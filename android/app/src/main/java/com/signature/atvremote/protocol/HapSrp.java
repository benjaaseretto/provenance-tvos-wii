package com.signature.atvremote.protocol;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Implementación completa de HAP SRP-6a (3072 bits, generador 5, SHA-512),
 * Pair-Setup (M1..M6) y Pair-Verify (M1..M4).
 * Compatible bit a bit con pyatv.auth.hap_srp y srptools.
 */
public final class HapSrp {

    private static final String PRIME_3072_HEX =
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA6" +
            "3B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245" +
            "E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7EDEE386BFB5A899FA5AE9F2411" +
            "7C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F" +
            "83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08" +
            "CA18217C32905E462E36CE3BE39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9" +
            "DE2BCBF6955817183995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D" +
            "04507A33A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7" +
            "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864D8760273" +
            "3EC86A64521F2B18177B200CBBE117577A615D6C770988C0BAD946E208E24FA074E5AB31" +
            "43DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF";

    public static final BigInteger N = new BigInteger(PRIME_3072_HEX, 16);
    public static final BigInteger G = BigInteger.valueOf(5);
    public static final int N_BYTES = 384; // 3072 bits

    private static final String USER = "Pair-Setup";

    /** Credenciales HAP persistibles en formato ltpk:ltsk:atv_id:client_id (igual que pyatv). */
    public static final class HapCredentials {
        public final byte[] ltpk;
        public final byte[] ltsk;
        public final byte[] atvId;
        public final byte[] clientId;

        public HapCredentials(byte[] ltpk, byte[] ltsk, byte[] atvId, byte[] clientId) {
            this.ltpk = ltpk;
            this.ltsk = ltsk;
            this.atvId = atvId;
            this.clientId = clientId;
        }

        public static HapCredentials parse(String serialized) {
            String[] parts = serialized.trim().split(":");
            if (parts.length != 4) {
                throw new IllegalArgumentException("Formato de credenciales inválido");
            }
            return new HapCredentials(
                    fromHex(parts[0]),
                    fromHex(parts[1]),
                    fromHex(parts[2]),
                    fromHex(parts[3]));
        }

        @Override
        public String toString() {
            return toHex(ltpk) + ":" + toHex(ltsk) + ":" + toHex(atvId) + ":" + toHex(clientId);
        }
    }

    private byte[] pairingId;
    private byte[] authPrivate; // 32B Ed25519 seed (también usado como clave privada SRP en pyatv)
    private byte[] authPublic;  // 32B Ed25519 public key
    private byte[] verifyPrivate; // 32B X25519 scalar
    private byte[] verifyPublic;  // 32B X25519 public key

    private BigInteger clientPublicA;
    private byte[] sessionKeyK;     // 64B SHA-512(S)
    private byte[] clientProofM1;   // 64B M1
    private byte[] expectedServerProofM2; // 64B M2
    private byte[] pairSetupEncryptKey;   // 32B HKDF
    private byte[] verifySharedSecret;    // 32B X25519 shared

