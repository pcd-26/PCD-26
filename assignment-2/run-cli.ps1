# Usage: .\assignment-2\run-cli.ps1 [directory] [maxFS] [nb] [sizeUnit: B|KiB|MiB|GiB] [paradigm: vt|rx|loop]
# Example: .\assignment-2\run-cli.ps1 . 10 5 MiB vt

$argsLine = if ($args.Count -gt 0) {
    $args -join ' '
} else {
    '. 10 5 MiB vt'
}

Start-Process -FilePath mvn -ArgumentList @(
    '-Dmaven.repo.local=C:\Users\alexs\.m2\repository',
    '-f',
    'assignment-2/pom.xml',
    'clean',
    'compile',
    'org.codehaus.mojo:exec-maven-plugin:3.6.3:java',
    '-Dexec.mainClass=pcd.assignment2.cli.FSStatCLI',
    ('-Dexec.args="' + $argsLine + '"')
) -NoNewWindow -Wait
