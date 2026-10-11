#!/usr/bin/env python3
"""Explicit test-set catalog. CI checks drift; --write is only for reviewed catalog changes."""
import argparse
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "docs/graduation/finalization/test-inventory.json"
SETS = ROOT / "scripts/test-sets"


def inventory():
    entries = []
    for path in sorted((ROOT / "src/test/java").rglob("*.java")):
        source = re.sub(r"/\*.*?\*/", "", path.read_text(), flags=re.S)
        source = re.sub(r"^\s*//.*$", "", source, flags=re.M)
        if not re.search(r"@(Test|ParameterizedTest|RepeatedTest|TestFactory)\b", source):
            group = "support"
        elif path.stem == "M18EndToEndObservationTest" or (
            "DEEPSEEK_CHAT_ENABLED" in source and "@EnabledIfEnvironmentVariable" in source
        ):
            group = "external-ai"
        elif re.search(r"@EnabledIfEnvironmentVariable\([^)]*(?:M17_|EMBEDDING_PROBE|M18_)", source):
            group = "optional-probe"
        elif "CM22_ISOLATED_TESTS" in source:
            group = "cm22"
        elif "CM23_ISOLATED_TESTS" in source:
            group = "cm23"
        elif "CM24_ISOLATED_TESTS" in source:
            group = "cm24"
        elif path.stem.endswith("Probe"):
            group = "optional-probe"
        elif "@SpringBootTest" in source or "DriverManager.getConnection" in source:
            group = "integration"
        else:
            group = "unit"
        package = re.search(r"package ([\w.]+);", source).group(1)
        entries.append({"path": str(path.relative_to(ROOT)), "class": package + "." + path.stem,
                        "set": group, "test_annotations": len(re.findall(r"@(Test|ParameterizedTest|RepeatedTest|TestFactory)\b", source))})
    return entries


def check():
    entries = inventory()
    if json.loads(CATALOG.read_text())["entries"] != entries:
        raise SystemExit("Test inventory drift: review classifications and update catalog before CI")
    for group in sorted({entry["set"] for entry in entries} - {"support", "optional-probe"}):
        expected = "".join("**/" + e["class"].split(".")[-1] + ".java\n" for e in entries if e["set"] == group)
        if (SETS / (group + ".txt")).read_text() != expected:
            raise SystemExit("Test set drift: " + group)
    return entries


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()
    entries = inventory()
    if args.write:
        SETS.mkdir(exist_ok=True)
        CATALOG.write_text(json.dumps({"entries": entries}, ensure_ascii=False, indent=2) + "\n")
        for group in sorted({entry["set"] for entry in entries} - {"support", "optional-probe"}):
            (SETS / (group + ".txt")).write_text("".join("**/" + e["class"].split(".")[-1] + ".java\n" for e in entries if e["set"] == group))
    else:
        check()
    from collections import Counter
    print(dict(Counter(e["set"] for e in entries)))
