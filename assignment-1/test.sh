#!/bin/bash

# Find the directory where this script is located
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

# Change directory to the script's folder
cd "$SCRIPT_DIR"

# Build and run tests
mvn test
