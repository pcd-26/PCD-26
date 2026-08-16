#!/bin/bash
# Run tests for the Smart Home Alarm System (SHAS) (Exercise 1 of Assignment 3).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
MAVEN_SETTINGS="$REPO_ROOT/.mvn/settings.xml"

# Execute the test suite for pcd.shas classes
mvn -s "$MAVEN_SETTINGS" -f "$SCRIPT_DIR/pom.xml" test -Dtest="pcd.shas.**"
