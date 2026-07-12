# PowerShell wrapper script to run the Distributed Tic-Tac-Toe GUI Client.
# Usage:
#   .\run-dttt-gui.ps1 [host] [port]

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$jarPath = Join-Path $scriptDir "target/distributed-ttt-1.0-SNAPSHOT-jar-with-dependencies.jar"

# Build if jar does not exist
if (-not (Test-Path $jarPath)) {
    Write-Host "Executable JAR not found. Compiling and packaging project..."
    mvn -f "$scriptDir/pom.xml" package -DskipTests
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Error: Build failed."
        exit 1
    }
}

# Default host and port if not specified
$hostName = if ($args[0]) { $args[0] } else { "localhost" }
$port = if ($args[1]) { $args[1] } else { "1099" }

Write-Host "Starting GUI Client connecting to server at $hostName:$port..."
java -jar $jarPath client $hostName $port
