#!/bin/bash
# Compile and run one CSHAS node, or the distributed process demo, ignoring tests.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MAIN_CLASS="pcd.shas.Main"
EXEC_ARGS="$*"

if [[ "${1:-}" == "demo" ]]; then
  MAIN_CLASS="pcd.shas.DemoMain"
  shift
  EXEC_ARGS="$*"
fi

mvn -f "$SCRIPT_DIR/pom.xml" compile exec:java -Dexec.mainClass="$MAIN_CLASS" -DskipTests -Dexec.args="$EXEC_ARGS"
