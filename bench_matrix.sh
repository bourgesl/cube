#!/bin/bash

source ./test-jdk9-cl.sh
#source ./test-openjdk7.sh

echo "CPU info:"
lscpu
cpupower frequency-info


echo "Java version:"
java -version

JAVA_OPTS="-Xms128m -Xmx128m"
#JAVA_OPTS="-verbose:gc -Xms256m -Xmx256m"


TH=4
SIZE=64

for SIZE in 32 64 128 256 512
do
    for TH in 1 2 4 8
    do
    echo "TH: $TH"
    echo "SIZE: $SIZE"
    sleep 1
    java $JAVA_OPTS -DthreadCount=$TH -DcubeSize=$SIZE -cp target/cubes-1.0.0.jar screen.Cubes
    done
done

echo "done"

