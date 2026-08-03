# Compile and run the Smart Home Alarm System (SHAS) CLI simulator, ignoring tests.
$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$MavenSettings = Join-Path $RepoRoot ".mvn\settings.xml"
mvn -s "$MavenSettings" -f "$PSScriptRoot\pom.xml" compile exec:java -Dexec.mainClass="pcd.shas.Main" -DskipTests
