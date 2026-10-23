#!/bin/zsh
# Sin argumentos: si el Apple TV se durmió, lo despierta y abre Provenance (sin cerrar un juego en curso).
# Con --subir: además garantiza el servidor web de subida, reiniciando Provenance si hace falta.
source "${0:A:h}/config.sh"
web() { curl -s -m 4 -o /dev/null -w '%{http_code}' http://$ATV_IP/; }
corriendo() { xcrun devicectl device info processes --device $ATV_UDID 2>/dev/null | grep -q Provenance.app/Provenance; }

if [[ "${1:-}" == "--subir" ]]; then
  if [[ "$(web)" != 200 ]]; then
    $ATV keys home >/dev/null 2>&1
    sleep 3
    xcrun devicectl device process launch --terminate-existing --device $ATV_UDID $PROVENANCE_BUNDLE >/dev/null 2>&1
    for i in {1..8}; do sleep 3; [[ "$(web)" == 200 ]] && break; done
  fi
elif ! corriendo; then
  $ATV keys home >/dev/null 2>&1
  sleep 3
  xcrun devicectl device process launch --device $ATV_UDID $PROVENANCE_BUNDLE >/dev/null 2>&1
fi
echo "web $(web)"
