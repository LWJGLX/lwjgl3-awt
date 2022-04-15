#!/bin/bash
if [[ $# -eq 0 ]]; then
    echo "No arguments supplied. Use as ./macos-create-native.sh [path-to-jdk-home]"
    exit 1
fi

JDK_HOME_PATH=$1

echo "Compiling arm64 binary..."
gcc -arch arm64 -dynamiclib lwjgl3awt/*.m -o native/macosx/liblwjgl3awt-arm64.dylib -framework CoreFoundation -framework AppKit -framework MetalKit -framework Metal -I${JDK_HOME_PATH}/include/darwin/ -I${JDK_HOME_PATH}/include/

echo "Compiling x86_64 binary..."
gcc -arch x86_64 -dynamiclib lwjgl3awt/*.m -o native/macosx/liblwjgl3awt-x86_64.dylib -framework CoreFoundation -framework AppKit -framework MetalKit -framework Metal -I${JDK_HOME_PATH}/include/darwin/ -I${JDK_HOME_PATH}/include/

echo "Creating universal binary..."
lipo -create native/macosx/liblwjgl3awt-arm64.dylib native/macosx/liblwjgl3awt-x86_64.dylib -output native/macosx/liblwjgl3awt.dylib

rm native/macosx/liblwjgl3awt-arm64.dylib
rm native/macosx/liblwjgl3awt-x86_64.dylib
