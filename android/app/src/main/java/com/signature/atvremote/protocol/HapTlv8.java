package com.signature.atvremote.protocol;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Codificador y decodificador TLV8 para HAP (HomeKit Accessory Protocol) usado en Companion.
 */
public final class HapTlv8 {

    public static final int TAG_METHOD = 0x00;
    public static final int TAG_IDENTIFIER = 0x01;
    public static final int TAG_SALT = 0x02;
    public static final int TAG_PUBLIC_KEY = 0x03;
    public static final int TAG_PROOF = 0x04;
    public static final int TAG_ENCRYPTED_DATA = 0x05;
    public static final int TAG_SEQ_NO = 0x06;
    public static final int TAG_ERROR = 0x07;
    public static final int TAG_BACKOFF = 0x08;
    public static final int TAG_CERTIFICATE = 0x09;
    public static final int TAG_SIGNATURE = 0x0A;
    public static final int TAG_PERMISSIONS = 0x0B;
    public static final int TAG_NAME = 0x11;
    public static final int TAG_FLAGS = 0x13;

    private HapTlv8() {}

    public static Map<Integer, byte[]> readTlv(byte[] data) {
        Map<Integer, byte[]> result = new LinkedHashMap<>();
        int pos = 0;
        while (pos + 2 <= data.length) {
            int tag = data[pos] & 0xFF;
            int len = data[pos + 1] & 0xFF;
            int end = Math.min(data.length, pos + 2 + len);
            byte[] chunk = Arrays.copyOfRange(data, pos + 2, end);
            byte[] existing = result.get(tag);
            if (existing == null) {
                result.put(tag, chunk);
            } else {
                byte[] merged = new byte[existing.length + chunk.length];
                System.arraycopy(existing, 0, merged, 0, existing.length);
                System.arraycopy(chunk, 0, merged, existing.length, chunk.length);
                result.put(tag, merged);
            }
            pos += 2 + len;
        }
        return result;
    }

    public static byte[] writeTlv(Map<Integer, byte[]> items) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map.Entry<Integer, byte[]> entry : items.entrySet()) {
            int tag = entry.getKey() & 0xFF;
            byte[] val = entry.getValue();
            if (val.length == 0) {
                out.write(tag);
                out.write(0);
                continue;
            }
            int pos = 0;
            int rem = val.length;
            while (pos < val.length) {
                int chunk = Math.min(rem, 255);
                out.write(tag);
                out.write(chunk);
                out.write(val, pos, chunk);
                pos += chunk;
                rem -= chunk;
            }
        }
        return out.toByteArray();
    }
}
