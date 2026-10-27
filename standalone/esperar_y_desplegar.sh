#!/bin/zsh
# esperar_y_desplegar.sh
# Espera a que Xcode renueve el perfil de aprovisionamiento y luego ejecuta
# automáticamente todo el flujo: empaquetado, firma, instalación y eliminación de Provenance.
set -euo pipefail

RAIZ=${0:A:h:h}
source "$RAIZ/scripts/config.sh"
cd "$RAIZ"

echo "=========================================================="
echo "  Despliegue Automático de Juegos Standalone Nativo tvOS   "
echo "=========================================================="
echo "Apple TV: $ATV_UDID ($ATV_IP)"
echo "Team ID:  $TEAM_ID"
echo "Prefix:   $BUNDLE_PREFIX"
echo ""

PROFILES_DIR="$HOME/Library/Developer/Xcode/UserData/Provisioning Profiles"

buscar_perfil() {
  local p
  for p in "$PROFILES_DIR"/*.mobileprovision(N.om); do
    echo "$p"
    return 0
  done
  return 1
}

NUEVO=$(buscar_perfil || true)
if [[ -z "$NUEVO" ]]; then
  echo "Esperando a que Xcode obtenga el perfil de aprovisionamiento..."
  echo "Por favor completá el inicio de sesión en Xcode (Xcode > Settings > Accounts)."
  
  INTENTO=1
  while true; do
    echo -n "[$INTENTO] Intentando generar perfil vía xcodebuild... "
    if xcodebuild -project stub/Stub.xcodeproj -scheme Stub -configuration Debug \
      -destination "id=$ATV_UDID" -allowProvisioningUpdates -allowProvisioningDeviceRegistration \
      DEVELOPMENT_TEAM="$TEAM_ID" PRODUCT_BUNDLE_IDENTIFIER="$PROVENANCE_BUNDLE" build > /tmp/xcodebuild_deploy.log 2>&1; then
      echo "¡Éxito! Perfil obtenido por xcodebuild."
      NUEVO=$(buscar_perfil || true)
      break
    fi

    # Comprobar si se descargó directamente
    NUEVO=$(buscar_perfil || true)
    if [[ -n "$NUEVO" ]]; then
      echo "¡Nuevo perfil detectado en Xcode!"
      break
    fi

    echo "Aún no disponible. Reintentando en 4 segundos..."
    sleep 4
    INTENTO=$((INTENTO + 1))
  done
fi

echo "Copiando perfil activo a $RAIZ/perfil.mobileprovision..."
cp "$NUEVO" "$RAIZ/perfil.mobileprovision"

# Esperar a que termine la copia de ROMs si hay una en curso
while pgrep -f "DeviceFS.*AppDataContainers" > /dev/null; do
  echo "Copia de ROMs desde el Apple TV en progreso... esperando unos segundos..."
  sleep 3
done

echo "Ejecutando empaquetado, firma e instalación de todos los juegos..."
"$RAIZ/standalone/instalar_standalone.sh"

echo "Verificando aplicaciones instaladas en el Apple TV:"
xcrun devicectl device info apps --device "$ATV_UDID"

echo ""
echo "=========================================================="
echo "  ¡TODO COMPLETADO! Juegos nativos instalados y listos.   "
echo "=========================================================="
