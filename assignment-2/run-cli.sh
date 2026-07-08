#!/bin/bash
# Usage: ./assignment-2/run-cli.sh [directory] [maxFS] [nb] [paradigm: vt|rx|loop]
# Example: ./assignment-2/run-cli.sh . 10485760 5 vt

ARGS="${*:-. 10485760 5 vt}"
mvn -f assignment-2/pom.xml compile exec:java -Dexec.mainClass="pcd.assignment2.cli.FSStatCLI" -Dexec.args="$ARGS"
