#!/usr/bin/env python3
"""Deja las extensiones de imagen de disco solo para Wii en el systems.plist de Provenance.

Sin esto, un .iso coincide con 11 consolas y Provenance pide elegir a mano en cada importación.
Uso: solo_wii.py <Provenance.app>
"""
import plistlib
import sys
from pathlib import Path

DISCO = {"iso", "wbfs", "rvz", "gcz", "ciso", "gcm", "wia"}
WII = "com.provenance.wii"

ruta = Path(sys.argv[1]) / "PVLibrary_PVLibrary.bundle" / "systems.plist"
sistemas = plistlib.loads(ruta.read_bytes())
tocados = []
for s in sistemas:
    if s.get("PVSystemIdentifier") == WII:
        continue
    exts = s.get("PVSupportedExtensions", [])
    nuevas = [e for e in exts if e.lower() not in DISCO]
    if len(nuevas) != len(exts):
        s["PVSupportedExtensions"] = nuevas
        tocados.append(s["PVSystemIdentifier"])
ruta.write_bytes(plistlib.dumps(sistemas))
print(f"extensiones de disco ahora solo para Wii (sacadas de {len(tocados)} sistemas)")

# Con dos cores para Wii, Provenance pregunta cuál usar en cada arranque. Dejamos solo el Dolphin nativo.
core = Path(sys.argv[1]) / "Frameworks" / "PVRetroArch.framework" / "Core.plist"
if core.exists():
    plist = plistlib.loads(core.read_bytes())
    for sub in plist.get("PVCores", []):
        if sub.get("PVCoreIdentifier") == "dolphin.libretro.framework":
            sub["PVSupportedSystems"] = []
    core.write_bytes(plistlib.dumps(plist))
    print("Dolphin (RetroArch) desactivado: Wii arranca directo con Dolphin")
