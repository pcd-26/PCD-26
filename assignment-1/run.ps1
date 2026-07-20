# Get the directory of the script
$ScriptDir = $PSScriptRoot
if (-not $ScriptDir) {
    $ScriptDir = Split-Path -Parent -Path $MyInvocation.MyCommand.Definition
}
if (-not $ScriptDir) {
    $ScriptDir = Get-Location
}

Push-Location $ScriptDir

# Compile the project
mvn compile
if ($LASTEXITCODE -eq 0) {
    # Run the program
    java -cp target/classes pcd.poool.SequentialPoool
}

Pop-Location
