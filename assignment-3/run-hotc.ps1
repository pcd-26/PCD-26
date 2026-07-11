# Get script directory
$scriptDir = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent -Path $MyInvocation.MyCommand.Definition }
if ([string]::IsNullOrEmpty($scriptDir)) {
    $scriptDir = Get-Location
}

# Navigate to go project
Set-Location -Path "$scriptDir/src/main/go/pcd/hotc"

# Determine OS-specific binary name
$binaryName = "hotc"
if ($IsWindows -or $env:OS -like "*Windows*") {
    $binaryName = "hotc.exe"
}

Write-Host "==> Compiling Heads-or-Tails Championship..."
go build -o $binaryName main.go

if ($LASTEXITCODE -eq 0) {
    Write-Host "==> Running Heads-or-Tails Championship..."
    & ".\$binaryName" $args
    $exitCode = $LASTEXITCODE
} else {
    Write-Error "==> Compilation failed."
    $exitCode = $LASTEXITCODE
}

exit $exitCode
