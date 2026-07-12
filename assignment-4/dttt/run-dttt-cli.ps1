# PowerShell wrapper script to run the Distributed Tic-Tac-Toe CLI Client.
# Usage:
#   .\run-dttt-cli.ps1 [host] [port] [serviceName]

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$jarPath = Join-Path $scriptDir "target/distributed-ttt-1.0-SNAPSHOT-jar-with-dependencies.jar"

# Automatically rebuild if JAR doesn't exist or source files are newer than the JAR
$rebuild = $false
if (-not (Test-Path $jarPath)) {
    $rebuild = $true
} else {
    $jarTime = (Get-Item $jarPath).LastWriteTime
    $srcFiles = Get-ChildItem -Path (Join-Path $scriptDir "src") -Recurse -File
    $pomFile = Get-Item (Join-Path $scriptDir "pom.xml")
    
    foreach ($file in $srcFiles) {
        if ($file.LastWriteTime -gt $jarTime) {
            $rebuild = $true
            break
        }
    }
    if ($pomFile.LastWriteTime -gt $jarTime) {
        $rebuild = $true
    }
}

if ($rebuild) {
    Write-Host "Source code changes detected. Compiling and packaging project..."
    mvn -f "$scriptDir/pom.xml" package -DskipTests
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Error: Build failed."
        exit 1
    }
}

# Default host, port, and service name if not specified
$hostName = if ($args[0]) { $args[0] } else { "localhost" }
$port = if ($args[1]) { $args[1] } else { "1099" }
$serviceName = if ($args[2]) { $args[2] } else { "Lobby" }

Write-Host "Starting CLI Client connecting to server at $hostName:$port (service '$serviceName')..."
java -jar $jarPath client $hostName $port $serviceName --cli
