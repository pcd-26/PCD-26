# Get the directory of the script
$ScriptDir = $PSScriptRoot
if (-not $ScriptDir) {
    $ScriptDir = Split-Path -Parent -Path $MyInvocation.MyCommand.Definition
}
if (-not $ScriptDir) {
    $ScriptDir = Get-Location
}

Push-Location $ScriptDir

# Run maven tests
mvn test

Pop-Location
