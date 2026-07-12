# Run tests for the Clustered Smart Home Alarm System (CSHAS).
mvn -f "$PSScriptRoot\pom.xml" test -Dtest="pcd.shas.**"
