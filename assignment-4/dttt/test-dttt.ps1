# PowerShell wrapper script to execute tests for Distributed Tic-Tac-Toe.

# Get script directory
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

Write-Host "Executing Tic-Tac-Toe test suite..."
mvn -f "$scriptDir/pom.xml" test
