#!/usr/bin/env bash
# Default entry: genuine no-infrastructure tests only. Never reads business .env.
# Full automated service coverage: python3 scripts/run-isolated-tests.py
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -x /usr/libexec/java_home ]; then
    export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
fi
python3 scripts/test_inventory.py
exec ./mvnw -s maven-settings.xml "$@" test
