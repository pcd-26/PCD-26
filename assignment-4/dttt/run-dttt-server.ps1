# PowerShell wrapper script to run the Distributed Tic-Tac-Toe Server.
# Usage:
#   .\run-dttt-server.ps1 [port]

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

# Default port to 1099 if not specified
$port = if ($args[0]) { $args[0] } else { "1099" }

Write-Host "Starting Tic-Tac-Toe Server on port $port..."
java -jar $jarPath server $port
