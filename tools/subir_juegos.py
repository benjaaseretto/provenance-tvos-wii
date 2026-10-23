#!/usr/bin/env python3
"""Espera a que Chrome termine las descargas, revisa cada archivo y sube los juegos a Provenance.

Solo acepta .zip/.7z cuyo contenido sea imágenes de disco (.iso .wbfs .rvz .gcm .ciso .wia).
Cualquier otra cosa (ejecutables, .app, .pkg, scripts) se reporta y no se toca.
"""
import hashlib
import json
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

from config import RAIZ, CONFIG

DESCARGAS = Path.home() / "Downloads"
TRABAJO = Path.home() / ".atv-juegos"
SERVIDOR = f"http://{CONFIG['ATV_IP']}"
IMAGENES = {".iso", ".wbfs", ".rvz", ".gcm", ".ciso", ".wia"}
INOFENSIVOS = {".txt", ".nfo", ".jpg", ".png", ".url", ".sfv"}
LOG = TRABAJO / "subida.log"
MANTENER = RAIZ / "scripts" / "mantener.sh"
INSTALAR = RAIZ / "accesos" / "instalar.sh"
JUEGOS = RAIZ / "juegos.json"
# El Provenance parcheado tiene las extensiones de disco solo para Wii, así que /Imports/ las asigna solo.
DESTINO = "/Imports/"


def log(msg: str) -> None:
    line = f"{time.strftime('%H:%M:%S')} {msg}"
    print(line, flush=True)
    with LOG.open("a") as f:
        f.write(line + "\n")


def contenido(archivo: Path) -> list[str]:
    out = subprocess.run(["bsdtar", "-tf", str(archivo)], capture_output=True, text=True, check=True)
    return [n for n in out.stdout.splitlines() if n and not n.endswith("/")]


def revisar(archivo: Path) -> list[str] | None:
    nombres = contenido(archivo)
    imagenes = [n for n in nombres if Path(n).suffix.lower() in IMAGENES]
    raros = [n for n in nombres if Path(n).suffix.lower() not in IMAGENES | INOFENSIVOS]
    if raros or not imagenes:
        log(f"RECHAZADO {archivo.name}: imágenes={imagenes} sospechosos={raros}")
        return None
    return imagenes


def anotar_md5(imagen: Path) -> str:
    h = hashlib.md5()
    with imagen.open("rb") as f:
        while bloque := f.read(8 << 20):
            h.update(bloque)
    md5 = h.hexdigest().upper()
    with (TRABAJO / "md5.txt").open("a") as f:
        f.write(f"{md5}\t{imagen.name}\n")
    log(f"md5 {md5} {imagen.name}")
    return md5


def conectar_icono(nombre: str, md5: str) -> None:
    """Si el juego figura en juegos.json, le carga el md5 y reinstala su ícono en el Apple TV."""
    if not JUEGOS.exists():
        return
    juegos = json.loads(JUEGOS.read_text())
    juego = next((j for j in juegos if j["clave"] in nombre.lower()), None)
    if not juego:
        return
    juego["md5"] = md5
    JUEGOS.write_text(json.dumps(juegos, indent=2, ensure_ascii=False) + "\n")
    r = subprocess.run([str(INSTALAR)], capture_output=True, text=True)
    log(f"ícono {juego['target']} {'conectado' if r.returncode == 0 else 'FALLÓ: ' + r.stderr[-300:]}")


def subir(imagen: Path) -> bool:
    md5 = anotar_md5(imagen)
    # curl interpreta comas y comillas en "@archivo": subimos con nombre limpio y ruta relativa
    limpio = imagen.with_name(re.sub(r"[^\w().\- ]", "", imagen.name))
    imagen.rename(limpio)
    for intento in range(1, 4):
        subprocess.run([str(MANTENER), "--subir"], capture_output=True)
        log(f"subiendo {limpio.name} ({limpio.stat().st_size / 1e9:.1f} GB), intento {intento}")
        r = subprocess.run(
            ["curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", "-F", f"path={DESTINO}",
             "-F", f"files[]=@{limpio.name}", f"{SERVIDOR}/upload"],
            capture_output=True, text=True, cwd=limpio.parent,
        )
        if r.stdout.strip() == "200":
            log(f"OK {limpio.name}")
            conectar_icono(limpio.name, md5)
            return True
        log(f"FALLÓ ({r.stdout}) {limpio.name}")
        time.sleep(30)
    return False


def procesar(archivo: Path) -> None:
    imagenes = revisar(archivo)
    if not imagenes:
        return
    destino = TRABAJO / archivo.stem
    destino.mkdir(parents=True, exist_ok=True)
    subprocess.run(["bsdtar", "-xf", str(archivo), "-C", str(destino), *imagenes], check=True)
    if all(subir(destino / nombre) for nombre in imagenes):
        shutil.rmtree(destino, ignore_errors=True)
    else:
        log(f"quedó sin subir en {destino}")


def main() -> None:
    TRABAJO.mkdir(exist_ok=True)
    registro = TRABAJO / "hechos.txt"
    hechos = set(registro.read_text().splitlines()) if registro.exists() else set()
    log("esperando descargas…")
    while True:
        pendientes = list(DESCARGAS.glob("*.crdownload"))
        for archivo in sorted(DESCARGAS.glob("*")):
            if archivo.suffix.lower() in {".zip", ".7z"} and archivo.name not in hechos \
                    and archivo.stat().st_mtime > time.time() - 6 * 3600:
                hechos.add(archivo.name)
                with registro.open("a") as f:
                    f.write(archivo.name + "\n")
                try:
                    procesar(archivo)
                except subprocess.CalledProcessError as e:
                    log(f"error con {archivo.name}: {e}")
        if not pendientes and "--una-vez" not in sys.argv:
            log("no quedan descargas; listo")
            return
        if "--una-vez" in sys.argv:
            return
        time.sleep(60)


if __name__ == "__main__":
    main()
