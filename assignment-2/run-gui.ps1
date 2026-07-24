# Usage: .\assignment-2\run-gui.ps1

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$pomPath = Join-Path $scriptDir 'pom.xml'

Start-Process -FilePath mvn -ArgumentList @(
    '-Dmaven.repo.local=C:\Users\alexs\.m2\repository',
    '-f',
    $pomPath,
    'compile',
    'org.codehaus.mojo:exec-maven-plugin:3.6.3:java',
    '-Dexec.mainClass=pcd.assignment2.gui.FSStatGUI'
) -NoNewWindow -Wait
