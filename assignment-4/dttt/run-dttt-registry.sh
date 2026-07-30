#!/bin/bash
# Wrapper script to run a standalone RMI registry for Distributed Tic-Tac-Toe.
# Usage:
#   ./run-dttt-registry.sh [port]

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
JAR_PATH="$DIR/target/ex2-distributed-tic-tac-toe-1.0-SNAPSHOT-jar-with-dependencies.jar"

REBUILD=false
if [ ! -f "$JAR_PATH" ]; then
    REBUILD=true
else
    if [ $(find "$DIR/src" -newer "$JAR_PATH" 2>/dev/null | wc -l) -gt 0 ]; then
        REBUILD=true
    elif [ "$DIR/pom.xml" -nt "$JAR_PATH" ]; then
        REBUILD=true
    fi
fi

if [ "$REBUILD" = true ]; then
    echo "Source code changes detected. Compiling and packaging project..."
    mvn -f "$DIR/pom.xml" package -DskipTests
    if [ $? -ne 0 ]; then
        echo "Error: Build failed."
        exit 1
    fi
fi

PORT="${1:-1099}"

echo "Starting RMI registry on port $PORT..."
java -jar "$JAR_PATH" registry "$PORT"