    public HapSrp() {
        this.pairingId = UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8);
    }

    public void setPairingId(byte[] pairingId) {
        this.pairingId = pairingId;
    }

    public void initialize() {
        SecureRandom rng = new SecureRandom();
        byte[] edSeed = new byte[32];
        byte[] xPriv = new byte[32];
        rng.nextBytes(edSeed);
        rng.nextBytes(xPriv);
        initializeWithSeeds(edSeed, xPriv);
    }

    public void initializeWithSeeds(byte[] edSeed32, byte[] xPriv32) {
        this.authPrivate = Arrays.copyOf(edSeed32, 32);
        this.authPublic = Crypto25519.ed25519PublicFromSeed(this.authPrivate);
        this.verifyPrivate = Arrays.copyOf(xPriv32, 32);
        this.verifyPublic = Crypto25519.x25519PublicFromPrivate(this.verifyPrivate);
    }

    public byte[] getAuthPublic() {
        return authPublic;
    }

    public byte[] getVerifyPublic() {
        return verifyPublic;
    }

    // =========================================================================
    // Pair-Setup (SRP-6a + Ed25519)
    // =========================================================================

    /**
     * Paso 1+2 de Pair-Setup: recibe salt y B del Apple TV más el PIN de 4 dígitos,
     * devuelve {clientPublicKeyBytes, clientProofM1Bytes}.
     */
    public byte[][] step1And2(String pinCode, byte[] atvPubKeyBytes, byte[] atvSaltBytes)
            throws GeneralSecurityException {
        BigInteger k = hashToInt(intToBytes(N), padN(G));
        BigInteger a = new BigInteger(1, authPrivate);
        this.clientPublicA = G.modPow(a, N);

        BigInteger B = new BigInteger(1, atvPubKeyBytes);
        if (B.mod(N).equals(BigInteger.ZERO)) {
            throw new GeneralSecurityException("Clave pública SRP del servidor inválida (B mod N == 0)");
        }

        // u = H(PAD(A) | PAD(B))
        BigInteger u = hashToInt(padN(clientPublicA), padN(B));

        // x = H(s | H(I | ":" | P))
        byte[] userPassHash = Crypto25519.sha512(
                (USER + ":" + pinCode).getBytes(StandardCharsets.UTF_8));
        BigInteger x = hashToInt(atvSaltBytes, userPassHash);

        // v = g^x % N
        BigInteger v = G.modPow(x, N);

        // S = (B - k * v) ^ (a + u * x) % N
        BigInteger base = B.subtract(k.multiply(v)).mod(N);
        if (base.signum() < 0) {
            base = base.add(N);
        }
        BigInteger exp = a.add(u.multiply(x));
        BigInteger S = base.modPow(exp, N);

        // K = H(int_to_bytes(S))
        this.sessionKeyK = Crypto25519.sha512(intToBytes(S));

        // M1 = H(int_to_bytes(H(N) ^ H(g)) | int_to_bytes(H(U)) | s | int_to_bytes(A) | int_to_bytes(B) | K)
        BigInteger hN = hashToInt(intToBytes(N));
        BigInteger hG = hashToInt(intToBytes(G));
        BigInteger hXor = hN.xor(hG);
        BigInteger hU = hashToInt(USER.getBytes(StandardCharsets.UTF_8));

        this.clientProofM1 = Crypto25519.sha512(
                intToBytes(hXor),
                intToBytes(hU),
                atvSaltBytes,
                intToBytes(clientPublicA),
                intToBytes(B),
                sessionKeyK);

        this.expectedServerProofM2 = Crypto25519.sha512(
                intToBytes(clientPublicA),
                clientProofM1,
                sessionKeyK);

        return new byte[][] { intToBytes(clientPublicA), clientProofM1 };
    }

    /**
     * Paso 3 de Pair-Setup: verifica la prueba M2 del servidor y construye el TLV cifrado para PS-Msg05.
     */
    public byte[] step3(byte[] serverProofM2, String displayName) throws GeneralSecurityException {
        if (expectedServerProofM2 != null && serverProofM2 != null
                && !MessageDigest.isEqual(expectedServerProofM2, serverProofM2)) {
            throw new GeneralSecurityException("PIN incorrecto o prueba SRP M2 no coincide");
        }

        byte[] iosDeviceX = Crypto25519.hkdfSha512(
                "Pair-Setup-Controller-Sign-Salt",
                "Pair-Setup-Controller-Sign-Info",
                sessionKeyK,
                32);

        this.pairSetupEncryptKey = Crypto25519.hkdfSha512(
                "Pair-Setup-Encrypt-Salt",
                "Pair-Setup-Encrypt-Info",
                sessionKeyK,
                32);

        byte[] deviceInfo = concat(iosDeviceX, pairingId, authPublic);
        byte[] signature = Crypto25519.ed25519Sign(authPrivate, deviceInfo);

        Map<Integer, byte[]> tlv = new LinkedHashMap<>();
        tlv.put(HapTlv8.TAG_IDENTIFIER, pairingId);
        tlv.put(HapTlv8.TAG_PUBLIC_KEY, authPublic);
        tlv.put(HapTlv8.TAG_SIGNATURE, signature);
        if (displayName != null && !displayName.isEmpty()) {
            Map<String, Object> nameMap = new LinkedHashMap<>();
            nameMap.put("name", displayName);
            tlv.put(HapTlv8.TAG_NAME, Opack.pack(nameMap));
        }

        byte[] tlvBytes = HapTlv8.writeTlv(tlv);
        return Crypto25519.chacha20Poly1305Encrypt(
                pairSetupEncryptKey,
                "PS-Msg05".getBytes(StandardCharsets.UTF_8),
                tlvBytes,
                null);
    }

    /**
     * Paso 4 de Pair-Setup: descifra PS-Msg06 y devuelve las credenciales permanentes HapCredentials.
     */
    public HapCredentials step4(byte[] encryptedData) throws GeneralSecurityException {
        byte[] decrypted = Crypto25519.chacha20Poly1305Decrypt(
                pairSetupEncryptKey,
                "PS-Msg06".getBytes(StandardCharsets.UTF_8),
                encryptedData,
                null);
        Map<Integer, byte[]> tlv = HapTlv8.readTlv(decrypted);
        byte[] atvIdentifier = tlv.get(HapTlv8.TAG_IDENTIFIER);
        byte[] atvPubKey = tlv.get(HapTlv8.TAG_PUBLIC_KEY);
        if (atvIdentifier == null || atvPubKey == null) {
            throw new GeneralSecurityException("Faltan campos de identidad en PS-Msg06");
        }
        return new HapCredentials(atvPubKey, authPrivate, atvIdentifier, pairingId);
    }

    // =========================================================================
    // Pair-Verify (X25519 + Ed25519)
    // =========================================================================

    /**
     * Paso 1 de Pair-Verify: procesa PV-Msg02 del Apple TV, verifica su firma Ed25519
     * y devuelve los datos cifrados para PV-Msg03.
     */
    public byte[] verify1(HapCredentials creds, byte[] serverSessionPubKey32, byte[] encryptedPvMsg02)
            throws GeneralSecurityException {
        this.verifySharedSecret = Crypto25519.x25519ScalarMult(verifyPrivate, serverSessionPubKey32);

        byte[] sessionKey = Crypto25519.hkdfSha512(
                "Pair-Verify-Encrypt-Salt",
                "Pair-Verify-Encrypt-Info",
                verifySharedSecret,
                32);

        byte[] decrypted = Crypto25519.chacha20Poly1305Decrypt(
                sessionKey,
                "PV-Msg02".getBytes(StandardCharsets.UTF_8),
                encryptedPvMsg02,
                null);

        Map<Integer, byte[]> tlv = HapTlv8.readTlv(decrypted);
        byte[] identifier = tlv.get(HapTlv8.TAG_IDENTIFIER);
        byte[] signature = tlv.get(HapTlv8.TAG_SIGNATURE);
        if (identifier == null || signature == null) {
            throw new GeneralSecurityException("PV-Msg02 incompleto");
        }
        if (!Arrays.equals(identifier, creds.atvId)) {
            throw new GeneralSecurityException("El identificador del Apple TV no coincide con las credenciales");
        }

        byte[] serverInfo = concat(serverSessionPubKey32, identifier, verifyPublic);
        if (!Crypto25519.ed25519Verify(creds.ltpk, serverInfo, signature)) {
            throw new GeneralSecurityException("Firma Ed25519 del Apple TV inválida en Pair-Verify");
        }

        byte[] clientInfo = concat(verifyPublic, creds.clientId, serverSessionPubKey32);
        byte[] clientSig = Crypto25519.ed25519Sign(creds.ltsk, clientInfo);

        Map<Integer, byte[]> respTlv = new LinkedHashMap<>();
        respTlv.put(HapTlv8.TAG_IDENTIFIER, creds.clientId);
        respTlv.put(HapTlv8.TAG_SIGNATURE, clientSig);

        return Crypto25519.chacha20Poly1305Encrypt(
                sessionKey,
                "PV-Msg03".getBytes(StandardCharsets.UTF_8),
                HapTlv8.writeTlv(respTlv),
                null);
    }

    /**
     * Paso 2 de Pair-Verify: deriva las claves simétricas del flujo principal Companion
     * {outputKey (ClientEncrypt-main), inputKey (ServerEncrypt-main)}.
     */
    public byte[][] deriveMainStreamKeys() {
        byte[] outKey = Crypto25519.hkdfSha512("", "ClientEncrypt-main", verifySharedSecret, 32);
        byte[] inKey = Crypto25519.hkdfSha512("", "ServerEncrypt-main", verifySharedSecret, 32);
        return new byte[][] { outKey, inKey };
    }

    /**
     * Deriva las claves del flujo principal Companion a partir de una sesión Transient Pair-Setup
     * (M1..M4 con PIN fijo 3939 y flag 0x10).
     */
    public byte[][] deriveTransientStreamKeys(byte[] serverProofM2) throws GeneralSecurityException {
        if (expectedServerProofM2 != null && serverProofM2 != null
                && !MessageDigest.isEqual(expectedServerProofM2, serverProofM2)) {
            throw new GeneralSecurityException("Prueba SRP M2 transient inválida");
        }
        byte[] outKey = Crypto25519.hkdfSha512("", "ClientEncrypt-main", sessionKeyK, 32);
        byte[] inKey = Crypto25519.hkdfSha512("", "ServerEncrypt-main", sessionKeyK, 32);
        return new byte[][] { outKey, inKey };
    }

    public String getPairingIdString() {
        return new String(pairingId, StandardCharsets.UTF_8);
    }

    // =========================================================================
    // Utilidades de codificación entera idénticas a srptools
    // =========================================================================

    /**
     * Equivale a srptools.utils.int_to_bytes: representación big-endian sin byte de signo 0x00
     * (salvo para el valor 0, que devuelve un único byte 0x00).
     */
    public static byte[] intToBytes(BigInteger val) {
        if (val.signum() == 0) {
            return new byte[] { 0 };
        }
        byte[] raw = val.toByteArray();
        if (raw[0] == 0 && raw.length > 1) {
            return Arrays.copyOfRange(raw, 1, raw.length);
        }
        return raw;
    }

    public static byte[] padN(BigInteger val) {
        byte[] b = intToBytes(val);
        if (b.length == N_BYTES) {
            return b;
        }
        if (b.length > N_BYTES) {
            return Arrays.copyOfRange(b, b.length - N_BYTES, b.length);
        }
        byte[] padded = new byte[N_BYTES];
        System.arraycopy(b, 0, padded, N_BYTES - b.length, b.length);
        return padded;
    }

    private static BigInteger hashToInt(byte[]... chunks) {
        return new BigInteger(1, Crypto25519.sha512(chunks));
    }

    private static byte[] concat(byte[]... arrays) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] a : arrays) {
            out.write(a, 0, a.length);
        }
        return out.toByteArray();
    }

    public static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    public static byte[] fromHex(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return out;
    }
}
