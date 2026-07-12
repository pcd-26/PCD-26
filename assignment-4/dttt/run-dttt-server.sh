#!/bin/bash
# Wrapper script to run the Distributed Tic-Tac-Toe Server.
# Usage:
#   ./run-dttt-server.sh [port]

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

# Default port to 1099 if not specified
PORT="${1:-1099}"

echo "Starting Tic-Tac-Toe Server on port $PORT..."
java -jar "$JAR_PATH" server "$PORT"
