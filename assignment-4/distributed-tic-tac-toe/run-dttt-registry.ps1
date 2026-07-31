# PowerShell wrapper script to run a standalone RMI registry for Distributed Tic-Tac-Toe.
# Usage:
#   .\run-dttt-registry.ps1 [port]

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$jarPath = Join-Path $scriptDir "target/distributed-tic-tac-toe-1.0-SNAPSHOT-jar-with-dependencies.jar"

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

$port = if ($args[0]) { $args[0] } else { "1099" }

Write-Host "Starting RMI registry on port $port..."
java -jar $jarPath registry $port
