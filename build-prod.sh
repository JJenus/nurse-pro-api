#!/bin/bash

mvn clean package -DskipTests -Dspring.profiles.active=prod -Dstyle.color=never
scp target/nursepro.jar nurseschedulerpro@ssh-nurseschedulerpro.alwaysdata.net: