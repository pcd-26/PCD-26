#!/bin/bash
# Run tests for the Smart Home Alarm System (SHAS) (Exercise 1 of Assignment 3).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Execute the test suite for pcd.shas classes
mvn -f "$SCRIPT_DIR/pom.xml" test -Dtest="pcd.shas.**"
