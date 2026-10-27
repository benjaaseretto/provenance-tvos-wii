#!/bin/zsh
# instalar_standalone.sh
# Empaqueta, firma e instala los juegos como aplicaciones standalone nativas en el Apple TV.
set -euo pipefail

RAIZ=${0:A:h:h}
source "$RAIZ/scripts/config.sh"
cd "$RAIZ"

PROFILE="$RAIZ/perfil.mobileprovision"
if [[ ! -f "$PROFILE" ]]; then
  echo "No se encontró $PROFILE. Corriendo generación de perfil..."
  "$RAIZ/scripts/renovar.sh" --forzar || true
fi

if [[ ! -f "$PROFILE" ]]; then
  echo "Error: falta perfil.mobileprovision. Asegurate de tener Xcode con tu Apple ID." >&2
  exit 1
fi

security cms -D -i "$PROFILE" > /tmp/profile.plist
TEAM=$(/usr/libexec/PlistBuddy -c 'Print :TeamIdentifier:0' /tmp/profile.plist)
CERT_SHA=$(/usr/libexec/PlistBuddy -c 'Print :DeveloperCertificates:0' /tmp/profile.plist | openssl x509 -inform DER -noout -fingerprint -sha1 | cut -d= -f2 | tr -d :)

echo "=== Configuración de firma ==="
echo "Team ID: $TEAM"
echo "Certificado: $CERT_SHA"
echo "Apple TV UDID: $ATV_UDID"

# Desempaquetar plantilla base de Provenance si hace falta
BASE_APP="$RAIZ/build_standalone/template/Payload/Provenance.app"
if [[ ! -d "$BASE_APP" ]]; then
  WORK=~/.atv-work
  if [[ ! -d "$WORK/Payload/Provenance.app" ]]; then
    echo "Descomprimiendo plantilla base desde $PROVENANCE_IPA..."
    rm -rf "$WORK" && mkdir -p "$WORK"
    unzip -q "$PROVENANCE_IPA" -d "$WORK"
  fi
  BASE_APP="$WORK/Payload/Provenance.app"
fi

BUILD_DIR="$RAIZ/build_standalone"
mkdir -p "$BUILD_DIR"

JUEGOS_FILE="$RAIZ/juegos.json"
if [[ ! -f "$JUEGOS_FILE" ]]; then
  JUEGOS_FILE="$RAIZ/juegos.example.json"
fi

python3 -c "
import json
from pathlib import Path

juegos = json.loads(Path('$JUEGOS_FILE').read_text())
for j in juegos:
    print(f\"{j['target']}|{j['nombre']}|{j['game_id']}|{j.get('md5', '')}\")
" | while IFS='|' read -r TARGET NOMBRE GAME_ID MD5; do
  echo "=========================================================="
  echo "Empaquetando juego nativo: $NOMBRE ($TARGET)"
  echo "=========================================================="

  APP="$BUILD_DIR/$TARGET.app"
  
  # Buscar ROM en ~/.atv-work/roms, ~/Downloads o carpeta local
  ROM_FILE=$(find ~/.atv-work/roms ~/Downloads "$RAIZ" \( -iname "*${TARGET}*" -o -iname "*${GAME_ID}*" -o -iname "*Mario*Kart*" -o -iname "*WWE*12*" \) 2>/dev/null | grep -E '\.(iso|wbfs|rvz)$' | head -1 || true)
  if [[ -n "$ROM_FILE" ]]; then
    echo "ROM encontrada: $ROM_FILE"
  else
    echo "Aviso: No se encontró ROM local para $TARGET. Se usará ROM embebida si ya existía."
  fi
  
  python3 "$RAIZ/standalone/empaquetar.py" "$TARGET" "$GAME_ID" "${ROM_FILE:-}" "$BASE_APP"

  # Trabajar fuera de ~/Documents para evitar que iCloud agregue atributos extendidos
  WORK_APP="$WORK/standalone/$TARGET.app"
  mkdir -p "$WORK/standalone"
  rm -rf "$WORK_APP"
  cp -r "$APP" "$WORK_APP"

  # Limpiar atributos y extensiones
  rm -rf "$WORK_APP/PlugIns" "$WORK_APP/_CodeSignature"
  find "$WORK_APP" -name '._*' -delete
  find "$WORK_APP" -exec xattr -d com.apple.FinderInfo {} + 2>/dev/null || true

  # Firmar la app standalone
  echo "Firmando $WORK_APP con perfil de desarrollo..."
  cp "$PROFILE" "$WORK_APP/embedded.mobileprovision"
  
  APPID="$TEAM.$BUNDLE_PREFIX.wii.${(L)TARGET}"
  ENTITLEMENTS="$RAIZ/build_standalone/entitlements_${TARGET}.plist"
  cat > "$ENTITLEMENTS" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>application-identifier</key><string>$APPID</string>
  <key>com.apple.developer.team-identifier</key><string>$TEAM</string>
  <key>get-task-allow</key><true/>
  <key>keychain-access-groups</key><array><string>$APPID</string></array>
</dict></plist>
EOF

  find "$WORK_APP/Frameworks" -depth \( -name '*.framework' -o -name '*.dylib' \) -print0 |
    xargs -0 -n 20 codesign -f -s "$CERT_SHA" --timestamp=none

  codesign -f -s "$CERT_SHA" --timestamp=none --entitlements "$ENTITLEMENTS" "$WORK_APP"
  codesign -v --deep "$WORK_APP" && echo "Firma OK: $TARGET"

  echo "Instalando en Apple TV ($ATV_UDID)..."
  xcrun devicectl device install app --device "$ATV_UDID" "$WORK_APP"
  echo "Juego nativo $TARGET instalado con éxito en el Apple TV!"
done

echo "=========================================================="
echo "Todos los juegos standalone han sido instalados como apps nativas."
echo "Eliminando emulador anterior (Provenance) para dejar solo juegos nativos..."
xcrun devicectl device uninstall app --device "$ATV_UDID" "$BUNDLE_PREFIX.provenance" 2>/dev/null || true
echo "Listo: Solo los juegos nativos permanecen en el Apple TV."
