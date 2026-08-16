#!/bin/bash
# Compile and run the Smart Home Alarm System (SHAS) CLI simulator, ignoring tests.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
MAVEN_SETTINGS="$REPO_ROOT/.mvn/settings.xml"

# Compile and execute the main class pcd.shas.Main, skipping tests
mvn -s "$MAVEN_SETTINGS" -f "$SCRIPT_DIR/pom.xml" compile exec:java -Dexec.mainClass="pcd.shas.Main" -DskipTests
