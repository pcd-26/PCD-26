# Usage: .\assignment-2\run-gui.ps1

Start-Process -FilePath mvn -ArgumentList @(
    '-Dmaven.repo.local=C:\Users\alexs\.m2\repository',
    '-f',
    'assignment-2/pom.xml',
    'test'
) -NoNewWindow -Wait

Start-Process -FilePath mvn -ArgumentList @(
    '-Dmaven.repo.local=C:\Users\alexs\.m2\repository',
    '-f',
    'assignment-2/pom.xml',
    'compile',
    'org.codehaus.mojo:exec-maven-plugin:3.6.3:java',
    '-Dexec.mainClass=pcd.assignment2.gui.FSStatGUI'
) -NoNewWindow -Wait
