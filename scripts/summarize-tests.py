#!/usr/bin/env python3
"""Summarize one already-executed set, require all expected classes and write sanitized XML."""
import argparse
import json
from pathlib import Path
from test_inventory import check
from importlib.util import spec_from_file_location, module_from_spec

spec = spec_from_file_location("isolated_runner", Path(__file__).with_name("run-isolated-tests.py"))
runner = module_from_spec(spec)
spec.loader.exec_module(runner)
parser = argparse.ArgumentParser()
parser.add_argument("group")
parser.add_argument("reports", type=Path)
parser.add_argument("--exit-code", type=int, default=0)
args = parser.parse_args()
result, ok = runner.summarize(args.group,args.reports,check(),args.exit_code)
(args.reports / "summary.json").write_text(json.dumps(result,ensure_ascii=False,indent=2) + "\n")
print(result["status"], result["counts"])
raise SystemExit(0 if ok else 1)
