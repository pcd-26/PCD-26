#!/bin/bash
# Wrapper script to run the Distributed Tic-Tac-Toe Server.
# Usage:
#   ./run-dttt-server.sh [port]

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
JAR_PATH="$DIR/target/distributed-ttt-1.0-SNAPSHOT-jar-with-dependencies.jar"

# Automatically rebuild if JAR doesn't exist or source files are newer than the JAR
REBUILD=false
if [ ! -f "$JAR_PATH" ]; then
    REBUILD=true
else
    # Check if any file in src/ is newer than the JAR
    if [ $(find "$DIR/src" -newer "$JAR_PATH" 2>/dev/null | wc -l) -gt 0 ]; then
        REBUILD=true
    # Check if pom.xml is newer than the JAR
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

# Default port to 1099 if not specified
PORT="${1:-1099}"

echo "Starting Tic-Tac-Toe Server on port $PORT..."
java -jar "$JAR_PATH" server "$PORT"
