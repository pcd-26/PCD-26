#!/bin/bash
# Compile and run the Clustered Smart Home Alarm System (CSHAS) simulator, ignoring tests.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Compile and execute the main class pcd.shas.Main, skipping tests
mvn -f "$SCRIPT_DIR/pom.xml" compile exec:java -Dexec.mainClass="pcd.shas.Main" -DskipTests
