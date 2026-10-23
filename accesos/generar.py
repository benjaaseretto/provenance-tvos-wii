#!/usr/bin/env python3
"""Genera accesos/project.yml y el ícono tvOS de cada juego de juegos.json (portada de GameTDB).

Cada juego queda como una app propia en el menú del Apple TV que abre Provenance directo en ese juego.
"""
import json
import os
import urllib.request
from pathlib import Path

from PIL import Image, ImageFilter, ImageEnhance

AQUI = Path(__file__).resolve().parent
JUEGOS = json.loads((AQUI.parent / "juegos.json").read_text())
PORTADAS = Path.home() / ".atv-portadas"
MARCA = "App Icon & Top Shelf Image.brandassets"


def portada(game_id: str) -> Image.Image:
    archivo = PORTADAS / f"{game_id}-coverfull.png"
    if not archivo.exists():
        PORTADAS.mkdir(exist_ok=True)
        urllib.request.urlretrieve(f"https://art.gametdb.com/wii/coverfull/US/{game_id}.png", archivo)
    full = Image.open(archivo).convert("RGBA")
    w, h = full.size
    return full.crop((int(w * 0.528), 0, w, h))


def fondo(frente: Image.Image, size) -> Image.Image:
    w, h = size
    escala = max(w / frente.width, h / frente.height)
    img = frente.resize((int(frente.width * escala) + 1, int(frente.height * escala) + 1), Image.LANCZOS)
    x, y = (img.width - w) // 2, (img.height - h) // 2
    img = img.crop((x, y, x + w, y + h)).filter(ImageFilter.GaussianBlur(w / 40))
    return ImageEnhance.Brightness(img).enhance(0.6).convert("RGB")


def caratula(frente: Image.Image, size, alto=0.92) -> Image.Image:
    w, h = size
    ch = int(h * alto)
    cw = int(frente.width * ch / frente.height)
    lienzo = Image.new("RGBA", size, (0, 0, 0, 0))
    lienzo.paste(frente.resize((cw, ch), Image.LANCZOS), ((w - cw) // 2, (h - ch) // 2))
    return lienzo


def escribir(carpeta: Path, contenido: dict) -> None:
    carpeta.mkdir(parents=True, exist_ok=True)
    (carpeta / "Contents.json").write_text(json.dumps(contenido, indent=2))


INFO = {"info": {"author": "xcode", "version": 1}}


def imageset(carpeta: Path, imagenes: dict, idiom="tv") -> None:
    carpeta.mkdir(parents=True, exist_ok=True)
    lista = []
    for escala, img in imagenes.items():
        nombre = f"img@{escala}.png"
        img.save(carpeta / nombre)
        lista.append({"idiom": idiom, "scale": escala, "filename": nombre})
    escribir(carpeta, {"images": lista, **INFO})


def imagestack(carpeta: Path, frente: Image.Image, base, escalas) -> None:
    capas = {
        "Front": lambda s: caratula(frente, s),
        "Back": lambda s: fondo(frente, s),
    }
    for capa, render in capas.items():
        dir_capa = carpeta / f"{capa}.imagestacklayer"
        escribir(dir_capa, INFO)
        imageset(dir_capa / "Content.imageset",
                 {f"{e}x": render((base[0] * e, base[1] * e)) for e in escalas})
    escribir(carpeta, {"layers": [{"filename": f"{c}.imagestacklayer"} for c in capas], **INFO})


def generar(target: str, game_id: str) -> None:
    frente = portada(game_id)
    xcassets = AQUI / "Iconos" / target / "Assets.xcassets"
    escribir(xcassets, INFO)
    marca = xcassets / MARCA
    imagestack(marca / "App Icon.imagestack", frente, (400, 240), (1, 2))
    imagestack(marca / "App Icon - App Store.imagestack", frente, (1280, 768), (1,))
    imageset(marca / "Top Shelf Image.imageset",
             {"1x": fondo(frente, (1920, 720)), "2x": fondo(frente, (3840, 1440))})
    imageset(marca / "Top Shelf Image Wide.imageset",
             {"1x": fondo(frente, (2320, 720)), "2x": fondo(frente, (4640, 1440))})
    escribir(marca, {
        "assets": [
            {"filename": "App Icon - App Store.imagestack", "idiom": "tv", "role": "primary-app-icon", "size": "1280x768"},
            {"filename": "App Icon.imagestack", "idiom": "tv", "role": "primary-app-icon", "size": "400x240"},
            {"filename": "Top Shelf Image Wide.imageset", "idiom": "tv", "role": "top-shelf-image-wide", "size": "2320x720"},
            {"filename": "Top Shelf Image.imageset", "idiom": "tv", "role": "top-shelf-image", "size": "1920x720"},
        ],
        **INFO,
    })
    print(f"{target}: ícono generado")


def proyecto() -> None:
    prefijo, equipo = os.environ["BUNDLE_PREFIX"], os.environ["TEAM_ID"]
    lineas = [
        "name: Accesos",
        "settings:",
        "  base:",
        f"    DEVELOPMENT_TEAM: {equipo}",
        "    CODE_SIGN_STYLE: Automatic",
        "    GENERATE_INFOPLIST_FILE: YES",
        '    MARKETING_VERSION: "1.0"',
        '    CURRENT_PROJECT_VERSION: "1"',
        '    TARGETED_DEVICE_FAMILY: "3"',
        '    ASSETCATALOG_COMPILER_APPICON_NAME: "App Icon & Top Shelf Image"',
        "    INFOPLIST_FILE: Acceso/Info.plist",
        "targets:",
    ]
    for j in JUEGOS:
        if not j["md5"]:
            continue
        lineas += [
            f"  {j['target']}:",
            "    type: application",
            "    platform: tvOS",
            '    deploymentTarget: "17.0"',
            "    sources:",
            "      - path: Acceso",
            "        excludes: [Info.plist]",
            f"      - path: Iconos/{j['target']}/Assets.xcassets",
            "    settings:",
            f"      PRODUCT_BUNDLE_IDENTIFIER: {prefijo}.wii.{j['target'].lower()}",
            f"      INFOPLIST_KEY_CFBundleDisplayName: {json.dumps(j['nombre'])}",
            f"      PV_GAME_MD5: {j['md5']}",
        ]
    (AQUI / "project.yml").write_text("\n".join(lineas) + "\n")


if __name__ == "__main__":
    for juego in JUEGOS:
        if juego["md5"]:
            generar(juego["target"], juego["game_id"])
    proyecto()
