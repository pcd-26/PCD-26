# PowerShell wrapper script to execute tests for Distributed Tic-Tac-Toe.

# Get script directory
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

Write-Host "Executing Tic-Tac-Toe test suite..."
mvn -B -f "$scriptDir/pom.xml" clean verify
