#!/bin/bash
# Usage: ./assignment-2/run-cli.sh [directory] [maxFS] [nb] [sizeUnit: B|KiB|MiB|GiB] [paradigm: vt|rx|loop]
# Example: ./assignment-2/run-cli.sh . 10 5 MiB vt

ARGS="${*:-. 10 5 MiB vt}"
mvn -f assignment-2/pom.xml clean compile exec:java -Dexec.mainClass="pcd.assignment2.cli.FSStatCLI" -Dexec.args="$ARGS"

