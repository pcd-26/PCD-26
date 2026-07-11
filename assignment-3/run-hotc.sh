#!/bin/bash

# Get the directory where this script is located
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/src/main/go/pcd/hotc" || exit 1

echo "==> Compiling Heads-or-Tails Championship..."
go build -o hotc main.go
COMPILE_STATUS=$?

if [ $COMPILE_STATUS -eq 0 ]; then
    echo "==> Running Heads-or-Tails Championship..."
    ./hotc "$@"
    EXIT_STATUS=$?
else
    echo "==> Compilation failed."
    EXIT_STATUS=$COMPILE_STATUS
fi

exit $EXIT_STATUS
