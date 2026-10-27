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

/**
 * Codificador y lector de bplist00 / NSKeyedArchiver para las operaciones de teclado RTI (_tiStart / _tiC)
 * en Apple TV Companion Protocol.
 */
public final class RtiPayload {

    private RtiPayload() {}

    public static final class Uid {
        public final int id;
        public Uid(int id) { this.id = id; }
    }

    /**
     * Extrae los 16 bytes de sessionUUID -> NS.uuidbytes de un bplist00 NSKeyedArchiver (_tiD).
     */
    @SuppressWarnings("unchecked")
    public static byte[] extractSessionUuid(byte[] bplist) {
        if (bplist == null || bplist.length < 40) return null;
        try {
            Object root = parseBplist(bplist);
            if (!(root instanceof Map)) return null;
            Map<String, Object> topMap = (Map<String, Object>) root;
            List<Object> objects = (List<Object>) topMap.get("$objects");
            Map<String, Object> top = (Map<String, Object>) topMap.get("$top");
            if (objects == null || top == null) return null;

            Object cur = top.get("sessionUUID");
            if (cur instanceof Uid) {
                cur = objects.get(((Uid) cur).id);
            }
            if (cur instanceof Map) {
                Object uuidBytes = ((Map<String, Object>) cur).get("NS.uuidbytes");
                if (uuidBytes instanceof Uid) {
                    uuidBytes = objects.get(((Uid) uuidBytes).id);
                }
                if (uuidBytes instanceof byte[]) {
                    return (byte[]) uuidBytes;
                }
            }
        } catch (Exception ignored) {
        }
        // Fallback: buscar la clave ASCII "NS.uuidbytes" y el objeto Data de 16 bytes (0x4F 0x10 <16B>)
        for (int i = 0; i + 18 <= bplist.length; i++) {
            if ((bplist[i] & 0xFF) == 0x4F && (bplist[i + 1] & 0xFF) == 0x10) {
                return Arrays.copyOfRange(bplist, i + 2, i + 18);
            }
        }
        return null;
    }

    public static byte[] buildClearTextPayload(byte[] sessionUuid16) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("$version", 100000L);
        root.put("$archiver", "RTIKeyedArchiver");
        Map<String, Object> top = new LinkedHashMap<>();
        top.put("textOperations", new Uid(1));
        root.put("$top", top);

        List<Object> objs = new ArrayList<>();
        objs.add("$null"); // 0

        Map<String, Object> obj1 = new LinkedHashMap<>();
        obj1.put("$class", new Uid(7));
        obj1.put("targetSessionUUID", new Uid(5));
        obj1.put("keyboardOutput", new Uid(2));
        obj1.put("textToAssert", new Uid(4));
        objs.add(obj1); // 1

        Map<String, Object> obj2 = new LinkedHashMap<>();
        obj2.put("$class", new Uid(3));
        objs.add(obj2); // 2

        Map<String, Object> obj3 = new LinkedHashMap<>();
        obj3.put("$classname", "TIKeyboardOutput");
        obj3.put("$classes", Arrays.asList("TIKeyboardOutput", "NSObject"));
        objs.add(obj3); // 3

        objs.add(""); // 4

        Map<String, Object> obj5 = new LinkedHashMap<>();
        obj5.put("NS.uuidbytes", sessionUuid16);
        obj5.put("$class", new Uid(6));
        objs.add(obj5); // 5

        Map<String, Object> obj6 = new LinkedHashMap<>();
        obj6.put("$classname", "NSUUID");
        obj6.put("$classes", Arrays.asList("NSUUID", "NSObject"));
        objs.add(obj6); // 6

        Map<String, Object> obj7 = new LinkedHashMap<>();
        obj7.put("$classname", "RTITextOperations");
        obj7.put("$classes", Arrays.asList("RTITextOperations", "NSObject"));
        objs.add(obj7); // 7

