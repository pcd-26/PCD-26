#!/bin/bash
# Wrapper script to execute tests for Distributed Tic-Tac-Toe.

# Get the directory of this script
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

echo "Executing Tic-Tac-Toe test suite..."
mvn -f "$DIR/pom.xml" test
