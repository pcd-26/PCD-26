# PowerShell wrapper script to run the Distributed Tic-Tac-Toe Server.
# Usage:
#   .\run-dttt-server.ps1 [port]

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

# Default port to 1099 if not specified
$port = if ($args[0]) { $args[0] } else { "1099" }

Write-Host "Starting Tic-Tac-Toe Server on port $port..."
java -jar $jarPath server $port
