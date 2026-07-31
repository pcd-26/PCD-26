#!/bin/bash
# Compile and run two concurrent instances of ProcessApp to demonstrate distributed critical sections.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SHARED_LOG="$SCRIPT_DIR/dcs_shared.log"
COMPOSE_FILE="$SCRIPT_DIR/docker-compose.rabbitmq.yml"
COMPOSE_PROJECT="pcd-dcs-rabbitmq"

# Function to check if RabbitMQ is running
check_rabbitmq() {
    timeout 1 bash -c 'cat < /dev/null > /dev/tcp/localhost/5672' >/dev/null 2>&1
}

# Start RabbitMQ in docker if not already running
RMQ_DOCKER_STARTED=false
if ! check_rabbitmq; then
    echo "RabbitMQ is not running on localhost:5672. Attempting to start RabbitMQ via Docker..."
    docker compose -p "$COMPOSE_PROJECT" -f "$COMPOSE_FILE" up -d rabbitmq
    RMQ_DOCKER_STARTED=true
    echo "Waiting for RabbitMQ to start..."
    for i in {1..30}; do
        if check_rabbitmq; then
            echo "RabbitMQ port is open. Waiting 5 more seconds for broker initialization..."
            sleep 5
            break
        fi
        sleep 1
    done
    if ! check_rabbitmq; then
        echo "Failed to start RabbitMQ. Please run RabbitMQ manually on port 5672."
        exit 1
    fi
else
    echo "RabbitMQ is already running on localhost:5672."
fi

# Clean up previous log
rm -f "$SHARED_LOG"
touch "$SHARED_LOG"

echo "Compiling the project..."
mvn -f "$SCRIPT_DIR/pom.xml" compile -DskipTests

echo "Starting Process-A and Process-B in the background..."
mvn -f "$SCRIPT_DIR/pom.xml" exec:java -Dexec.mainClass="pcd.dcs.demo.ProcessApp" -Dexec.args="Process-A" > /dev/null &
PID_A=$!
mvn -f "$SCRIPT_DIR/pom.xml" exec:java -Dexec.mainClass="pcd.dcs.demo.ProcessApp" -Dexec.args="Process-B" > /dev/null &
PID_B=$!

echo "Tailing dcs_shared.log to show critical section access (Ctrl+C to stop)..."
echo "Press Enter to exit once the runs are complete."
echo "--------------------------------------------------------"

# Show the shared log file changes in real-time
tail -f "$SHARED_LOG" &
PID_TAIL=$!

# Wait for both processes to finish
wait $PID_A
wait $PID_B

# Give tail a second to catch up, then kill it
sleep 1
kill $PID_TAIL 2>/dev/null

echo "--------------------------------------------------------"
echo "Processes finished. Final contents of $SHARED_LOG:"
cat "$SHARED_LOG"

# Clean up Docker if we started it
if [ "$RMQ_DOCKER_STARTED" = true ]; then
    echo "Stopping RabbitMQ Docker container..."
    docker compose -p "$COMPOSE_PROJECT" -f "$COMPOSE_FILE" down --remove-orphans >/dev/null
fi

echo "Done."
