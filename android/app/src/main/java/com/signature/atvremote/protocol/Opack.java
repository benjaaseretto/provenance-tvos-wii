package com.signature.atvremote.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Codificador y decodificador binario OPACK (Apple Companion Protocol).
 * Compatible byte a byte con pyatv.support.opack.
 */
public final class Opack {

    private Opack() {}

    /** Entero que preserva su ancho en bytes al re-empaquetarse. */
    public static final class SizedInt extends Number {
        public final long value;
        public final int sizeBytes;

        public SizedInt(long value, int sizeBytes) {
            this.value = value;
            this.sizeBytes = sizeBytes;
        }

        @Override public int intValue() { return (int) value; }
        @Override public long longValue() { return value; }
        @Override public float floatValue() { return (float) value; }
        @Override public double doubleValue() { return (double) value; }
        @Override public String toString() { return Long.toString(value); }

        @Override
        public boolean equals(Object o) {
            if (o instanceof Number) {
                return ((Number) o).longValue() == value;
            }
            return false;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(value);
        }
    }

    public static byte[] pack(Object data) {
        List<byte[]> objectList = new ArrayList<>();
        return packInternal(data, objectList);
    }

    @SuppressWarnings("unchecked")
    private static byte[] packInternal(Object data, List<byte[]> objectList) {
        byte[] packed;
        if (data == null) {
            packed = new byte[] { 0x04 };
        } else if (data instanceof Boolean) {
            packed = new byte[] { (byte) (((Boolean) data) ? 0x01 : 0x02) };
        } else if (data instanceof UUID) {
            UUID u = (UUID) data;
            ByteBuffer bb = ByteBuffer.allocate(17).order(ByteOrder.BIG_ENDIAN);
            bb.put((byte) 0x05);
            bb.putLong(u.getMostSignificantBits());
            bb.putLong(u.getLeastSignificantBits());
            packed = bb.array();
        } else if (data instanceof Float || data instanceof Double) {
            double d = ((Number) data).doubleValue();
            ByteBuffer bb = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN);
            bb.put((byte) 0x36);
            bb.putDouble(d);
            packed = bb.array();
        } else if (data instanceof Number) {
            long v = ((Number) data).longValue();
            int sizeHint = (data instanceof SizedInt) ? ((SizedInt) data).sizeBytes : 0;
            if (v >= 0 && v < 0x28 && sizeHint == 0) {
                packed = new byte[] { (byte) (v + 8) };
            } else if ((v >= 0 && v <= 0xFFL && sizeHint == 0) || sizeHint == 1) {
                packed = new byte[] { 0x30, (byte) (v & 0xFF) };
            } else if ((v >= 0 && v <= 0xFFFFL && sizeHint == 0) || sizeHint == 2) {
                packed = new byte[] {
                    0x31,
                    (byte) (v & 0xFF),
                    (byte) ((v >>> 8) & 0xFF)
                };
            } else if ((v >= 0 && v <= 0xFFFFFFFFL && sizeHint == 0) || sizeHint == 4) {
                ByteBuffer bb = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN);
                bb.put((byte) 0x32);
                bb.putInt((int) v);
                packed = bb.array();
            } else {
                ByteBuffer bb = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN);
                bb.put((byte) 0x33);
                bb.putLong(v);
                packed = bb.array();
            }
        } else if (data instanceof String) {
            byte[] utf8 = ((String) data).getBytes(StandardCharsets.UTF_8);
            int len = utf8.length;
            if (len <= 0x20) {
                packed = new byte[1 + len];
                packed[0] = (byte) (0x40 + len);
                System.arraycopy(utf8, 0, packed, 1, len);
            } else if (len <= 0xFF) {
                packed = new byte[2 + len];
                packed[0] = 0x61;
                packed[1] = (byte) len;
                System.arraycopy(utf8, 0, packed, 2, len);
            } else if (len <= 0xFFFF) {
                packed = new byte[3 + len];
                packed[0] = 0x62;
                packed[1] = (byte) (len & 0xFF);
                packed[2] = (byte) ((len >>> 8) & 0xFF);
                System.arraycopy(utf8, 0, packed, 3, len);
            } else {
                ByteBuffer bb = ByteBuffer.allocate(5 + len).order(ByteOrder.LITTLE_ENDIAN);
                bb.put((byte) 0x64);
                bb.putInt(len);
                bb.put(utf8);
                packed = bb.array();
            }
        } else if (data instanceof byte[]) {
            byte[] raw = (byte[]) data;
            int len = raw.length;
            if (len <= 0x20) {
                packed = new byte[1 + len];
                packed[0] = (byte) (0x70 + len);
                System.arraycopy(raw, 0, packed, 1, len);
            } else if (len <= 0xFF) {
                packed = new byte[2 + len];
                packed[0] = (byte) 0x91;
                packed[1] = (byte) len;
                System.arraycopy(raw, 0, packed, 2, len);
            } else if (len <= 0xFFFF) {
                packed = new byte[3 + len];
                packed[0] = (byte) 0x92;
                packed[1] = (byte) (len & 0xFF);
                packed[2] = (byte) ((len >>> 8) & 0xFF);
                System.arraycopy(raw, 0, packed, 3, len);
            } else {
                ByteBuffer bb = ByteBuffer.allocate(5 + len).order(ByteOrder.LITTLE_ENDIAN);
                bb.put((byte) 0x93);
                bb.putInt(len);
                bb.put(raw);
                packed = bb.array();
            }
        } else if (data instanceof List) {
            List<Object> list = (List<Object>) data;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(0xD0 + Math.min(list.size(), 0x0F));
            for (Object item : list) {
                byte[] b = packInternal(item, objectList);
                out.write(b, 0, b.length);
            }
            if (list.size() >= 0x0F) {
                out.write(0x03);
            }
            packed = out.toByteArray();
        } else if (data instanceof Map) {
            Map<Object, Object> map = (Map<Object, Object>) data;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(0xE0 + Math.min(map.size(), 0x0F));
            for (Map.Entry<Object, Object> entry : map.entrySet()) {
                byte[] kb = packInternal(entry.getKey(), objectList);
                byte[] vb = packInternal(entry.getValue(), objectList);
                out.write(kb, 0, kb.length);
                out.write(vb, 0, vb.length);
            }
            if (map.size() >= 0x0F) {
                out.write(0x03);
            }
            packed = out.toByteArray();
        } else {
            throw new IllegalArgumentException("Tipo OPACK no soportado: " + data.getClass());
        }

        int existingIdx = indexOfBytes(objectList, packed);
        if (existingIdx >= 0) {
            if (existingIdx < 0x21) {
                packed = new byte[] { (byte) (0xA0 + existingIdx) };
            } else if (existingIdx <= 0xFF) {
                packed = new byte[] { (byte) 0xC1, (byte) existingIdx };
            } else if (existingIdx <= 0xFFFF) {
                packed = new byte[] {
                    (byte) 0xC2,
                    (byte) (existingIdx & 0xFF),
                    (byte) ((existingIdx >>> 8) & 0xFF)
                };
            }
        } else if (packed.length > 1) {
            objectList.add(packed);
        }
        return packed;
    }

    private static int indexOfBytes(List<byte[]> list, byte[] target) {
        for (int i = 0; i < list.size(); i++) {
            if (Arrays.equals(list.get(i), target)) {
                return i;
            }
        }
        return -1;
    }

    public static Object unpack(byte[] data) {
        List<Object> objectList = new ArrayList<>();
        DecodeResult res = unpackInternal(data, 0, objectList);
        return res.value;
    }

    private static final class DecodeResult {
        final Object value;
        final int nextPos;

        DecodeResult(Object value, int nextPos) {
            this.value = value;
            this.nextPos = nextPos;
        }
    }

    private static DecodeResult unpackInternal(byte[] data, int pos, List<Object> objectList) {
        int b0 = data[pos] & 0xFF;
        Object value;
        int nextPos;
        boolean addToObjectList = true;

        if (b0 == 0x01) {
            value = Boolean.TRUE;
            nextPos = pos + 1;
            addToObjectList = false;
        } else if (b0 == 0x02) {
            value = Boolean.FALSE;
            nextPos = pos + 1;
            addToObjectList = false;
        } else if (b0 == 0x04) {
            value = null;
            nextPos = pos + 1;
            addToObjectList = false;
        } else if (b0 == 0x05) {
            ByteBuffer bb = ByteBuffer.wrap(data, pos + 1, 16).order(ByteOrder.BIG_ENDIAN);
            value = new UUID(bb.getLong(), bb.getLong());
            nextPos = pos + 17;
        } else if (b0 == 0x06) {
            long v = readLittleEndianUnsigned(data, pos + 1, 8);
            value = v;
            nextPos = pos + 9;
        } else if (b0 >= 0x08 && b0 <= 0x2F) {
            value = (long) (b0 - 8);
            nextPos = pos + 1;
            addToObjectList = false;
        } else if (b0 == 0x35) {
            float f = ByteBuffer.wrap(data, pos + 1, 4).order(ByteOrder.LITTLE_ENDIAN).getFloat();
            value = (double) f;
            nextPos = pos + 5;
        } else if (b0 == 0x36) {
            double d = ByteBuffer.wrap(data, pos + 1, 8).order(ByteOrder.LITTLE_ENDIAN).getDouble();
            value = d;
            nextPos = pos + 9;
        } else if ((b0 & 0xF0) == 0x30) {
            int numBytes = 1 << (b0 & 0x0F);
            long v = readLittleEndianUnsigned(data, pos + 1, numBytes);
            value = new SizedInt(v, numBytes);
            nextPos = pos + 1 + numBytes;
        } else if (b0 >= 0x40 && b0 <= 0x60) {
            int len = b0 - 0x40;
            value = new String(data, pos + 1, len, StandardCharsets.UTF_8);
            nextPos = pos + 1 + len;
        } else if (b0 > 0x60 && b0 <= 0x64) {
            int numBytes = b0 & 0x0F;
            int len = (int) readLittleEndianUnsigned(data, pos + 1, numBytes);
            value = new String(data, pos + 1 + numBytes, len, StandardCharsets.UTF_8);
            nextPos = pos + 1 + numBytes + len;
        } else if (b0 >= 0x70 && b0 <= 0x90) {
            int len = b0 - 0x70;
            value = Arrays.copyOfRange(data, pos + 1, pos + 1 + len);
            nextPos = pos + 1 + len;
        } else if (b0 >= 0x91 && b0 <= 0x94) {
            int numBytes = 1 << ((b0 & 0x0F) - 1);
            int len = (int) readLittleEndianUnsigned(data, pos + 1, numBytes);
            int start = pos + 1 + numBytes;
            value = Arrays.copyOfRange(data, start, start + len);
            nextPos = start + len;
        } else if ((b0 & 0xF0) == 0xD0) {
            int count = b0 & 0x0F;
            List<Object> list = new ArrayList<>();
            int ptr = pos + 1;
            if (count == 0x0F) {
                while ((data[ptr] & 0xFF) != 0x03) {
                    DecodeResult item = unpackInternal(data, ptr, objectList);
                    list.add(item.value);
                    ptr = item.nextPos;
                }
                ptr++;
            } else {
                for (int i = 0; i < count; i++) {
                    DecodeResult item = unpackInternal(data, ptr, objectList);
                    list.add(item.value);
                    ptr = item.nextPos;
                }
            }
            value = list;
            nextPos = ptr;
            addToObjectList = false;
        } else if ((b0 & 0xE0) == 0xE0) {
            int count = b0 & 0x0F;
            Map<Object, Object> map = new LinkedHashMap<>();
            int ptr = pos + 1;
            if (count == 0x0F) {
                while ((data[ptr] & 0xFF) != 0x03) {
                    DecodeResult k = unpackInternal(data, ptr, objectList);
                    DecodeResult v = unpackInternal(data, k.nextPos, objectList);
                    map.put(k.value, v.value);
                    ptr = v.nextPos;
                }
                ptr++;
            } else {
                for (int i = 0; i < count; i++) {
                    DecodeResult k = unpackInternal(data, ptr, objectList);
                    DecodeResult v = unpackInternal(data, k.nextPos, objectList);
                    map.put(k.value, v.value);
                    ptr = v.nextPos;
                }
            }
            value = map;
            nextPos = ptr;
            addToObjectList = false;
        } else if (b0 >= 0xA0 && b0 <= 0xC0) {
            value = objectList.get(b0 - 0xA0);
            nextPos = pos + 1;
            addToObjectList = false;
        } else if (b0 >= 0xC1 && b0 <= 0xC4) {
            int len = b0 - 0xC0;
            int uid = (int) readLittleEndianUnsigned(data, pos + 1, len);
            value = objectList.get(uid);
            nextPos = pos + 1 + len;
            addToObjectList = false;
        } else {
            throw new IllegalArgumentException(String.format("Byte OPACK desconocido: 0x%02X en pos %d", b0, pos));
        }

        if (addToObjectList && !containsElement(objectList, value)) {
            objectList.add(value);
        }
        return new DecodeResult(value, nextPos);
    }

    private static boolean containsElement(List<Object> list, Object candidate) {
        for (Object item : list) {
            if (candidate instanceof byte[] && item instanceof byte[]) {
                if (Arrays.equals((byte[]) candidate, (byte[]) item)) return true;
            } else if (candidate != null && candidate.equals(item)) {
                return true;
            }
        }
        return false;
    }

    private static long readLittleEndianUnsigned(byte[] data, int offset, int length) {
        long result = 0;
        for (int i = 0; i < length; i++) {
            result |= ((long) (data[offset + i] & 0xFF)) << (8 * i);
        }
        return result;
    }
}
