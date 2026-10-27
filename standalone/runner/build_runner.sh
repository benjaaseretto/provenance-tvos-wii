#!/bin/zsh
# Compila libstandalone.dylib para tvOS (arm64).
set -euo pipefail
cd "${0:A:h}"
clang -target arm64-apple-tvos17.0 -isysroot "$(xcrun --sdk appletvos --show-sdk-path)" \
  -dynamiclib -fobjc-arc -framework Foundation -framework UIKit \
  -install_name @executable_path/Frameworks/libstandalone.dylib \
  -o libstandalone.dylib standalone_runner.m
echo "libstandalone.dylib compilada con éxito para tvOS (arm64)"
