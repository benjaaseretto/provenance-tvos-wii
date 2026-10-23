#!/usr/bin/env python3
"""Hace que Dolphin (Wii) en Provenance se juegue con el control del Apple TV.

Sin un gamepad completo conectado, el core escribe una plantilla de controles táctiles en pantalla
(que en tvOS no existen) y el control remoto queda sin asignar. Reemplazamos esas plantillas,
dentro de PVDolphin, por un mapeo al control remoto ("Remote"):
  clic de la superficie = A, play/pausa = B, superficie = cruz y palanca del Nunchuk.
Las plantillas para gamepads MFi (PlayStation, Xbox) no se tocan.
Uso: control_remoto.py <Provenance.app>
"""
import sys
from pathlib import Path

DIRS = {"Up": "Up", "Down": "Down", "Left": "Left", "Right": "Right"}


def cruz(prefijo: str) -> str:
    return "".join(f"{prefijo}/{d} = `D-Pad {d}` | `Direction Pad {d}`\n" for d in DIRS)


WIIMOTE = (
    "[Wiimote%d]\n"
    "TouchscreenIndex = %d\n"
    "Device = MFi/0/Remote\n"
    "Buttons/A = `Button A`\n"
    "Buttons/B = `Button X`\n"
    + cruz("D-Pad")
    + "Extension = Nunchuk\n"
    + cruz("Nunchuk/Stick")
    + "Nunchuk/Stick/Calibration = 100.00 141.42 100.00 141.42 100.00 141.42 100.00 141.42\n"
    "Source = %d\n"
)

# El mando de GameCube queda con su plantilla: varios juegos de Wii (WWE '12, por ejemplo) también lo
# aceptan, y si ambos respondieran al mismo control remoto el juego recibiría cada botón dos veces.
PLANTILLAS = {
    b"[Wiimote%d]\nDevice = iOS/%d/Touchscreen": WIIMOTE,
}

binario = Path(sys.argv[1]) / "Frameworks" / "PVDolphin.framework" / "PVDolphin"
data = bytearray(binario.read_bytes())
for clave, nueva in PLANTILLAS.items():
    i = data.find(clave)
    if i < 0:
        sys.exit(f"no encontré la plantilla {clave[:12]!r} (¿ya parcheado u otra versión?)")
    inicio = data.rfind(b"\0", 0, i) + 1
    fin = data.find(b"\0", i)
    nueva_b = nueva.encode()
    if len(nueva_b) > fin - inicio:
        sys.exit(f"la plantilla nueva no entra: {len(nueva_b)} > {fin - inicio}")
    data[inicio:fin] = nueva_b.ljust(fin - inicio, b"\n")
binario.write_bytes(data)
print("Dolphin: controles asignados al control remoto del Apple TV")
