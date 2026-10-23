#!/bin/zsh
# Renueva la firma gratis (7 días) de Provenance y los accesos cuando le quedan menos de 3 días.
# La corre launchd todos los días (ver instalar-renovacion.sh); a mano: renovar.sh --forzar
set -uo pipefail
source "${0:A:h}/config.sh"
cd $RAIZ
PROFILES=~/Library/Developer/Xcode/UserData/Provisioning\ Profiles
exec >> ~/.atv-renovar.log 2>&1
echo "=== $(date)"

if [[ "${1:-}" != "--forzar" && -f perfil.mobileprovision ]]; then
  vence=$(security cms -D -i perfil.mobileprovision | plutil -extract ExpirationDate raw -)
  restante=$(( $(date -j -f "%Y-%m-%dT%H:%M:%SZ" "$vence" +%s) - $(date +%s) ))
  if (( restante > 3 * 86400 )); then
    echo "vence $vence, todavía no hace falta"
    exit 0
  fi
fi

# Compilar una app vacía con el mismo bundle ID hace que Xcode emita un perfil nuevo.
rm -f $PROFILES/*.mobileprovision
(cd stub && xcodegen generate -q && xcodebuild -project Stub.xcodeproj -scheme Stub -configuration Debug \
  -destination "id=$ATV_UDID" -allowProvisioningUpdates -allowProvisioningDeviceRegistration \
  DEVELOPMENT_TEAM=$TEAM_ID PRODUCT_BUNDLE_IDENTIFIER=$PROVENANCE_BUNDLE build > /dev/null)
nuevo=$(ls -t $PROFILES/*.mobileprovision 2>/dev/null | head -1)
if [[ -z "$nuevo" ]]; then
  echo "no se pudo obtener perfil nuevo"
  exit 1
fi
cp "$nuevo" perfil.mobileprovision
scripts/resign.sh perfil.mobileprovision | grep -v "replacing existing signature"
accesos/instalar.sh
echo "renovado"