        root.put("$objects", objs);
        return writeBplist(root);
    }

    public static byte[] buildInputTextPayload(byte[] sessionUuid16, String text) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("$version", 100000L);
        root.put("$archiver", "RTIKeyedArchiver");
        Map<String, Object> top = new LinkedHashMap<>();
        top.put("textOperations", new Uid(1));
        root.put("$top", top);

        List<Object> objs = new ArrayList<>();
        objs.add("$null"); // 0

        Map<String, Object> obj1 = new LinkedHashMap<>();
        obj1.put("keyboardOutput", new Uid(2));
        obj1.put("$class", new Uid(7));
        obj1.put("targetSessionUUID", new Uid(5));
        objs.add(obj1); // 1

        Map<String, Object> obj2 = new LinkedHashMap<>();
        obj2.put("insertionText", new Uid(3));
        obj2.put("$class", new Uid(4));
        objs.add(obj2); // 2

        objs.add(text); // 3

        Map<String, Object> obj4 = new LinkedHashMap<>();
        obj4.put("$classname", "TIKeyboardOutput");
        obj4.put("$classes", Arrays.asList("TIKeyboardOutput", "NSObject"));
        objs.add(obj4); // 4

        Map<String, Object> obj5 = new LinkedHashMap<>();
        obj5.put("NS.uuidbytes", sessionUuid16);
        obj5.put("$class", new Uid(6));
        objs.add(obj5); // 5

        Map<String, Object> obj6 = new LinkedHashMap<>();
        obj6.put("$classname", "NSUUID");
        obj6.put("$classes", Arrays.asList("NSUUID", "NSObject"));
        objs.add(obj6); // 6

        Map<String, Object> obj7 = new LinkedHashMap<>();
        obj7.put("$classname", "RTITextOperations");
        obj7.put("$classes", Arrays.asList("RTITextOperations", "NSObject"));
        objs.add(obj7); // 7

        root.put("$objects", objs);
        return writeBplist(root);
    }

    // =========================================================================
    // Escritor y lector compacto de Binary Plist (bplist00)
    // =========================================================================

    @SuppressWarnings("unchecked")
    public static byte[] writeBplist(Object root) {
        List<Object> flat = new ArrayList<>();
        flatten(root, flat);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] header = "bplist00".getBytes(StandardCharsets.US_ASCII);
        out.write(header, 0, header.length);

        int[] offsets = new int[flat.size()];
        for (int i = 0; i < flat.size(); i++) {
            offsets[i] = out.size();
            Object obj = flat.get(i);
            if (obj == null) {
                out.write(0x00);
            } else if (obj instanceof Boolean) {
                out.write(((Boolean) obj) ? 0x09 : 0x08);
            } else if (obj instanceof Uid) {
                out.write(0x80);
                out.write(((Uid) obj).id & 0xFF);
            } else if (obj instanceof Number) {
                long v = ((Number) obj).longValue();
                if (v >= 0 && v <= 0xFF) {
                    out.write(0x10);
                    out.write((int) v);
                } else if (v >= 0 && v <= 0xFFFF) {
                    out.write(0x11);
                    out.write((int) ((v >>> 8) & 0xFF));
                    out.write((int) (v & 0xFF));
                } else {
                    out.write(0x12);
                    out.write((int) ((v >>> 24) & 0xFF));
                    out.write((int) ((v >>> 16) & 0xFF));
                    out.write((int) ((v >>> 8) & 0xFF));
                    out.write((int) (v & 0xFF));
                }
            } else if (obj instanceof byte[]) {
                byte[] b = (byte[]) obj;
                writeMarkerAndCount(out, 0x40, b.length);
                out.write(b, 0, b.length);
            } else if (obj instanceof String) {
                String s = (String) obj;
                boolean ascii = true;
                for (int c = 0; c < s.length(); c++) {
                    if (s.charAt(c) > 0x7F) {
                        ascii = false;
                        break;
                    }
                }
                if (ascii) {
                    byte[] b = s.getBytes(StandardCharsets.US_ASCII);
                    writeMarkerAndCount(out, 0x50, b.length);
                    out.write(b, 0, b.length);
                } else {
                    byte[] b = s.getBytes(StandardCharsets.UTF_16BE);
                    writeMarkerAndCount(out, 0x60, s.length());
                    out.write(b, 0, b.length);
                }
            } else if (obj instanceof List) {
                List<Object> list = (List<Object>) obj;
                writeMarkerAndCount(out, 0xA0, list.size());
                for (Object item : list) {
                    out.write(indexOfRef(flat, item));
                }
            } else if (obj instanceof Map) {
                Map<Object, Object> map = (Map<Object, Object>) obj;
                writeMarkerAndCount(out, 0xD0, map.size());
                for (Object k : map.keySet()) {
                    out.write(indexOfRef(flat, k));
                }
                for (Object v : map.values()) {
                    out.write(indexOfRef(flat, v));
                }
            }
        }

        int offsetTableOffset = out.size();
        for (int off : offsets) {
            out.write((off >>> 8) & 0xFF);
            out.write(off & 0xFF);
        }

        ByteBuffer trailer = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN);
        trailer.position(6);
        trailer.put((byte) 2); // offsetIntSize = 2 bytes
        trailer.put((byte) 1); // objectRefSize = 1 byte
        trailer.putLong(flat.size());
        trailer.putLong(0);    // topObject = 0
        trailer.putLong(offsetTableOffset);
        byte[] tr = trailer.array();
        out.write(tr, 0, tr.length);
        return out.toByteArray();
    }

    private static void writeMarkerAndCount(ByteArrayOutputStream out, int highNibble, int count) {
        if (count < 0x0F) {
            out.write(highNibble | count);
        } else {
            out.write(highNibble | 0x0F);
            if (count <= 0xFF) {
                out.write(0x10);
                out.write(count);
            } else {
                out.write(0x11);
                out.write((count >>> 8) & 0xFF);
                out.write(count & 0xFF);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void flatten(Object obj, List<Object> flat) {
        if (indexOfRef(flat, obj) >= 0) return;
        flat.add(obj);
        if (obj instanceof List) {
            for (Object item : (List<Object>) obj) {
                flatten(item, flat);
            }
        } else if (obj instanceof Map) {
            Map<Object, Object> map = (Map<Object, Object>) obj;
            for (Object k : map.keySet()) flatten(k, flat);
            for (Object v : map.values()) flatten(v, flat);
        }
    }

    private static int indexOfRef(List<Object> flat, Object target) {
        for (int i = 0; i < flat.size(); i++) {
            Object item = flat.get(i);
            if (item == target) return i;
            if (target instanceof String && item instanceof String && target.equals(item)) return i;
            if (target instanceof Number && item instanceof Number && target.equals(item)) return i;
            if (target instanceof Uid && item instanceof Uid && ((Uid) target).id == ((Uid) item).id) return i;
        }
        return -1;
    }

    public static Object parseBplist(byte[] data) {
        if (data.length < 40) return null;
        String magic = new String(data, 0, 8, StandardCharsets.US_ASCII);
        if (!"bplist00".equals(magic)) return null;

        ByteBuffer trailer = ByteBuffer.wrap(data, data.length - 32, 32).order(ByteOrder.BIG_ENDIAN);
        trailer.position(6);
        int offsetIntSize = trailer.get() & 0xFF;
        int objectRefSize = trailer.get() & 0xFF;
        int numObjects = (int) trailer.getLong();
        int topObject = (int) trailer.getLong();
        int offsetTableOffset = (int) trailer.getLong();

        int[] offsets = new int[numObjects];
        for (int i = 0; i < numObjects; i++) {
            offsets[i] = (int) readBigEndianUnsigned(data, offsetTableOffset + i * offsetIntSize, offsetIntSize);
        }
        return parseBplistObject(data, offsets, objectRefSize, topObject);
    }

    private static Object parseBplistObject(byte[] data, int[] offsets, int refSize, int objIdx) {
        int pos = offsets[objIdx];
        int marker = data[pos] & 0xFF;
        int high = marker & 0xF0;
        int low = marker & 0x0F;

        if (marker == 0x00) return null;
        if (marker == 0x08) return Boolean.FALSE;
        if (marker == 0x09) return Boolean.TRUE;
        if (high == 0x10) {
            int n = 1 << low;
            return readBigEndianUnsigned(data, pos + 1, n);
        }
        if (high == 0x40) {
            int[] lc = readLenAndOffset(data, pos, low);
            return Arrays.copyOfRange(data, lc[1], lc[1] + lc[0]);
        }
        if (high == 0x50) {
            int[] lc = readLenAndOffset(data, pos, low);
            return new String(data, lc[1], lc[0], StandardCharsets.US_ASCII);
        }
        if (high == 0x60) {
            int[] lc = readLenAndOffset(data, pos, low);
            return new String(data, lc[1], lc[0] * 2, StandardCharsets.UTF_16BE);
        }
        if (high == 0x80) {
            int n = low + 1;
            return new Uid((int) readBigEndianUnsigned(data, pos + 1, n));
        }
        if (high == 0xA0) {
            int[] lc = readLenAndOffset(data, pos, low);
            List<Object> list = new ArrayList<>(lc[0]);
            for (int i = 0; i < lc[0]; i++) {
                int ref = (int) readBigEndianUnsigned(data, lc[1] + i * refSize, refSize);
                list.add(parseBplistObject(data, offsets, refSize, ref));
            }
            return list;
        }
        if (high == 0xD0) {
            int[] lc = readLenAndOffset(data, pos, low);
            Map<Object, Object> map = new LinkedHashMap<>();
            int keyStart = lc[1];
            int valStart = keyStart + lc[0] * refSize;
            for (int i = 0; i < lc[0]; i++) {
                int kRef = (int) readBigEndianUnsigned(data, keyStart + i * refSize, refSize);
                int vRef = (int) readBigEndianUnsigned(data, valStart + i * refSize, refSize);
                map.put(
                        parseBplistObject(data, offsets, refSize, kRef),
                        parseBplistObject(data, offsets, refSize, vRef));
            }
            return map;
        }
        return null;
    }

    private static int[] readLenAndOffset(byte[] data, int pos, int low) {
        if (low != 0x0F) {
            return new int[] { low, pos + 1 };
        }
        int intMarker = data[pos + 1] & 0xFF;
        int n = 1 << (intMarker & 0x0F);
        int len = (int) readBigEndianUnsigned(data, pos + 2, n);
        return new int[] { len, pos + 2 + n };
    }

    private static long readBigEndianUnsigned(byte[] data, int offset, int length) {
        long res = 0;
        for (int i = 0; i < length; i++) {
            res = (res << 8) | (data[offset + i] & 0xFF);
        }
        return res;
    }
}
