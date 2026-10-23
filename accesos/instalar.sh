#!/bin/zsh
# Genera, compila, firma e instala un ícono por juego de juegos.json en el Apple TV.
set -euo pipefail
source "${0:A:h:h}/scripts/config.sh"
cd "${0:A:h}"
python3 generar.py
xcodegen generate -q
for t in $(python3 -c "import json; print(' '.join(j['target'] for j in json.load(open('../juegos.json')) if j['md5']))"); do
  xcodebuild -project Accesos.xcodeproj -scheme $t -configuration Release \
    -destination 'generic/platform=tvOS' -allowProvisioningUpdates \
    -derivedDataPath build build > /dev/null
  xcrun devicectl device install app --device $ATV_UDID \
    build/Build/Products/Release-appletvos/$t.app > /dev/null
  echo "acceso $t instalado"
done
