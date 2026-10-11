#!/usr/bin/env python3
"""Create a dedicated pilot CA and short-lived server certificate; never install trust."""
import argparse
import ipaddress
from pathlib import Path
import secrets
import subprocess
import os


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path, help="new private output directory")
    parser.add_argument("--lan-ip", action="append", default=[], help="current IPv4 address")
    args = parser.parse_args()
    addresses = ["127.0.0.1"]
    for value in args.lan_ip:
        address = ipaddress.IPv4Address(value)
        if address.is_unspecified or address.is_multicast or address.is_reserved:
            parser.error("LAN IP must be a specific unicast IPv4 address")
        addresses.append(str(address))
    output = args.directory.resolve()
    if output.exists():
        parser.error("output directory already exists; certificates will not be overwritten")

    os.umask(0o077)
    output.mkdir(parents=True, mode=0o700)
    authority = output / "authority"
    server = output / "server"
    authority.mkdir(mode=0o700)
    server.mkdir(mode=0o700)
    ca_config = authority / "ca.cnf"
    ca_config.write_text("""[req]
prompt = no
distinguished_name = dn
x509_extensions = ca
[dn]
CN = BitForum dedicated pilot CA
[ca]
basicConstraints = critical,CA:TRUE,pathlen:0
keyUsage = critical,keyCertSign,cRLSign
subjectKeyIdentifier = hash
""")
    server_config = authority / "server.cnf"
    server_config.write_text("""[req]
prompt = no
distinguished_name = dn
[dn]
CN = localhost
[server]
basicConstraints = critical,CA:FALSE
keyUsage = critical,digitalSignature,keyEncipherment
extendedKeyUsage = serverAuth
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid,issuer
subjectAltName = @names
[names]
DNS.1 = localhost
""" + "".join(f"IP.{i} = {ip}\n" for i, ip in enumerate(dict.fromkeys(addresses), 1)))

    def openssl(*arguments):
        subprocess.run(["openssl", *map(str, arguments)], check=True, stdout=subprocess.DEVNULL,
                       stderr=subprocess.DEVNULL)

    openssl("req", "-x509", "-newkey", "rsa:2048", "-nodes", "-sha256", "-days", "30",
            "-config", ca_config, "-keyout", authority / "ca.key", "-out", output / "ca.crt")
    openssl("req", "-new", "-newkey", "rsa:2048", "-nodes", "-sha256", "-config", server_config,
            "-keyout", server / "server.key", "-out", authority / "server.csr")
    openssl("x509", "-req", "-in", authority / "server.csr", "-CA", output / "ca.crt",
            "-CAkey", authority / "ca.key", "-set_serial", "0x" + secrets.token_hex(16),
            "-days", "14", "-sha256", "-extfile", server_config, "-extensions", "server",
            "-out", server / "server.crt")
    openssl("verify", "-CAfile", output / "ca.crt", server / "server.crt")
    print(f"CA certificate: {output / 'ca.crt'}")
    print(f"BITFORUM_TLS_DIR={server}")
    print("Trust unchanged. Keep authority/ offline; distribute only ca.crt after approval.")


if __name__ == "__main__":
    main()
