package com.signature.atvremote.protocol;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Primitivas criptográficas RFC exactas sin dependencias externas para Apple Companion / HAP:
 * - RFC 7748 X25519
 * - RFC 8032 Ed25519
 * - RFC 8439 ChaCha20-Poly1305 AEAD (con contadores de 8 y 12 bytes de pyatv)
 * - RFC 5869 HKDF-SHA512
 */
public final class Crypto25519 {

    private static final BigInteger TWO = BigInteger.valueOf(2);
    private static final BigInteger P = TWO.pow(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger A24 = BigInteger.valueOf(121665);
    private static final BigInteger L = TWO.pow(252).add(new BigInteger("27742317777372353535851937790883648493"));
    private static final BigInteger D = new BigInteger("37095705934669439343138083508754565189542113879843219016388785533085940283555");
    private static final BigInteger I_SQRT = TWO.modPow(P.subtract(BigInteger.ONE).divide(BigInteger.valueOf(4)), P);
    private static final BigInteger BX = new BigInteger("15112221349535400772501151409588531511454012693041857206046113283949847762202");
    private static final BigInteger BY = new BigInteger("46316835694926478169428394003475163141307993866256225615783033603165251855960");
    private static final BigInteger POLY1305_P = TWO.pow(130).subtract(BigInteger.valueOf(5));

    private Crypto25519() {}

    // =========================================================================
    // HKDF-SHA512 (RFC 5869)
    // =========================================================================

    public static byte[] hkdfSha512(String salt, String info, byte[] ikm, int length) {
        return hkdfSha512(
                salt.getBytes(StandardCharsets.UTF_8),
                info.getBytes(StandardCharsets.UTF_8),
                ikm,
                length);
    }

    public static byte[] hkdfSha512(byte[] salt, byte[] info, byte[] ikm, int length) {
        try {
            byte[] actualSalt = (salt == null || salt.length == 0) ? new byte[64] : salt;
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(actualSalt, "HmacSHA512"));
            byte[] prk = mac.doFinal(ikm);

            mac.init(new SecretKeySpec(prk, "HmacSHA512"));
            byte[] result = new byte[length];
            byte[] t = new byte[0];
            int offset = 0;
            byte counter = 1;
            while (offset < length) {
                mac.reset();
                mac.update(t);
                if (info != null && info.length > 0) {
                    mac.update(info);
                }
                mac.update(counter++);
                t = mac.doFinal();
                int copy = Math.min(t.length, length - offset);
                System.arraycopy(t, 0, result, offset, copy);
                offset += copy;
            }
            return result;
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("HKDF-SHA512 error", e);
        }
    }

