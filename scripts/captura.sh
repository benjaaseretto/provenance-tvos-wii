#!/bin/zsh
# Uso: captura.sh [teclas...]  → manda teclas al Apple TV y guarda una captura de pantalla reducida.
# Imprime la ruta de la captura.
source "${0:A:h}/config.sh"
if (( $# )); then
  $ATV keys "$@" >/dev/null 2>&1
  sleep 1.5
fi
D=~/.atv-snaps; mkdir -p $D
F=$D/tv-$(date +%H%M%S)-$RANDOM
xcrun devicectl device capture screenshot --device $ATV_UDID --destination $F.png >/dev/null 2>&1
sips -Z 1280 $F.png --out $F-s.png >/dev/null && rm $F.png
echo $F-s.png
