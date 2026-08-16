# Run tests for the Smart Home Alarm System (SHAS) (Exercise 1 of Assignment 3).
$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$MavenSettings = Join-Path $RepoRoot ".mvn\settings.xml"
mvn -s "$MavenSettings" -f "$PSScriptRoot\pom.xml" test -Dtest="pcd.shas.**"
