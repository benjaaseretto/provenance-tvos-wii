#!/usr/bin/env python3
"""empaquetar.py

Empaquetador de Juegos Standalone Nativos para Apple TV (tvOS).
Inspirado en el concepto de nxnative: toma una imagen de disco de Wii (.iso, .wbfs, .rvz)
y genera un IPA independiente y nativo para tvOS con su propio ícono 3D Parallax,
banner Top Shelf panorámico y arranque directo en Metal a 60 fps sin interfaz de emulador.
"""

import json
import os
import plistlib
import re
import shutil
import subprocess
import sys
import urllib.request
from pathlib import Path

from PIL import Image, ImageFilter, ImageEnhance

AQUI = Path(__file__).resolve().parent
RAIZ = AQUI.parent
PORTADAS = RAIZ / "build_standalone" / "portadas"
MARCA = "App Icon & Top Shelf Image.brandassets"
INFO_JSON = {"info": {"author": "xcode", "version": 1}}


def log(msg: str) -> None:
    print(f"[Standalone] {msg}", flush=True)


# --- 1. Generación de Arte tvOS 10-foot (Parallax 3D & Top Shelf) ---

def descargar_portada(game_id: str) -> Image.Image:
    PORTADAS.mkdir(exist_ok=True, parents=True)
    archivo = PORTADAS / f"{game_id}-coverfull.png"
    if not archivo.exists():
        url = f"https://art.gametdb.com/wii/coverfull/US/{game_id}.png"
        log(f"Descargando carátula de GameTDB: {url}")
        try:
            urllib.request.urlretrieve(url, archivo)
        except Exception as e:
            log(f"Advertencia: no se pudo descargar de US ({e}), intentando región EN...")
            url_en = f"https://art.gametdb.com/wii/coverfull/EN/{game_id}.png"
            urllib.request.urlretrieve(url_en, archivo)

    full = Image.open(archivo).convert("RGBA")
    w, h = full.size
    # El 52.8% derecho de coverfull contiene la portada frontal
    return full.crop((int(w * 0.528), 0, w, h))


def render_fondo(frente: Image.Image, size: tuple[int, int]) -> Image.Image:
    w, h = size
    escala = max(w / frente.width, h / frente.height)
    img = frente.resize((int(frente.width * escala) + 1, int(frente.height * escala) + 1), Image.LANCZOS)
    x, y = (img.width - w) // 2, (img.height - h) // 2
    img = img.crop((x, y, x + w, y + h)).filter(ImageFilter.GaussianBlur(w / 35))
    return ImageEnhance.Brightness(img).enhance(0.55).convert("RGB")