    public static byte[] sha512(byte[]... chunks) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-512");
            for (byte[] c : chunks) {
                md.update(c);
            }
            return md.digest();
        } catch (GeneralSecurityException e) {
            throw new RuntimeException(e);
        }
    }

    // =========================================================================
    // X25519 (RFC 7748)
    // =========================================================================

    public static byte[] x25519PublicFromPrivate(byte[] privateKey32) {
        byte[] basePoint = new byte[32];
        basePoint[0] = 9;
        return x25519ScalarMult(privateKey32, basePoint);
    }

    public static byte[] x25519ScalarMult(byte[] scalar32, byte[] uCoord32) {
        byte[] kBytes = Arrays.copyOf(scalar32, 32);
        kBytes[0] &= (byte) 248;
        kBytes[31] &= (byte) 127;
        kBytes[31] |= (byte) 64;
        BigInteger k = fromLittleEndian(kBytes);

        byte[] uBytes = Arrays.copyOf(uCoord32, 32);
        uBytes[31] &= (byte) 127;
        BigInteger u = fromLittleEndian(uBytes).mod(P);

        BigInteger x1 = u;
        BigInteger x2 = BigInteger.ONE;
        BigInteger z2 = BigInteger.ZERO;
        BigInteger x3 = u;
        BigInteger z3 = BigInteger.ONE;
        int swap = 0;

        for (int t = 254; t >= 0; t--) {
            int kt = k.testBit(t) ? 1 : 0;
            swap ^= kt;
            if (swap != 0) {
                BigInteger tmp = x2; x2 = x3; x3 = tmp;
                tmp = z2; z2 = z3; z3 = tmp;
            }
            swap = kt;

            BigInteger a = x2.add(z2).mod(P);
            BigInteger aa = a.multiply(a).mod(P);
            BigInteger b = x2.subtract(z2).mod(P);
            BigInteger bb = b.multiply(b).mod(P);
            BigInteger e = aa.subtract(bb).mod(P);
            BigInteger c = x3.add(z3).mod(P);
            BigInteger d = x3.subtract(z3).mod(P);
            BigInteger da = d.multiply(a).mod(P);
            BigInteger cb = c.multiply(b).mod(P);

            x3 = da.add(cb).modPow(TWO, P);
            z3 = x1.multiply(da.subtract(cb).modPow(TWO, P)).mod(P);
            x2 = aa.multiply(bb).mod(P);
            z2 = e.multiply(aa.add(A24.multiply(e))).mod(P);
        }

        if (swap != 0) {
            BigInteger tmp = x2; x2 = x3; x3 = tmp;
            tmp = z2; z2 = z3; z3 = tmp;
        }

        BigInteger out = x2.multiply(z2.modPow(P.subtract(TWO), P)).mod(P);
        return toLittleEndian(out, 32);
    }

    // =========================================================================
    // Ed25519 (RFC 8032)
    // =========================================================================

    private static final class EdPoint {
        final BigInteger x, y;
        EdPoint(BigInteger x, BigInteger y) {
            this.x = x;
            this.y = y;
        }
    }

    private static final EdPoint BASE_POINT = new EdPoint(BX, BY);
    private static final EdPoint ZERO_POINT = new EdPoint(BigInteger.ZERO, BigInteger.ONE);

    private static EdPoint edAdd(EdPoint p1, EdPoint p2) {
        BigInteger x1y2 = p1.x.multiply(p2.y).mod(P);
        BigInteger y1x2 = p1.y.multiply(p2.x).mod(P);
        BigInteger y1y2 = p1.y.multiply(p2.y).mod(P);
        BigInteger x1x2 = p1.x.multiply(p2.x).mod(P);
        BigInteger dxxyy = D.multiply(x1x2).mod(P).multiply(y1y2).mod(P);

        BigInteger x3 = x1y2.add(y1x2).multiply(BigInteger.ONE.add(dxxyy).modInverse(P)).mod(P);
        BigInteger y3 = y1y2.add(x1x2).multiply(BigInteger.ONE.subtract(dxxyy).mod(P).modInverse(P)).mod(P);
        return new EdPoint(x3, y3);
    }

    private static EdPoint edScalarMult(EdPoint pt, BigInteger s) {
        EdPoint res = ZERO_POINT;
        EdPoint addend = pt;
        BigInteger k = s;
        while (k.signum() > 0) {
            if (k.testBit(0)) {
                res = edAdd(res, addend);
            }
            addend = edAdd(addend, addend);
            k = k.shiftRight(1);
        }
        return res;
    }

    private static byte[] encodeEdPoint(EdPoint pt) {
        byte[] out = toLittleEndian(pt.y.mod(P), 32);
        if (pt.x.mod(P).testBit(0)) {
            out[31] |= (byte) 0x80;
        }
        return out;
    }

    private static EdPoint decodeEdPoint(byte[] enc) throws GeneralSecurityException {
        if (enc.length != 32) {
            throw new GeneralSecurityException("Longitud de clave pública Ed25519 inválida");
        }
        byte[] yBytes = Arrays.copyOf(enc, 32);
        int x0 = (yBytes[31] >>> 7) & 1;
        yBytes[31] &= (byte) 0x7F;
        BigInteger y = fromLittleEndian(yBytes);
        if (y.compareTo(P) >= 0) {
            throw new GeneralSecurityException("Coordenada y fuera de rango en Ed25519");
        }
        BigInteger y2 = y.multiply(y).mod(P);
        BigInteger u = y2.subtract(BigInteger.ONE).mod(P);
        BigInteger v = D.multiply(y2).add(BigInteger.ONE).mod(P);
        BigInteger v3 = v.modPow(BigInteger.valueOf(3), P);
        BigInteger v7 = v3.multiply(v3).mod(P).multiply(v).mod(P);
        BigInteger uv7 = u.multiply(v7).mod(P);
        BigInteger x = u.multiply(v3).mod(P).multiply(uv7.modPow(P.subtract(BigInteger.valueOf(5)).divide(BigInteger.valueOf(8)), P)).mod(P);

        BigInteger vx2 = v.multiply(x).mod(P).multiply(x).mod(P);
        if (vx2.equals(u)) {
            // ok
        } else if (vx2.equals(P.subtract(u).mod(P))) {
            x = x.multiply(I_SQRT).mod(P);
        } else {
            throw new GeneralSecurityException("Punto Ed25519 inválido");
        }
        if (x.equals(BigInteger.ZERO) && x0 == 1) {
            throw new GeneralSecurityException("Signo x0 inválido para x=0");
        }
        if ((x.testBit(0) ? 1 : 0) != x0) {
            x = P.subtract(x);
        }
        return new EdPoint(x, y);
    }

    public static byte[] ed25519PublicFromSeed(byte[] seed32) {
        byte[] h = sha512(seed32);
        byte[] aBytes = Arrays.copyOfRange(h, 0, 32);
        aBytes[0] &= (byte) 248;
        aBytes[31] &= (byte) 127;
        aBytes[31] |= (byte) 64;
        BigInteger a = fromLittleEndian(aBytes);
        return encodeEdPoint(edScalarMult(BASE_POINT, a));
    }

    public static byte[] ed25519Sign(byte[] seed32, byte[] message) {
        byte[] h = sha512(seed32);
        byte[] aBytes = Arrays.copyOfRange(h, 0, 32);
        aBytes[0] &= (byte) 248;
        aBytes[31] &= (byte) 127;
        aBytes[31] |= (byte) 64;
        BigInteger a = fromLittleEndian(aBytes);
        byte[] prefix = Arrays.copyOfRange(h, 32, 64);
        byte[] pubKey = encodeEdPoint(edScalarMult(BASE_POINT, a));

        BigInteger r = fromLittleEndian(sha512(prefix, message)).mod(L);
        byte[] rEnc = encodeEdPoint(edScalarMult(BASE_POINT, r));
        BigInteger k = fromLittleEndian(sha512(rEnc, pubKey, message)).mod(L);
        BigInteger s = r.add(k.multiply(a)).mod(L);
        byte[] sEnc = toLittleEndian(s, 32);

        byte[] sig = new byte[64];
        System.arraycopy(rEnc, 0, sig, 0, 32);
        System.arraycopy(sEnc, 0, sig, 32, 32);
        return sig;
    }

    public static boolean ed25519Verify(byte[] pubKey32, byte[] message, byte[] signature64) {
        if (signature64 == null || signature64.length != 64) {
            return false;
        }
        try {
            byte[] rEnc = Arrays.copyOfRange(signature64, 0, 32);
            byte[] sEnc = Arrays.copyOfRange(signature64, 32, 64);
            BigInteger s = fromLittleEndian(sEnc);
            if (s.compareTo(L) >= 0) {
                return false;
            }
            EdPoint rPt = decodeEdPoint(rEnc);
            EdPoint aPt = decodeEdPoint(pubKey32);
            BigInteger k = fromLittleEndian(sha512(rEnc, pubKey32, message)).mod(L);

            EdPoint lhs = edScalarMult(BASE_POINT, s);
            EdPoint rhs = edAdd(rPt, edScalarMult(aPt, k));
            return Arrays.equals(encodeEdPoint(lhs), encodeEdPoint(rhs));
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    // =========================================================================
    // ChaCha20-Poly1305 AEAD (RFC 8439)
    // =========================================================================

    private static int rotl32(int v, int c) {
        return (v << c) | (v >>> (32 - c));
    }

    private static void quarterRound(int[] s, int a, int b, int c, int d) {
        s[a] += s[b]; s[d] = rotl32(s[d] ^ s[a], 16);
        s[c] += s[d]; s[b] = rotl32(s[b] ^ s[c], 12);
        s[a] += s[b]; s[d] = rotl32(s[d] ^ s[a], 8);
        s[c] += s[d]; s[b] = rotl32(s[b] ^ s[c], 7);
    }

    private static byte[] chacha20Block(byte[] key32, int counter, byte[] nonce12) {
        int[] state = new int[16];
        state[0] = 0x61707865;
        state[1] = 0x3320646e;
        state[2] = 0x79622d32;
        state[3] = 0x6b206574;
        ByteBuffer kb = ByteBuffer.wrap(key32).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 8; i++) {
            state[4 + i] = kb.getInt();
        }
        state[12] = counter;
        ByteBuffer nb = ByteBuffer.wrap(nonce12).order(ByteOrder.LITTLE_ENDIAN);
        state[13] = nb.getInt();
        state[14] = nb.getInt();
        state[15] = nb.getInt();

        int[] working = Arrays.copyOf(state, 16);
        for (int i = 0; i < 10; i++) {
            quarterRound(working, 0, 4, 8, 12);
            quarterRound(working, 1, 5, 9, 13);
            quarterRound(working, 2, 6, 10, 14);
            quarterRound(working, 3, 7, 11, 15);
            quarterRound(working, 0, 5, 10, 15);
            quarterRound(working, 1, 6, 11, 12);
            quarterRound(working, 2, 7, 8, 13);
            quarterRound(working, 3, 4, 9, 14);
        }

        ByteBuffer out = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 16; i++) {
            out.putInt(working[i] + state[i]);
        }
        return out.array();
    }

    private static byte[] chacha20Xor(byte[] key32, int initialCounter, byte[] nonce12, byte[] input) {
        byte[] output = new byte[input.length];
        int fullBlocks = (input.length + 63) / 64;
        for (int b = 0; b < fullBlocks; b++) {
            byte[] block = chacha20Block(key32, initialCounter + b, nonce12);
            int offset = b * 64;
            int len = Math.min(64, input.length - offset);
            for (int i = 0; i < len; i++) {
                output[offset + i] = (byte) (input[offset + i] ^ block[i]);
            }
        }
        return output;
    }

    private static byte[] poly1305Mac(byte[] key32, byte[] msg) {
        byte[] rBytes = Arrays.copyOfRange(key32, 0, 16);
        rBytes[3] &= 15;
        rBytes[7] &= 15;
        rBytes[11] &= 15;
        rBytes[15] &= 15;
        rBytes[4] &= (byte) 252;
        rBytes[8] &= (byte) 252;
        rBytes[12] &= (byte) 252;

        BigInteger r = fromLittleEndian(rBytes);
        BigInteger s = fromLittleEndian(Arrays.copyOfRange(key32, 16, 32));
        BigInteger acc = BigInteger.ZERO;

        for (int offset = 0; offset < msg.length; offset += 16) {
            int len = Math.min(16, msg.length - offset);
            byte[] chunk = new byte[len + 1];
            System.arraycopy(msg, offset, chunk, 0, len);
            chunk[len] = 0x01;
            BigInteger n = fromLittleEndian(chunk);
            acc = acc.add(n).multiply(r).mod(POLY1305_P);
        }
        acc = acc.add(s);
        return toLittleEndian(acc, 16);
    }

    private static byte[] buildPoly1305Data(byte[] aad, byte[] ciphertext) {
        byte[] a = (aad != null) ? aad : new byte[0];
        int aPad = (16 - (a.length % 16)) % 16;
        int cPad = (16 - (ciphertext.length % 16)) % 16;
        ByteBuffer bb = ByteBuffer.allocate(a.length + aPad + ciphertext.length + cPad + 16)
                .order(ByteOrder.LITTLE_ENDIAN);
        bb.put(a);
        for (int i = 0; i < aPad; i++) bb.put((byte) 0);
        bb.put(ciphertext);
        for (int i = 0; i < cPad; i++) bb.put((byte) 0);
        bb.putLong(a.length);
        bb.putLong(ciphertext.length);
        return bb.array();
    }

    public static byte[] chacha20Poly1305Encrypt(byte[] key32, byte[] nonce12, byte[] plaintext, byte[] aad) {
        byte[] fullNonce = padNonce12(nonce12);
        byte[] polyKey = Arrays.copyOfRange(chacha20Block(key32, 0, fullNonce), 0, 32);
        byte[] ciphertext = chacha20Xor(key32, 1, fullNonce, plaintext);
        byte[] tag = poly1305Mac(polyKey, buildPoly1305Data(aad, ciphertext));
        byte[] out = new byte[ciphertext.length + 16];
        System.arraycopy(ciphertext, 0, out, 0, ciphertext.length);
        System.arraycopy(tag, 0, out, ciphertext.length, 16);
        return out;
    }

    public static byte[] chacha20Poly1305Decrypt(byte[] key32, byte[] nonce12, byte[] ciphertextWithTag, byte[] aad)
            throws GeneralSecurityException {
        if (ciphertextWithTag == null || ciphertextWithTag.length < 16) {
            throw new GeneralSecurityException("Ciphertext demasiado corto");
        }
        byte[] fullNonce = padNonce12(nonce12);
        int ctLen = ciphertextWithTag.length - 16;
        byte[] ciphertext = Arrays.copyOfRange(ciphertextWithTag, 0, ctLen);
        byte[] receivedTag = Arrays.copyOfRange(ciphertextWithTag, ctLen, ciphertextWithTag.length);

        byte[] polyKey = Arrays.copyOfRange(chacha20Block(key32, 0, fullNonce), 0, 32);
        byte[] expectedTag = poly1305Mac(polyKey, buildPoly1305Data(aad, ciphertext));
        if (!MessageDigest.isEqual(receivedTag, expectedTag)) {
            throw new GeneralSecurityException("Tag de autenticación ChaCha20-Poly1305 inválido");
        }
        return chacha20Xor(key32, 1, fullNonce, ciphertext);
    }

    public static byte[] padNonce12(byte[] nonce) {
        if (nonce.length == 12) return nonce;
        byte[] padded = new byte[12];
        System.arraycopy(nonce, 0, padded, 12 - nonce.length, nonce.length);
        return padded;
    }

    /**
     * Cifrador de canal continuo para tramas Companion (contador little-endian de 12 bytes).
     */
    public static final class CompanionStreamCipher {
        private final byte[] outKey;
        private final byte[] inKey;
        private long outCounter = 0;
        private long inCounter = 0;

        public CompanionStreamCipher(byte[] outKey, byte[] inKey) {
            this.outKey = Arrays.copyOf(outKey, 32);
            this.inKey = Arrays.copyOf(inKey, 32);
        }

        public synchronized byte[] encrypt(byte[] plaintext, byte[] aad) {
            byte[] nonce = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(outCounter++).array();
            return chacha20Poly1305Encrypt(outKey, nonce, plaintext, aad);
        }

        public synchronized byte[] decrypt(byte[] ciphertextWithTag, byte[] aad) throws GeneralSecurityException {
            byte[] nonce = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(inCounter++).array();
            return chacha20Poly1305Decrypt(inKey, nonce, ciphertextWithTag, aad);
        }
    }

    // =========================================================================
    // Conversiones Little-Endian <-> BigInteger
    // =========================================================================

    public static BigInteger fromLittleEndian(byte[] bytes) {
        byte[] rev = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            rev[i] = bytes[bytes.length - 1 - i];
        }
        return new BigInteger(1, rev);
    }

    public static byte[] toLittleEndian(BigInteger val, int len) {
        byte[] be = val.toByteArray();
        byte[] le = new byte[len];
        for (int i = 0; i < len; i++) {
            int beIdx = be.length - 1 - i;
            if (beIdx >= 0) {
                le[i] = be[beIdx];
            }
        }
        return le;
    }
}
