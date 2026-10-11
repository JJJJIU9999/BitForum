#!/usr/bin/env python3
"""Run mutating HTTPS checks ONLY against an isolated pilot database and accounts."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import socket
import ssl
import struct
import urllib.error
import urllib.parse
import urllib.request
import zlib


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def png(side):
    def chunk(kind, data):
        return struct.pack("!I", len(data)) + kind + data + struct.pack("!I", zlib.crc32(kind + data))
    pixels = b"".join(b"\0" + os.urandom(side * 3) for _ in range(side))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack("!2I5B", side, side, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(pixels)) + chunk(b"IEND", b""))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True, help="HTTPS origin, standard port 443")
    parser.add_argument("--ca", required=True)
    parser.add_argument("--accounts", type=Path, required=True, help="private JSON: owner/other/reviewer credentials")
    parser.add_argument("--output", type=Path, required=True, help="sanitized evidence JSON")
    parser.add_argument("--isolated", action="store_true", required=True, help="confirm target is disposable")
    args = parser.parse_args()
    url = urllib.parse.urlsplit(args.base_url)
    if url.scheme != "https" or url.port not in (None, 443) or url.path not in ("", "/") or url.username:
        parser.error("base-url must be an HTTPS origin on port 443")
    base = args.base_url.rstrip("/")
    context = ssl.create_default_context(cafile=args.ca)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect(),
                                        urllib.request.HTTPSHandler(context=context))
    results = []

    def save():
        args.output.write_text(json.dumps({"origin": base, "checked_at": datetime.now(timezone.utc).isoformat(),
                                          "checks": results}, indent=2) + "\n")

    def check(name, valid):
        results.append({"check": name, "passed": bool(valid)})
        save()
        if not valid:
            raise RuntimeError(f"check failed: {name}")

    def request(path, method="GET", body=None, token=None, content_type="application/json", origin=base):
        headers = {"Content-Type": content_type}
        if token:
            headers["Authorization"] = "Bearer " + token
        if isinstance(body, dict):
            body = json.dumps(body).encode()
        req = urllib.request.Request(origin + path, data=body, headers=headers, method=method)
        try:
            response = opener.open(req, timeout=20)
        except urllib.error.HTTPError as error:
            response = error
        return response.code, response.headers, response.read()

    def api(path, method="GET", body=None, token=None):
        status, _, raw = request(path, method, body, token)
        data = json.loads(raw)
        check(method + " " + path, status == 200 and data.get("code") == 200)
        return data.get("data")

    for method in ("GET", "POST"):
        try:
            status, headers, _ = request("/api/article/page?contentType=QUESTION", method,
                                         b"{}" if method == "POST" else None,
                                         origin="http://" + url.hostname)
        except (OSError, urllib.error.URLError) as error:
            results.append({"check": "308 preserves path/query " + method, "passed": False,
                            "network_error": type(error).__name__})
            save()
            continue
        check("308 preserves path/query " + method, status == 308 and headers.get("Location") ==
              base + "/api/article/page?contentType=QUESTION")
    for version in (ssl.TLSVersion.TLSv1_2, ssl.TLSVersion.TLSv1_3):
        tls = ssl.create_default_context(cafile=args.ca)
        tls.minimum_version = tls.maximum_version = version
        with socket.create_connection((url.hostname, 443), timeout=5) as sock:
            with tls.wrap_socket(sock, server_hostname=url.hostname) as secure:
                check("TLS " + version.name, secure.version() in ("TLSv1.2", "TLSv1.3"))
    for name, tls, hostname in (("untrusted CA rejected", ssl.create_default_context(), url.hostname),
                                ("wrong SAN rejected", context, "wrong.invalid")):
        rejected = False
        try:
            with socket.create_connection((url.hostname, 443), timeout=5) as sock:
                with tls.wrap_socket(sock, server_hostname=hostname):
                    pass
        except ssl.SSLCertVerificationError:
            rejected = True
        check(name, rejected)
    for path in ("/", "/questions", "/login", "/publish?type=QUESTION"):
        status, _, raw = request(path)
        check("SPA " + path, status == 200 and b'<div id="root">' in raw)
    assets = re.findall(r'(?:src|href)="([^\"]+)"', raw.decode())
    check("HTML resources same origin", all(x.startswith("/") and not x.startswith("//") for x in assets))
    for asset in assets:
        check("static asset " + asset, request(asset)[0] == 200)
    api("/api/article/page?contentType=QUESTION")
    check("anonymous profile denied", request("/api/user/profile")[0] == 401)
    tokens = {}
    for role, credentials in json.loads(args.accounts.read_text()).items():
        tokens[role] = api("/api/user/login", "POST", credentials)["token"]
    api("/api/user/profile", token=tokens["owner"])

    def upload(kind, image):
        body = (b'--m20boundary\r\nContent-Disposition: form-data; name="file"; filename="pilot.png"\r\n'
                b'Content-Type: image/png\r\n\r\n' + image + b'\r\n--m20boundary--\r\n')
        status, _, raw = request("/api/upload/" + kind, "POST", body, tokens["owner"],
                                 "multipart/form-data; boundary=m20boundary")
        return status, raw

    for kind, side in (("avatar", 650), ("article-cover", 1300)):
        image = png(side)
        status, raw = upload(kind, image)
        data = json.loads(raw)
        check("legal >1MB " + kind, len(image) > 1024**2 and status == 200 and data.get("code") == 200)
        resource = data["data"]
        check("pending resource has no public URL " + kind,
              resource["status"] == "PENDING" and "url" not in resource)
        path = f'/api/images/{resource["id"]}'
        check("pending public denied " + kind, request(path + "/content")[0] == 404)
        check("anonymous preview denied " + kind, request(path + "/preview")[0] == 401)
        check("other user preview denied " + kind, request(path + "/preview", token=tokens["other"])[0] == 403)
        for role in ("owner", "reviewer"):
            status, headers, raw = request(path + "/preview", token=tokens[role])
            check(role + " private preview " + kind, status == 200 and raw == image and
                  headers.get("Cache-Control") == "no-store" and headers.get_content_type() == "image/png")
        api("/api/admin/images/" + str(resource["id"]) + "/approve", "PUT", token=tokens["reviewer"])
        status, headers, raw = request(path + "/content")
        check("approved anonymous image " + kind, status == 200 and raw == image and
              headers.get("X-Content-Type-Options") == "nosniff")
    status, raw = upload("avatar", png(850))
    check("avatar >2MB business rejection", status == 400 and json.loads(raw).get("code") == 400)
    status, raw = upload("article-cover", png(1400))
    check("cover >5MB business rejection", status == 400 and json.loads(raw).get("code") == 400)
    check("request >6MB Nginx 413", upload("article-cover", b"x" * (7 * 1024**2))[0] == 413)
    for path in ("/uploads/avatar/pilot.png", "/uploads/private/avatar/pilot.png"):
        check("legacy upload blocked " + path, request(path)[0] == 404)
    passed = sum(result["passed"] for result in results)
    print(f"Passed {passed}/{len(results)} HTTPS checks; no credentials saved to evidence.")
    if passed != len(results):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