def render_frente(frente: Image.Image, size: tuple[int, int], alto_rel=0.92) -> Image.Image:
    w, h = size
    ch = int(h * alto_rel)
    cw = int(frente.width * ch / frente.height)
    lienzo = Image.new("RGBA", size, (0, 0, 0, 0))
    caratula_resized = frente.resize((cw, ch), Image.LANCZOS)
    lienzo.paste(caratula_resized, ((w - cw) // 2, (h - ch) // 2))
    return lienzo


def escribir_json(carpeta: Path, data: dict) -> None:
    carpeta.mkdir(parents=True, exist_ok=True)
    (carpeta / "Contents.json").write_text(json.dumps(data, indent=2))


def crear_imageset(carpeta: Path, imagenes: dict, idiom="tv") -> None:
    carpeta.mkdir(parents=True, exist_ok=True)
    lista = []
    for escala, img in imagenes.items():
        nombre = f"img@{escala}.png"
        img.save(carpeta / nombre)
        lista.append({"idiom": idiom, "scale": escala, "filename": nombre})
    escribir_json(carpeta, {"images": lista, **INFO_JSON})


def crear_imagestack(carpeta: Path, frente: Image.Image, base: tuple[int, int], escalas: tuple[int, ...]) -> None:
    capas = {
        "Front": lambda s: render_frente(frente, s),
        "Back": lambda s: render_fondo(frente, s),
    }
    for capa, render in capas.items():
        dir_capa = carpeta / f"{capa}.imagestacklayer"
        escribir_json(dir_capa, INFO_JSON)
        crear_imageset(dir_capa / "Content.imageset",
                       {f"{e}x": render((base[0] * e, base[1] * e)) for e in escalas})
    escribir_json(carpeta, {"layers": [{"filename": f"{c}.imagestacklayer"} for c in capas], **INFO_JSON})


def generar_assets_tvos(game_id: str, salida_assets: Path) -> None:
    frente = descargar_portada(game_id)
    marca = salida_assets / MARCA
    escribir_json(salida_assets, INFO_JSON)

    crear_imagestack(marca / "App Icon.imagestack", frente, (400, 240), (1, 2))
    crear_imagestack(marca / "App Icon - App Store.imagestack", frente, (1280, 768), (1,))
    crear_imageset(marca / "Top Shelf Image.imageset",
                   {"1x": render_fondo(frente, (1920, 720)), "2x": render_fondo(frente, (3840, 1440))})
    crear_imageset(marca / "Top Shelf Image Wide.imageset",
                   {"1x": render_fondo(frente, (2320, 720)), "2x": render_fondo(frente, (4640, 1440))})

    escribir_json(marca, {
        "assets": [
            {"filename": "App Icon - App Store.imagestack", "idiom": "tv", "role": "primary-app-icon", "size": "1280x768"},
            {"filename": "App Icon.imagestack", "idiom": "tv", "role": "primary-app-icon", "size": "400x240"},
            {"filename": "Top Shelf Image Wide.imageset", "idiom": "tv", "role": "top-shelf-image-wide", "size": "2320x720"},
            {"filename": "Top Shelf Image.imageset", "idiom": "tv", "role": "top-shelf-image", "size": "1920x720"},
        ],
        **INFO_JSON,
    })
    log(f"Assets de tvOS generados con éxito en {marca.name}")


# --- 2. Compilación de Assets con actool ---

def compilar_assets(xcassets_dir: Path, output_app_dir: Path) -> None:
    sdk_path = subprocess.check_output(["xcrun", "--sdk", "appletvos", "--show-sdk-path"], text=True).strip()
    cmd = [
        "xcrun", "actool", str(xcassets_dir),
        "--compile", str(output_app_dir),
        "--platform", "appletvos",
        "--target-device", "tv",
        "--minimum-deployment-target", "17.0",
        "--app-icon", "App Icon & Top Shelf Image",
        "--output-partial-info-plist", str(output_app_dir / "Assets-Info.plist"),
    ]
    subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL)
    log("Assets.car compilado para tvOS con éxito")


# --- 3. Empaquetado del Runner Standalone ---

def empaquetar_juego(
    target: str,
    nombre: str,
    game_id: str,
    md5: str,
    rom_path: Path | None,
    base_app: Path,
    salida_dir: Path,
    bundle_prefix: str = "com.tunombre",
) -> Path:
    bundle_id = f"{bundle_prefix}.wii.{target.lower()}"
    app_dir = salida_dir / f"{target}.app"
    log(f"Preparando bundle standalone para '{nombre}' ({bundle_id})...")

    if app_dir.exists():
        shutil.rmtree(app_dir)
    shutil.copytree(base_app, app_dir)

    # Limpiar atributos, extensiones y firmas viejas
    shutil.rmtree(app_dir / "PlugIns", ignore_errors=True)
    shutil.rmtree(app_dir / "_CodeSignature", ignore_errors=True)
    subprocess.run(["find", str(app_dir), "-name", "._*", "-delete"], check=False)
    subprocess.run(["find", str(app_dir), "-exec", "xattr", "-d", "com.apple.FinderInfo", "{}", "+"], check=False, stderr=subprocess.DEVNULL)
    subprocess.run(["xattr", "-crs", str(app_dir)], check=False, stderr=subprocess.DEVNULL)

    # 1. Modificar Info.plist
    plist_path = app_dir / "Info.plist"
    plist = plistlib.loads(plist_path.read_bytes())
    plist["CFBundleIdentifier"] = bundle_id
    plist["CFBundleDisplayName"] = nombre
    plist["CFBundleName"] = target
    plist["LSApplicationCategoryType"] = "public.app-category.action-games"
    plist["GCSupportsControllerUserInteraction"] = True
    plist["GCSupportedGameControllers"] = [{"ProfileName": "ExtendedGamepad"}]
    plist["PVGameMD5"] = md5
    plist["PVStandaloneGameID"] = game_id
    plist_path.write_bytes(plistlib.dumps(plist))

    # 2. Generar metadatos game.json
    game_info = {
        "target": target,
        "nombre": nombre,
        "game_id": game_id,
        "md5": md5,
        "system": "com.provenance.wii"
    }
    (app_dir / "game.json").write_text(json.dumps(game_info, indent=2))

    # 3. Compilar e inyectar libstandalone.dylib
    runner_script = AQUI / "runner" / "build_runner.sh"
    subprocess.run([str(runner_script)], check=True)
    dylib_fuente = AQUI / "runner" / "libstandalone.dylib"
    frameworks_dir = app_dir / "Frameworks"
    frameworks_dir.mkdir(exist_ok=True)
    shutil.copy2(dylib_fuente, frameworks_dir / "libstandalone.dylib")

    ejecutable = app_dir / plist.get("CFBundleExecutable", "Provenance")
    add_dylib_script = RAIZ / "patch" / "add_dylib.py"
    subprocess.run([
        sys.executable, str(add_dylib_script),
        str(ejecutable), "@executable_path/Frameworks/libstandalone.dylib"
    ], check=True)

    # 4. Inyectar mapeo de control remoto en Dolphin
    patch_remoto = RAIZ / "patch" / "control_remoto.py"
    if patch_remoto.exists():
        subprocess.run([sys.executable, str(patch_remoto), str(app_dir)], check=True)

    # 5. Generar ícono Parallax 3D y Top Shelf
    temp_assets = salida_dir / f"Assets_{target}.xcassets"
    generar_assets_tvos(game_id, temp_assets)
    compilar_assets(temp_assets, app_dir)
    shutil.rmtree(temp_assets, ignore_errors=True)

    # 6. Copiar o enlazar ROM si se especificó
    if rom_path and rom_path.exists():
        roms_dir = app_dir / "roms"
        roms_dir.mkdir(exist_ok=True)
        destino_rom = roms_dir / f"game{rom_path.suffix.lower()}"
        log(f"Copiando ROM ({rom_path.stat().st_size / 1e9:.2f} GB) a {destino_rom}...")
        try:
            os.link(rom_path, destino_rom) # hardlink instantáneo si están en el mismo disco
            log("Hardlink instantáneo creado para la ROM.")
        except OSError:
            shutil.copy2(rom_path, destino_rom)
            log("ROM copiada.")

    log(f"App standalone lista: {app_dir}")
    return app_dir


if __name__ == "__main__":
    if len(sys.argv) < 3:
        print("Uso: empaquetar.py <target> <game_id> [ruta_a_rom] [base_app]")
        sys.exit(1)

    target_arg = sys.argv[1]
    game_id_arg = sys.argv[2]
    rom_arg = Path(sys.argv[3]) if len(sys.argv) > 3 else None
    local_template = RAIZ / "build_standalone" / "template" / "Payload" / "Provenance.app"
    default_base = local_template if local_template.exists() else Path.home() / ".atv-work" / "Payload" / "Provenance.app"
    base_app_arg = Path(sys.argv[4]) if len(sys.argv) > 4 else default_base

    cfg_file = RAIZ / "config.env"
    prefijo = "com.tuusuario"
    if cfg_file.exists():
        for line in cfg_file.read_text().splitlines():
            if line.startswith("BUNDLE_PREFIX="):
                prefijo = line.split("=", 1)[1].strip()
    prefijo = os.environ.get("BUNDLE_PREFIX", prefijo)

    out_dir = RAIZ / "build_standalone"
    out_dir.mkdir(exist_ok=True)
    empaquetar_juego(
        target=target_arg,
        nombre=f"[Wii] {target_arg}",
        game_id=game_id_arg,
        md5="STANDALONE",
        rom_path=rom_arg,
        base_app=base_app_arg,
        salida_dir=out_dir,
        bundle_prefix=prefijo,
    )
