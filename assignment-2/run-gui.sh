#!/bin/bash
# Usage: ./assignment-2/run-gui.sh

mvn -f assignment-2/pom.xml compile exec:java -Dexec.mainClass="pcd.assignment2.gui.FSStatGUI"
