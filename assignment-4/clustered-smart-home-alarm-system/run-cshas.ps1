# Compile and run one CSHAS node, or the distributed process demo, ignoring tests.
$mainClass = 'pcd.shas.Main'
$execArguments = $args
if ($args.Count -gt 0 -and $args[0] -eq 'demo') {
    $mainClass = 'pcd.shas.DemoMain'
    $execArguments = if ($args.Count -gt 1) { $args[1..($args.Count - 1)] } else { @() }
}
$execArgs = ($execArguments -join ' ')
$mvnArgs = @(
    '-f'
    "$PSScriptRoot\pom.xml"
    'compile'
    'exec:java'
    "-Dexec.mainClass=$mainClass"
    '-DskipTests'
    "-Dexec.args=$execArgs"
)
& mvn @mvnArgs
