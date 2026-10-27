#!/bin/zsh
# renovar_standalone.sh
# Renueva la firma gratis de los juegos standalone en el Apple TV cuando restan menos de 3 días.
set -uo pipefail

RAIZ=${0:A:h:h}
source "$RAIZ/scripts/config.sh"
cd "$RAIZ"

PROFILES=~/Library/Developer/Xcode/UserData/Provisioning\ Profiles
exec >> ~/.atv-renovar-standalone.log 2>&1
echo "=== $(date)"

PROFILE="$RAIZ/perfil.mobileprovision"
if [[ "${1:-}" != "--forzar" && -f "$PROFILE" ]]; then
  vence=$(security cms -D -i "$PROFILE" | plutil -extract ExpirationDate raw -)
  restante=$(( $(date -j -f "%Y-%m-%dT%H:%M:%SZ" "$vence" +%s) - $(date +%s) ))
  if (( restante > 3 * 86400 )); then
    echo "Perfil vence en $vence, todavía no hace falta renovar."
    exit 0
  fi
fi

echo "Renovando perfil con Xcode..."
rm -f $PROFILES/*.mobileprovision
(cd "$RAIZ/stub" && xcodegen generate -q && xcodebuild -project Stub.xcodeproj -scheme Stub -configuration Debug \
  -destination "id=$ATV_UDID" -allowProvisioningUpdates -allowProvisioningDeviceRegistration \
  DEVELOPMENT_TEAM=$TEAM_ID PRODUCT_BUNDLE_IDENTIFIER=$BUNDLE_PREFIX.standalone.stub build > /dev/null)

nuevo=$(ls -t $PROFILES/*.mobileprovision 2>/dev/null | head -1)
if [[ -z "$nuevo" ]]; then
  echo "Error: no se pudo obtener perfil nuevo desde Xcode."
  exit 1
fi

cp "$nuevo" "$PROFILE"
echo "Nuevo perfil guardado. Re-firmando e instalando juegos..."
"$RAIZ/standalone/instalar_standalone.sh"
echo "Renovación completa."
