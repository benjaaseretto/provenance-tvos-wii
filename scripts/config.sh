#!/bin/zsh
# Carga config.env desde la raíz del repo. Se usa con: source "$(dirname "$0")/config.sh"
RAIZ=${0:A:h:h}
[[ -f $RAIZ/config.env ]] || { echo "falta $RAIZ/config.env (copiá config.example.env)" >&2; exit 1; }
set -a
source $RAIZ/config.env
set +a
for v in ATV_UDID ATV_IP TEAM_ID BUNDLE_PREFIX PROVENANCE_IPA; do
  [[ -n ${(P)v} ]] || { echo "config.env: falta $v" >&2; exit 1; }
done
PROVENANCE_BUNDLE=$BUNDLE_PREFIX.provenance
ATV=(${ATV_PYTHON:-python3} $RAIZ/tools/atv.py)
