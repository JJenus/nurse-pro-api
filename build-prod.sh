#!/bin/bash

# Get the version from pom.xml
VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)

# Remove dots from version (e.g., 4.1.5 -> 415, 5.3.4 -> 534)

# Build the WAR file
mvn clean package -DskipTests -Dspring.profiles.active=prod

# Rename WAR file dynamically
mv target/nurse-pro-api-${VERSION}.jar target/nursepro.jar

# SCP to remote server (if ssh pass is set)
scp target/nursepro.jar nursepro@ssh-nursepro.alwaysdata.net:
