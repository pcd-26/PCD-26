# Get script directory
$scriptDir = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent -Path $MyInvocation.MyCommand.Definition }
if ([string]::IsNullOrEmpty($scriptDir)) {
    $scriptDir = Get-Location
}

# Navigate to go project
Set-Location -Path "$scriptDir/src/main/go/pcd/hotc"

Write-Host "==> Running Heads-or-Tails Championship tests..."
go test -v ./... $args
exit $LASTEXITCODE
