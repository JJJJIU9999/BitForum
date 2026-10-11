#!/usr/bin/env python3
"""Dedicated test runner. Never reads business .env, never touches existing Compose projects."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

from test_inventory import ROOT, check

COMPOSE = ROOT / "docs/graduation/finalization/test-compose.yml"
GROUPS = ("unit", "integration", "cm22", "cm23", "cm24", "external-ai")


def docker(*args, env=None, input=None):
    return subprocess.check_output(["docker", *args], env=env, input=input, stderr=subprocess.PIPE).decode()


def protected_snapshot():
    ids = docker("ps", "-aq").split()
    objects = json.loads(docker("inspect", *ids)) if ids else []
    return {o["Name"].lstrip("/"): {"id": o["Id"], "image": o["Image"],
        "status": o["State"]["Status"], "started": o["State"]["StartedAt"],
        "finished": o["State"]["FinishedAt"],
        "mounts": sorted([{k: m.get(k) for k in ("Type", "Name", "Source", "Destination")} for m in o["Mounts"]], key=lambda m: json.dumps(m,sort_keys=True))}
        for o in objects if o["Name"].lstrip("/").startswith(("bit-forum-spring-", "bitforum-defense-", "bitforum-preview-"))}


def verify_mapping(container_id, port, host_port, project=None):
    obj = json.loads(docker("inspect", container_id))[0]
    bindings = obj["NetworkSettings"]["Ports"].get(str(port) + "/tcp") or []
    if not bindings or any(b["HostPort"] != str(host_port) for b in bindings):
        raise RuntimeError("Test service port mapping mismatch")
    if any(b["HostIp"] not in ("127.0.0.1", "0.0.0.0", "::") for b in bindings):
        raise RuntimeError("Test service host mapping mismatch")
    if project and obj["Config"]["Labels"].get("com.docker.compose.project") != project:
        raise RuntimeError("Container ownership mismatch")
    if project and (len(bindings) != 1 or bindings[0]["HostIp"] != "127.0.0.1"):
        raise RuntimeError("Local test service must bind only loopback")
    return obj["Id"]


def summarize(group, report_dir, entries, exit_code):
    suites = []
    sanitized = report_dir / "sanitized"
    sanitized.mkdir(exist_ok=True)
    for path in sorted(report_dir.glob("TEST-*.xml")):
        root = ET.parse(path).getroot()
        suite = {k: root.get(k) for k in ("name", "tests", "failures", "errors", "skipped", "time")}
        for k in ("tests", "failures", "errors", "skipped"):
            suite[k] = int(suite[k] or 0)
        suite["passed"] = suite["tests"] - suite["failures"] - suite["errors"] - suite["skipped"]
        suite["report_sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
        safe = ET.Element("testsuite", {k: str(v) for k, v in suite.items() if k != "report_sha256"})
        for case in root.findall("testcase"):
            node = ET.SubElement(safe, "testcase", {k: case.get(k, "") for k in ("classname", "name", "time")})
            for tag in ("failure", "error", "skipped"):
                for _ in case.findall(tag):
                    ET.SubElement(node, tag, {"message": "Details retained in private execution evidence"})
        ET.ElementTree(safe).write(sanitized / path.name, encoding="utf-8", xml_declaration=True)
        suites.append(suite)
    expected = {entry["class"] for entry in entries if entry["set"] == group}
    actual = {suite["name"] for suite in suites}
    missing = sorted(expected - actual)
    counts = {k: sum(s[k] for s in suites) for k in ("tests", "passed", "failures", "errors", "skipped")}
    ok = exit_code == 0 and not missing and bool(suites) and counts["failures"] == counts["errors"] == 0
    if group == "external-ai":
        ok = ok and counts["passed"] == 0 and counts["skipped"] > 0
        status = "SKIPPED_BY_POLICY" if ok else "FAILED"
    else:
        ok = ok and counts["skipped"] == 0 and counts["passed"] > 0
        status = "PASS" if ok else "FAILED"
    return {"set": group, "exit_code": exit_code, "status": status, "counts": counts,
            "missing_classes": missing, "suites": suites}, ok


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--ci", action="store_true", help="Use only GitHub job services with supplied container IDs")
    parser.add_argument("--sets", default=",".join(GROUPS))
    args = parser.parse_args()
    selected = args.sets.split(",")
    if len(set(selected)) != len(selected) or any(g not in GROUPS for g in selected):
        raise SystemExit("Unknown or duplicated test set")
    entries = check()
    token = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + secrets.token_hex(3)
    output = ROOT / "target/finalization" / token
    output.mkdir(parents=True)
    evidence = output / "summary"
    evidence.mkdir(parents=True)
    private = (ROOT.parent / "local-finalization" / token) if not args.ci else output / "private"
    private.mkdir(parents=True)
    private.chmod(0o700)
    env = os.environ.copy()
    for key in list(env):
        if "API_KEY" in key or key.startswith("COMPOSE_") or key in ("SPRING_APPLICATION_JSON", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "MAVEN_OPTS"):
            env.pop(key)
    env.update(BITFORUM_ISOLATED_TESTS="true", DEEPSEEK_CHAT_ENABLED="false",
        BITFORUM_RECOMMEND_REASON_ENABLED="false", BITFORUM_MODERATION_AUTO_APPROVE="false",
        M18_E2E_OBSERVATION="false", SPRING_DATA_REDIS_HOST="127.0.0.1", SPRING_DATA_REDIS_PORT="26379",
        SPRING_RABBITMQ_HOST="127.0.0.1", SPRING_RABBITMQ_PORT="25672", SPRING_DATASOURCE_USERNAME="root")
    for key in list(env):
        if key.startswith(("CM22_", "CM23_", "CM24_", "M17_", "EMBEDDING_PROBE")):
            env.pop(key)
    if sys.platform == "darwin":
        env["JAVA_HOME"] = subprocess.check_output(["/usr/libexec/java_home", "-v", "17"]).decode().strip()
        env["PATH"] = env["JAVA_HOME"] + "/bin:" + env["PATH"]
    before = protected_snapshot()
    (private / "protected-before.json").write_text(json.dumps(before, indent=2))
    summary = {"git_head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT).decode().strip(), "started_at": token, "sets": [], "protected_count": len(before)}
    project = "bitforum-finalization-verification-" + secrets.token_hex(4)
    compose = None
    owned_started = False
    success = True
    try:
        if args.ci:
            if os.environ.get("GITHUB_ACTIONS") != "true":
                raise RuntimeError("--ci is permitted only on the GitHub runner")
            mysql_id = verify_mapping(os.environ["CI_MYSQL_ID"], 3306, 23306)
            verify_mapping(os.environ["CI_REDIS_ID"], 6379, 26379)
            verify_mapping(os.environ["CI_RABBITMQ_ID"], 5672, 25672)
            env["SPRING_DATASOURCE_PASSWORD"] = os.environ["CI_DB_PASSWORD"]
            env["SPRING_RABBITMQ_USERNAME"] = "finalization_test"
            env["SPRING_RABBITMQ_PASSWORD"] = os.environ["CI_MQ_PASSWORD"]
        else:
            endpoint = json.loads(docker("context", "inspect"))[0]["Endpoints"]["docker"]["Host"]
            if not endpoint.startswith("unix://") or env.get("DOCKER_HOST", endpoint) != endpoint:
                raise RuntimeError("Local Unix Docker endpoint required")
            for port in (23306,26379,25672):
                with socket.socket() as s:
                    s.bind(("127.0.0.1", port))  # no outgoing connection to an existing service
            for image in ("mysql:8.0.46","redis/redis-stack-server:7.4.0-v8","rabbitmq:3-management"):
                docker("image", "inspect", image)
            env["FINALIZATION_DB_PASSWORD"] = secrets.token_hex(24)
            env["FINALIZATION_MQ_PASSWORD"] = secrets.token_hex(24)
            env["SPRING_DATASOURCE_PASSWORD"] = env["FINALIZATION_DB_PASSWORD"]
            env["SPRING_RABBITMQ_USERNAME"] = "finalization_test"
            env["SPRING_RABBITMQ_PASSWORD"] = env["FINALIZATION_MQ_PASSWORD"]
            envfile = private / "test.env"
            envfile.write_text("FINALIZATION_DB_PASSWORD=" + env["FINALIZATION_DB_PASSWORD"] + "\nFINALIZATION_MQ_PASSWORD=" + env["FINALIZATION_MQ_PASSWORD"] + "\n")
            envfile.chmod(0o600)
            compose = ["docker","compose","--env-file",str(envfile),"-p",project,"-f",str(COMPOSE)]
            owned_started = True
            with (private / "compose-start.log").open("w") as log:
                subprocess.run(compose + ["up","-d","--pull","never","--no-build","--wait","--wait-timeout","120"],
                    env=env, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=150)
            mysql_id = verify_mapping(subprocess.check_output(compose + ["ps","-q","mysql"], env=env).decode().strip(),3306,23306,project)
            verify_mapping(subprocess.check_output(compose + ["ps","-q","redis"], env=env).decode().strip(),6379,26379,project)
            verify_mapping(subprocess.check_output(compose + ["ps","-q","rabbitmq"], env=env).decode().strip(),5672,25672,project)
        summary["owned_project"] = project if not args.ci else "github-job-services"
        # Only this verified disposable MySQL instance receives schema creation.
        sql = "\n".join("CREATE DATABASE IF NOT EXISTS " + name + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
            for name in ("bitforum_finalization_test","bitforum_cm22_test","bitforum_cm23_test","bitforum_cm24_test"))
        deadline = time.monotonic() + 90
        while True:
            try:
                docker("exec",mysql_id,"sh","-c",'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=TCP -h127.0.0.1 -uroot -e "SELECT 1"')
                break
            except subprocess.CalledProcessError:
                if time.monotonic() >= deadline:
                    raise
                time.sleep(2)
        docker("exec","-i",mysql_id,"sh","-c",'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=TCP -h127.0.0.1 -uroot',input=sql.encode())
        temp_base = Path("/private/tmp") if sys.platform == "darwin" else Path("/tmp")
        explain_dir = temp_base / ("bitforum-cm24-finalization-" + token)
        explain_dir.mkdir(mode=0o700)
        for group in selected:
            group_env = env.copy()
            db = "bitforum_" + group + "_test" if group in ("cm22","cm23","cm24") else "bitforum_finalization_test"
            group_env["SPRING_DATASOURCE_URL"] = "jdbc:mysql://127.0.0.1:23306/" + db + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8"
            group_env["SPRING_FLYWAY_TARGET"] = "23" if group in ("cm22","cm23") else "24"
            group_env["BITFORUM_KB_INDEX"] = "bitforum-finalization-" + group
            if group in ("cm22","cm23","cm24"):
                group_env[group.upper() + "_ISOLATED_TESTS"] = "true"
            group_env["CM24_EVIDENCE_DIR"] = str(explain_dir)
            report_dir = output / group
            command = [str(ROOT / "mvnw"),"-B","-s",str(ROOT / "maven-settings.xml"),
                "-Dbitforum.test.includes=" + str(ROOT / "scripts/test-sets" / (group + ".txt")),
                "-Dbitforum.test.reports=" + str(report_dir),"test"]
            print("Running", group, "with dedicated test targets", flush=True)
            with (private / (group + ".log")).open("w") as log:
                result = subprocess.run(command,cwd=ROOT,env=group_env,stdout=log,stderr=subprocess.STDOUT,timeout=1800)
            item, ok = summarize(group, report_dir, entries, result.returncode)
            summary["sets"].append(item)
            (evidence / (group + ".json")).write_text(json.dumps(item,ensure_ascii=False,indent=2) + "\n")
            print(group,item["status"],item["counts"],flush=True)
            success = success and ok
        if (explain_dir / "explain.json").exists():
            shutil.copy2(explain_dir / "explain.json",evidence / "cm24-explain.json")
        schema_sql = """SELECT JSON_OBJECT(
          'database','bitforum_finalization_test',
          'flyway_version',(SELECT version FROM bitforum_finalization_test.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1),
          'columns',(SELECT JSON_ARRAYAGG(JSON_OBJECT('table',TABLE_NAME,'column',COLUMN_NAME,'type',COLUMN_TYPE,'nullable',IS_NULLABLE,'key',COLUMN_KEY))
            FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='bitforum_finalization_test'),
          'foreign_keys',(SELECT JSON_ARRAYAGG(JSON_OBJECT('table',TABLE_NAME,'column',COLUMN_NAME,'references_table',REFERENCED_TABLE_NAME,'references_column',REFERENCED_COLUMN_NAME))
            FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA='bitforum_finalization_test' AND REFERENCED_TABLE_NAME IS NOT NULL));"""
        schema = docker("exec","-i",mysql_id,"sh","-c",'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=TCP -h127.0.0.1 -uroot --batch --raw -N',input=schema_sql.encode())
        (evidence / "database-schema.json").write_text(json.dumps(json.loads(schema),ensure_ascii=False,indent=2) + "\n")
    except Exception as error:
        success = False
        summary["execution_error_type"] = type(error).__name__
        # Raw errors may contain credentials. Keep detailed text only in private evidence.
        (private / "execution-error.txt").write_text(str(error) + "\n" + getattr(error,"stderr",b"").decode(errors="replace"))
        print("Execution blocked:",type(error).__name__,"details in private evidence",flush=True)
    finally:
        if owned_started and compose:
            with (private / "compose-cleanup.log").open("w") as log:
                result = subprocess.run(compose + ["down","--volumes"],env=env,stdout=log,stderr=subprocess.STDOUT,timeout=90)
                summary["cleanup_exit_code"] = result.returncode
                success = success and result.returncode == 0
            remains = docker("ps","-aq","--filter","label=com.docker.compose.project=" + project).strip()
            volumes = docker("volume","ls","-q","--filter","label=com.docker.compose.project=" + project).strip()
            networks = docker("network","ls","-q","--filter","label=com.docker.compose.project=" + project).strip()
            summary["owned_resources_remaining"] = bool(remains or volumes or networks)
            success = success and not summary["owned_resources_remaining"]
        after = protected_snapshot()
        summary["protected_unchanged"] = before == after
        success = success and before == after
        summary["status"] = "PASS_WITH_EXPLICIT_MODEL_SKIPS" if success else "FAILED_OR_BLOCKED"
        (private / "protected-after.json").write_text(json.dumps(after,indent=2))
        (evidence / "summary.json").write_text(json.dumps(summary,ensure_ascii=False,indent=2) + "\n")
        print("Evidence:",evidence.relative_to(ROOT),"protected unchanged:",before == after,flush=True)
    return 0 if success else 1


if __name__ == "__main__":
    raise SystemExit(main())
