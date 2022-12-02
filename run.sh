#!/bin/bash

source ~/test-jdk-gh.sh

#echo "CPU info:"
#lscpu
cpupower frequency-info


echo "Java version:"
java -version

#JAVA_OPTS="-Xms128m -Xmx128m"
JAVA_OPTS="-verbose:gc -Xms256m -Xmx256m"


TH=4
SIZE=256 # 4K screen

echo "TH: $TH"
echo "SIZE: $SIZE"
sleep 1
java $JAVA_OPTS -DthreadCount=$TH -DcubeSize=$SIZE -cp target/cubes-1.0.0.jar screen.Cubes

echo "done"

