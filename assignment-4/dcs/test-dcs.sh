#!/bin/bash
# Run tests for the Distributed Critical Sections (DCS) middleware (Exercise 3 of Assignment 4).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/docker-compose.rabbitmq.yml"
COMPOSE_PROJECT="pcd-dcs-rabbitmq"

# Function to check if RabbitMQ is running
check_rabbitmq() {
    timeout 1 bash -c 'cat < /dev/null > /dev/tcp/localhost/5672' >/dev/null 2>&1
}

# Start RabbitMQ in docker if not already running
RMQ_DOCKER_STARTED=false
if ! check_rabbitmq; then
    echo "RabbitMQ is not running on localhost:5672. Attempting to start RabbitMQ via Docker for tests..."
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

# Execute the test suite for pcd.dcs classes
mvn -f "$SCRIPT_DIR/pom.xml" test -Dtest="pcd.dcs.**"
TEST_EXIT_CODE=$?

# Clean up Docker if we started it
if [ "$RMQ_DOCKER_STARTED" = true ]; then
    echo "Stopping RabbitMQ Docker container..."
    docker compose -p "$COMPOSE_PROJECT" -f "$COMPOSE_FILE" down --remove-orphans >/dev/null
fi

exit $TEST_EXIT_CODE
