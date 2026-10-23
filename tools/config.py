"""Lee config.env de la raíz del repo (mismo formato que usan los scripts de zsh)."""
from pathlib import Path

RAIZ = Path(__file__).resolve().parent.parent


def _leer() -> dict[str, str]:
    archivo = RAIZ / "config.env"
    if not archivo.exists():
        raise SystemExit(f"falta {archivo} (copiá config.example.env)")
    valores = {}
    for linea in archivo.read_text().splitlines():
        linea = linea.strip()
        if linea and not linea.startswith("#") and "=" in linea:
            clave, valor = linea.split("=", 1)
            valores[clave.strip()] = valor.strip().strip('"')
    return valores


CONFIG = _leer()
