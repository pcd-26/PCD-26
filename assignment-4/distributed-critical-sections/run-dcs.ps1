# Compile and run two concurrent instances of ProcessApp to demonstrate distributed critical sections.
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$sharedLog = Join-Path $scriptDir "dcs_shared.log"
$composeFile = Join-Path $scriptDir "docker-compose.rabbitmq.yml"
$composeProject = "pcd-dcs-rabbitmq"

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
    Write-Host "RabbitMQ is not running on localhost:5672. Attempting to start RabbitMQ via Docker..."
    docker compose -p $composeProject -f $composeFile up -d rabbitmq
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

if (Test-Path $sharedLog) {
    Remove-Item $sharedLog
}
New-Item -Path $sharedLog -ItemType File | Out-Null

Write-Host "Compiling the project..."
mvn -f "$scriptDir/pom.xml" compile -DskipTests

Write-Host "Starting Process-A and Process-B in the background..."
$procA = Start-Process mvn -ArgumentList "-f `"$scriptDir/pom.xml`" exec:java -Dexec.mainClass=`"pcd.dcs.demo.ProcessApp`" -Dexec.args=`"Process-A`"" -NoNewWindow -PassThru
$procB = Start-Process mvn -ArgumentList "-f `"$scriptDir/pom.xml`" exec:java -Dexec.mainClass=`"pcd.dcs.demo.ProcessApp`" -Dexec.args=`"Process-B`"" -NoNewWindow -PassThru

Write-Host "Waiting for processes to complete..."
$procA.WaitForExit()
$procB.WaitForExit()

Write-Host "--------------------------------------------------------"
Write-Host "Final contents of dcs_shared.log:"
Get-Content $sharedLog

if ($rmqDockerStarted) {
    Write-Host "Stopping RabbitMQ Docker container..."
    docker compose -p $composeProject -f $composeFile down --remove-orphans | Out-Null
}

Write-Host "Done."
