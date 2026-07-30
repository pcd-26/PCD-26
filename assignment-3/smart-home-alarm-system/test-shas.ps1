# Run tests for the Smart Home Alarm System (SHAS) (Exercise 1 of Assignment 3).
mvn -f "$PSScriptRoot\pom.xml" test -Dtest="pcd.shas.**"
