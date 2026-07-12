#!/bin/bash
# Wrapper script to run the Distributed Tic-Tac-Toe CLI Client.
# Usage:
#   ./run-dttt-cli.sh [host] [port]

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
JAR_PATH="$DIR/target/distributed-ttt-1.0-SNAPSHOT-jar-with-dependencies.jar"

# Build if jar does not exist
if [ ! -f "$JAR_PATH" ]; then
    echo "Executable JAR not found. Compiling and packaging project..."
    mvn -f "$DIR/pom.xml" package -DskipTests
    if [ $? -ne 0 ]; then
        echo "Error: Build failed."
        exit 1
    fi
fi

# Default host and port if not specified
HOST="${1:-localhost}"
PORT="${2:-1099}"

echo "Starting CLI Client connecting to server at $HOST:$PORT..."
java -jar "$JAR_PATH" client "$HOST" "$PORT" --cli
