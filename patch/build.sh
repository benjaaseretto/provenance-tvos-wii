#!/bin/zsh
# Compila libckstub.dylib para tvOS (arm64).
set -euo pipefail
cd "${0:A:h}"
clang -target arm64-apple-tvos17.0 -isysroot "$(xcrun --sdk appletvos --show-sdk-path)" \
  -dynamiclib -fobjc-arc -framework Foundation \
  -install_name @executable_path/Frameworks/libckstub.dylib -o libckstub.dylib ckstub.m
