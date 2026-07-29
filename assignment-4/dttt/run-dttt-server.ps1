# PowerShell wrapper script to run the Distributed Tic-Tac-Toe Server.
# Usage:
#   .\run-dttt-server.ps1 [registryHost] [registryPort] [serviceName]

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$jarPath = Join-Path $scriptDir "target/ex2-distributed-tic-tac-toe-1.0-SNAPSHOT-jar-with-dependencies.jar"

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

# Default registry parameters if not specified
$registryHost = if ($args[0]) { $args[0] } else { "localhost" }
$registryPort = if ($args[1]) { $args[1] } else { "1099" }
$serviceName = if ($args[2]) { $args[2] } else { "Lobby" }

Write-Host "Starting Tic-Tac-Toe Server binding '$serviceName' at $registryHost:$registryPort..."
java -jar $jarPath server $registryHost $registryPort $serviceName
