#!/bin/zsh
# limpiar_emulador.sh
# Espera a que termine el respaldo local de ROMs y elimina las ROMs y el emulador del Apple TV.
set -euo pipefail

RAIZ=${0:A:h:h}
source "$RAIZ/scripts/config.sh"

echo "Esperando que finalice la copia de seguridad de las ROMs hacia la Mac..."
while pgrep -f "DeviceFS.*AppDataContainers" > /dev/null; do
  sleep 2
done

PROVENANCE_CONTAINER="$(find "$HOME/Library/Developer/CoreDevice/DeviceFS" -maxdepth 3 -name "$BUNDLE_PREFIX.provenance" 2>/dev/null | head -n 1 || true)"

if [[ -d "$PROVENANCE_CONTAINER/Library/Caches/ROMs" ]]; then
  echo "Eliminando ROMs almacenadas dentro del contenedor del emulador en el Apple TV..."
  rm -rf "$PROVENANCE_CONTAINER/Library/Caches/ROMs/"*
  echo "ROMs eliminadas del emulador."
fi

if [[ -d "$PROVENANCE_CONTAINER/Library/Caches/Save States" ]]; then
  echo "Limpiando estados guardados del emulador..."
  rm -rf "$PROVENANCE_CONTAINER/Library/Caches/Save States/"*
fi

echo "Desinstalando app del emulador (Provenance) del Apple TV..."
xcrun devicectl device uninstall app --device "$ATV_UDID" "$BUNDLE_PREFIX.provenance" 2>/dev/null || true
echo "Emulador y sus juegos eliminados exitosamente del Apple TV."
