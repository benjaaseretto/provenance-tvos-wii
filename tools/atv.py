"""Control del Apple TV por el protocolo Companion (como el control remoto del iPhone).

Uso:
  atv.py pair                 empareja; lee el PIN de atv-pin.txt
  atv.py open <url|bundle>    abre una app o una URL (p. ej. la página de la App Store)
  atv.py keys select down ... aprieta teclas del control
  atv.py text <texto>         escribe en el campo de texto enfocado
  atv.py apps                 lista apps instaladas
"""

import asyncio
import json
import os
import sys

import pyatv
from pyatv.const import Protocol

from config import CONFIG

HERE = os.path.dirname(os.path.abspath(__file__))
CREDS = os.path.join(HERE, "atv-creds.json")
PIN_FILE = os.path.join(HERE, "atv-pin.txt")
HOST = CONFIG["ATV_IP"]


async def find(loop):
    found = await pyatv.scan(loop, hosts=[HOST], timeout=5)
    if not found:
        raise SystemExit("no encontré el Apple TV")
    conf = found[0]
    if os.path.exists(CREDS):
        for proto, cred in json.load(open(CREDS)).items():
            conf.set_credentials(Protocol[proto], cred)
    return conf


async def pair(loop):
    conf = await find(loop)
    creds = json.load(open(CREDS)) if os.path.exists(CREDS) else {}
    for proto in (Protocol.Companion, Protocol.AirPlay):
        if proto.name in creds:
            continue
        handler = await pyatv.pair(conf, proto, loop)
        await handler.begin()
        print(f"{proto.name}: esperando PIN en {PIN_FILE}", flush=True)
        for _ in range(300):
            if os.path.exists(PIN_FILE) and open(PIN_FILE).read().strip():
                break
            await asyncio.sleep(1)
        handler.pin(int(open(PIN_FILE).read().strip()))
        os.remove(PIN_FILE)
        await handler.finish()
        if handler.has_paired:
            creds[proto.name] = handler.service.credentials
            json.dump(creds, open(CREDS, "w"))
            print(f"{proto.name}: emparejado", flush=True)
        else:
            print(f"{proto.name}: falló", flush=True)
        await handler.close()


async def run(loop, cmd, args):
    conf = await find(loop)
    atv = await pyatv.connect(conf, loop)
    try:
        if cmd == "open":
            await atv.apps.launch_app(args[0])
        elif cmd == "keys":
            for k in args:
                await getattr(atv.remote_control, k)()
                await asyncio.sleep(0.6)
        elif cmd == "text":
            await atv.keyboard.text_set(" ".join(args))
        elif cmd == "apps":
            for app in await atv.apps.app_list():
                print(app.identifier, "|", app.name)
        print("ok")
    finally:
        atv.close()


def main():
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    cmd, args = sys.argv[1], sys.argv[2:]
    if cmd == "pair":
        loop.run_until_complete(pair(loop))
    else:
        loop.run_until_complete(run(loop, cmd, args))


if __name__ == "__main__":
    main()
