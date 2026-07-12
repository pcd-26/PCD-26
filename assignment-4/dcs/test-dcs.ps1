# Run tests for the Distributed Critical Sections (DCS) middleware (Exercise 3 of Assignment 4).
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

function Check-RabbitMQ {
    $socket = New-Object System.Net.Sockets.TcpClient
    try {
        $socket.Connect("localhost", 5672)
        $socket.Close()
        return $true
    } catch {
        return $false
    }
}

$rmqDockerStarted = $false
if (-not (Check-RabbitMQ)) {
    Write-Host "RabbitMQ is not running on localhost:5672. Attempting to start RabbitMQ via Docker for tests..."
    docker run -d --name rabbitmq-dcs-test -p 5672:5672 -p 15672:15672 rabbitmq:3-management
    $rmqDockerStarted = $true
    Write-Host "Waiting for RabbitMQ to start..."
    for ($i = 0; $i -lt 30; $i++) {
        if (Check-RabbitMQ) {
            Write-Host "RabbitMQ is ready!"
            break
        }
        Start-Sleep -Seconds 1
    }
    if (-not (Check-RabbitMQ)) {
        Write-Host "Failed to start RabbitMQ. Please run RabbitMQ manually on port 5672."
        exit 1
    }
} else {
    Write-Host "RabbitMQ is already running on localhost:5672."
}

# Execute the test suite for pcd.dcs classes
mvn -f "$scriptDir/pom.xml" test -Dtest="pcd.dcs.**"
$testExitCode = $LASTEXITCODE

if ($rmqDockerStarted) {
    Write-Host "Stopping RabbitMQ Docker container..."
    docker stop rabbitmq-dcs-test | Out-Null
    docker rm rabbitmq-dcs-test | Out-Null
}

exit $testExitCode
