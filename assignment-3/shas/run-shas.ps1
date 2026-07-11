# Compile and run the Smart Home Alarm System (SHAS) CLI simulator, ignoring tests.
mvn -f "$PSScriptRoot\pom.xml" compile exec:java -Dexec.mainClass="pcd.shas.Main" -DskipTests
