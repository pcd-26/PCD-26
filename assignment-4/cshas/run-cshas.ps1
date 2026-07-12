# Compile and run the Clustered Smart Home Alarm System (CSHAS) simulator, ignoring tests.
mvn -f "$PSScriptRoot\pom.xml" compile exec:java -Dexec.mainClass="pcd.shas.Main" -DskipTests
