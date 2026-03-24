#!/bin/bash

cd "$(dirname "$0")"

echo "Starting DXO One MJPEG Server (x86_64)..."
echo ""

# Execute the JAR file specifically for the JVM for the Intel Apple Silicon
/Library/Java/JavaVirtualMachines/tu-jdk-intel-21.jdk/Contents/Home/bin/java -jar dxo-one-webcam-server-1.0.1.jar
