#!/bin/bash

# Get the directory where this script is located
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/src/main/go/pcd/hotc" || exit 1

echo "==> Running Heads-or-Tails Championship tests..."
go test -v ./... "$@"
exit $?
