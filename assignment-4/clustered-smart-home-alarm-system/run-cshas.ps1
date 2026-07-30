# Compile and run the Clustered Smart Home Alarm System (CSHAS) simulator, ignoring tests.
$execArgs = ($args -join ' ')
$mvnArgs = @(
    '-f'
    "$PSScriptRoot\pom.xml"
    'compile'
    'exec:java'
    '-Dexec.mainClass=pcd.shas.Main'
    '-DskipTests'
    "-Dexec.args=$execArgs"
)
& mvn @mvnArgs
