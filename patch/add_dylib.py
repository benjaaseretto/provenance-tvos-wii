#!/usr/bin/env python3
"""Agrega un LC_LOAD_DYLIB a un Mach-O arm64 delgado usando el padding del header."""
import struct
import sys

LC_LOAD_DYLIB = 0xC
LC_SEGMENT_64 = 0x19
LC_CODE_SIGNATURE = 0x1D

path, dylib = sys.argv[1], sys.argv[2]
data = bytearray(open(path, "rb").read())
magic, cputype, cpusub, ftype, ncmds, sizeofcmds, flags, _ = struct.unpack_from("<IiiIIIII", data, 0)
assert magic == 0xFEEDFACF, "solo Mach-O 64 bits delgado"
hdr = 32

first_sect = len(data)
off = hdr
for _ in range(ncmds):
    cmd, size = struct.unpack_from("<II", data, off)
    if cmd == LC_LOAD_DYLIB and dylib.encode() in data[off:off + size]:
        sys.exit(f"ya tiene {dylib}")
    if cmd == LC_SEGMENT_64:
        nsects = struct.unpack_from("<I", data, off + 64)[0]
        for s in range(nsects):
            so = off + 72 + s * 80
            sect_off = struct.unpack_from("<I", data, so + 48)[0]
            if sect_off:
                first_sect = min(first_sect, sect_off)
    off += size

name = dylib.encode() + b"\0"
cmdsize = (24 + len(name) + 7) & ~7
end = hdr + sizeofcmds
if end + cmdsize > first_sect:
    sys.exit(f"sin espacio: hay {first_sect - end} bytes, hacen falta {cmdsize}")
lc = struct.pack("<IIIIII", LC_LOAD_DYLIB, cmdsize, 24, 2, 0x10000, 0x10000) + name
lc += b"\0" * (cmdsize - len(lc))
data[end:end + cmdsize] = lc
struct.pack_into("<II", data, 16, ncmds + 1, sizeofcmds + cmdsize)
open(path, "wb").write(data)
print(f"agregado {dylib} ({first_sect - end - cmdsize} bytes libres)")
