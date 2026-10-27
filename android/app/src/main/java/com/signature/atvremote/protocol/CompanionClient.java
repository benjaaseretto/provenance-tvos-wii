package com.signature.atvremote.protocol;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Cliente TCP nativo para el protocolo Apple TV Companion (_companion-link._tcp, puerto 49153).
 * Implementa Pair-Setup (PIN en pantalla), Pair-Verify, cifrado ChaCha20-Poly1305 continuo,
 * comandos HID (_hidC), gestos táctiles (_hidT), control multimedia (_mcc), teclado (_tiC)
 * y lanzamiento/listado de aplicaciones (_launchApp / FetchLaunchableApplicationsEvent).
 */
public final class CompanionClient {

    public static final int DEFAULT_PORT = 49153;

    // Tipos de trama Companion
    public static final int FRAME_NOOP = 1;
    public static final int FRAME_PS_START = 3;
    public static final int FRAME_PS_NEXT = 4;
    public static final int FRAME_PV_START = 5;
    public static final int FRAME_PV_NEXT = 6;
    public static final int FRAME_U_OPACK = 7;
    public static final int FRAME_E_OPACK = 8;
    public static final int FRAME_P_OPACK = 9;

    // Tipos de mensaje OPACK (_t)
    public static final int MSG_EVENT = 1;
    public static final int MSG_REQUEST = 2;
    public static final int MSG_RESPONSE = 3;

    // Códigos HID (_hidC)
    public static final int HID_UP = 1;
    public static final int HID_DOWN = 2;
    public static final int HID_LEFT = 3;
    public static final int HID_RIGHT = 4;
    public static final int HID_MENU = 5;
    public static final int HID_SELECT = 6;
    public static final int HID_HOME = 7;
    public static final int HID_VOLUME_UP = 8;
    public static final int HID_VOLUME_DOWN = 9;
    public static final int HID_SIRI = 10;
    public static final int HID_SCREENSAVER = 11;
    public static final int HID_SLEEP = 12;
    public static final int HID_WAKE = 13;
    public static final int HID_PLAY_PAUSE = 14;
    public static final int HID_CHANNEL_INC = 15;
    public static final int HID_CHANNEL_DEC = 16;
    public static final int HID_GUIDE = 17;
    public static final int HID_PAGE_UP = 18;
    public static final int HID_PAGE_DOWN = 19;

    // Fases táctiles (_hidT _tPh)
    public static final int TOUCH_PRESS = 1;
    public static final int TOUCH_HOLD = 3;
    public static final int TOUCH_RELEASE = 4;
    public static final int TOUCH_CLICK = 5;

    // Comandos MediaControl (_mcc)
    public static final int MCC_PLAY = 1;
    public static final int MCC_PAUSE = 2;
    public static final int MCC_NEXT_TRACK = 3;
    public static final int MCC_PREV_TRACK = 4;
    public static final int MCC_GET_VOLUME = 5;
    public static final int MCC_SET_VOLUME = 6;
    public static final int MCC_SKIP_BY = 7;

    public interface Listener {
        void onStateChanged(boolean connected, String statusText, long rttMs);
        void onEventReceived(String eventName, Map<String, Object> content);
        void onError(String message);
    }

    private final Listener listener;
    private final ExecutorService ioPool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CompanionIO");
        t.setDaemon(true);
        return t;
    });

    private volatile Socket socket;
    private volatile OutputStream outStream;
    private volatile Crypto25519.CompanionStreamCipher cipher;
    private volatile boolean sessionReady = false;
    private volatile String currentHost = "192.168.1.10";
    private volatile int currentPort = DEFAULT_PORT;
    private volatile long lastRttMs = -1;

    private final AtomicInteger nextXid = new AtomicInteger(new Random().nextInt(30000) + 100);
    private final Map<Integer, CompletableFuture<Map<String, Object>>> pendingXids = new ConcurrentHashMap<>();
    private final Map<Integer, CompletableFuture<Map<String, Object>>> pendingAuthFrames = new ConcurrentHashMap<>();

    // Estado de emparejamiento en curso
    private HapSrp pairingSrp;
    private byte[] pairingSalt;
    private byte[] pairingAtvPubB;

    // Estado de sesión activa
    private long sessionSid = 0;
    private long touchBaseNs = System.nanoTime();

    public CompanionClient(Listener listener) {
        this.listener = listener;
    }

    public boolean isConnected() {
        return sessionReady && socket != null && socket.isConnected() && !socket.isClosed();
    }

    public String getHost() {
        return currentHost;
    }

    public int getPort() {
        return currentPort;
    }

    public long getLastRttMs() {
        return lastRttMs;
    }

    // =========================================================================
    // 1. Emparejamiento (Pair-Setup): muestra el PIN de 4 dígitos en el Apple TV
    // =========================================================================

    public void startPairingAsync(String host, int port, Runnable onPinShownOnTv) {
        ioPool.execute(() -> {
            this.currentHost = host;
            this.currentPort = port;
            for (int attempt = 1; attempt <= 2; attempt++) {
                try {
                    closeSocketQuietly();
                    notifyState(false, "Mostrando código PIN en Apple TV Sala…", -1);

                    long t0 = System.nanoTime();
                    openTcpAndReader(host, port);
                    this.lastRttMs = (System.nanoTime() - t0) / 1_000_000L;

                    pairingSrp = new HapSrp();
                    pairingSrp.initialize();

                    Map<Integer, byte[]> tlv = new LinkedHashMap<>();
                    tlv.put(HapTlv8.TAG_METHOD, new byte[] { 0x00 });
                    tlv.put(HapTlv8.TAG_SEQ_NO, new byte[] { 0x01 });

                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("_pd", HapTlv8.writeTlv(tlv));
                    payload.put("_pwTy", 1);

                    Map<String, Object> resp = exchangeAuthFrame(FRAME_PS_START, FRAME_PS_NEXT, payload, 8000);
                    Map<Integer, byte[]> respTlv = extractPairingTlv(resp);
                    pairingSalt = respTlv.get(HapTlv8.TAG_SALT);
                    pairingAtvPubB = respTlv.get(HapTlv8.TAG_PUBLIC_KEY);
                    if (pairingSalt == null || pairingAtvPubB == null) {
                        throw new IOException("Respuesta de emparejamiento incompleta");
                    }

                    notifyState(false, "✓ PIN activo en TV • Ingresá los 4 dígitos", lastRttMs);
                    if (onPinShownOnTv != null) {
                        onPinShownOnTv.run();
                    }
                    return;
                } catch (Exception e) {
                    closeSocketQuietly();
                    if (attempt == 1 && e.getMessage() != null
                            && (e.getMessage().contains("código 3") || e.getMessage().contains("código 7"))) {
                        notifyState(false, "Esperando disponibilidad de Apple TV Sala…", -1);
                        try {
                            Thread.sleep(3500);
                        } catch (InterruptedException ignored) {
                        }
                        continue;
                    }
                    notifyError("Error iniciando emparejamiento: " + e.getMessage());
                    return;
                }
            }
        });
    }

    public interface PairingCallback {
        void onPaired(HapSrp.HapCredentials credentials);
    }

    public void finishPairingAsync(String pin4, PairingCallback callback) {
        ioPool.execute(() -> {
            try {
                if (pairingSrp == null || pairingSalt == null || pairingAtvPubB == null) {
                    throw new IllegalStateException("Primero iniciá el emparejamiento para ver el PIN en la tele");
                }
                String cleanPin = pin4.trim();
                if (cleanPin.length() < 4) {
                    cleanPin = String.format("%04d", Integer.parseInt(cleanPin));
                }

                byte[][] step2 = pairingSrp.step1And2(cleanPin, pairingAtvPubB, pairingSalt);

                Map<Integer, byte[]> tlv3 = new LinkedHashMap<>();
                tlv3.put(HapTlv8.TAG_SEQ_NO, new byte[] { 0x03 });
                tlv3.put(HapTlv8.TAG_PUBLIC_KEY, step2[0]);
                tlv3.put(HapTlv8.TAG_PROOF, step2[1]);

                Map<String, Object> req3 = new LinkedHashMap<>();
                req3.put("_pd", HapTlv8.writeTlv(tlv3));
                req3.put("_pwTy", 1);

                Map<String, Object> resp4 = exchangeAuthFrame(FRAME_PS_NEXT, FRAME_PS_NEXT, req3, 8000);
                Map<Integer, byte[]> tlv4 = extractPairingTlv(resp4);
                byte[] serverProofM2 = tlv4.get(HapTlv8.TAG_PROOF);

                byte[] encryptedMsg05 = pairingSrp.step3(serverProofM2, "Motorola Signature");

                Map<Integer, byte[]> tlv5 = new LinkedHashMap<>();
                tlv5.put(HapTlv8.TAG_SEQ_NO, new byte[] { 0x05 });
                tlv5.put(HapTlv8.TAG_ENCRYPTED_DATA, encryptedMsg05);

                Map<String, Object> req5 = new LinkedHashMap<>();
                req5.put("_pd", HapTlv8.writeTlv(tlv5));
                req5.put("_pwTy", 1);

                Map<String, Object> resp6 = exchangeAuthFrame(FRAME_PS_NEXT, FRAME_PS_NEXT, req5, 8000);
                Map<Integer, byte[]> tlv6 = extractPairingTlv(resp6);
                byte[] encryptedMsg06 = tlv6.get(HapTlv8.TAG_ENCRYPTED_DATA);

                HapSrp.HapCredentials creds = pairingSrp.step4(encryptedMsg06);
                closeSocketQuietly();

                if (callback != null) {
                    callback.onPaired(creds);
                }
                // Conectar inmediatamente con las nuevas credenciales
                connectWithCredentialsInternal(currentHost, currentPort, creds);
            } catch (Exception e) {
                closeSocketQuietly();
                notifyError("Error verificando PIN: " + e.getMessage());
            }
        });
    }

    // =========================================================================
    // 2. Conexión cifrada y sesión de control remoto (Pair-Verify + Session)
    // =========================================================================

    public void connectAsync(String host, int port, HapSrp.HapCredentials creds) {
        ioPool.execute(() -> connectWithCredentialsInternal(host, port, creds));
    }

    private void connectWithCredentialsInternal(String host, int port, HapSrp.HapCredentials creds) {
        try {
            closeSocketQuietly();
            this.currentHost = host;
            this.currentPort = port;
            notifyState(false, "Conectando a Apple TV Sala…", -1);

            long t0 = System.nanoTime();
            openTcpAndReader(host, port);
            long tcpRtt = (System.nanoTime() - t0) / 1_000_000L;
            this.lastRttMs = tcpRtt;

            HapSrp srp = new HapSrp();
            srp.setPairingId(creds.clientId);
            srp.initialize();

            // PV_Start (M1)
            Map<Integer, byte[]> pv1Tlv = new LinkedHashMap<>();
            pv1Tlv.put(HapTlv8.TAG_SEQ_NO, new byte[] { 0x01 });
            pv1Tlv.put(HapTlv8.TAG_PUBLIC_KEY, srp.getVerifyPublic());

            Map<String, Object> pv1Req = new LinkedHashMap<>();
            pv1Req.put("_pd", HapTlv8.writeTlv(pv1Tlv));
            pv1Req.put("_auTy", 4);

            Map<String, Object> pv2Resp = exchangeAuthFrame(FRAME_PV_START, FRAME_PV_NEXT, pv1Req, 6000);
            Map<Integer, byte[]> pv2Tlv = extractPairingTlv(pv2Resp);
            byte[] serverVerifyPub = pv2Tlv.get(HapTlv8.TAG_PUBLIC_KEY);
            byte[] encryptedPvMsg02 = pv2Tlv.get(HapTlv8.TAG_ENCRYPTED_DATA);

            // PV_Next (M3)
            byte[] encryptedPvMsg03 = srp.verify1(creds, serverVerifyPub, encryptedPvMsg02);
            Map<Integer, byte[]> pv3Tlv = new LinkedHashMap<>();
            pv3Tlv.put(HapTlv8.TAG_SEQ_NO, new byte[] { 0x03 });
            pv3Tlv.put(HapTlv8.TAG_ENCRYPTED_DATA, encryptedPvMsg03);

            Map<String, Object> pv3Req = new LinkedHashMap<>();
            pv3Req.put("_pd", HapTlv8.writeTlv(pv3Tlv));

            exchangeAuthFrame(FRAME_PV_NEXT, FRAME_PV_NEXT, pv3Req, 6000);

            // Activar cifrado ChaCha20-Poly1305 continuo para todas las tramas siguientes
            byte[][] keys = srp.deriveMainStreamKeys();
            this.cipher = new Crypto25519.CompanionStreamCipher(keys[0], keys[1]);

            completeCompanionSessionHandshake(new String(creds.clientId, StandardCharsets.UTF_8));
        } catch (Exception e) {
            closeSocketQuietly();
            notifyState(false, "Desconectado (" + e.getMessage() + ")", -1);
            notifyError("No se pudo iniciar sesión Companion: " + e.getMessage());
        }
    }

    private void completeCompanionSessionHandshake(String clientIdStr) throws Exception {
        Map<String, Object> sysInfo = new LinkedHashMap<>();
        sysInfo.put("_bf", 0);
        sysInfo.put("_cf", 512);
        sysInfo.put("_clFl", 128);
        sysInfo.put("_i", "a1b2c3d4e5f6");
        sysInfo.put("_idsID", clientIdStr);
        sysInfo.put("_pubID", "00:11:22:33:44:55");
        sysInfo.put("_sf", 256);
        sysInfo.put("_sv", "170.18");
        sysInfo.put("model", "Motorola Signature");
        sysInfo.put("name", "Motorola Signature");
        sendCommandSync("_systemInfo", sysInfo, 5000);

        this.touchBaseNs = System.nanoTime();
        Map<String, Object> touchStart = new LinkedHashMap<>();
        touchStart.put("_height", 1000.0);
        touchStart.put("_tFl", 0);
        touchStart.put("_width", 1000.0);
        sendCommandSync("_touchStart", touchStart, 5000);

        long localSid = new Random().nextInt(Integer.MAX_VALUE);
        Map<String, Object> sessStart = new LinkedHashMap<>();
        sessStart.put("_srvT", "com.apple.tvremoteservices");
        sessStart.put("_sid", localSid);
        Map<String, Object> sessResp = sendCommandSync("_sessionStart", sessStart, 5000);
        Object cObj = sessResp.get("_c");
        if (cObj instanceof Map) {
            Object rSid = ((Map<?, ?>) cObj).get("_sid");
            if (rSid instanceof Number) {
                this.sessionSid = (((Number) rSid).longValue() << 32) | localSid;
            }
        }

        try {
            Map<String, Object> tvrc = new LinkedHashMap<>();
            tvrc.put("ProtocolVersionKey", "1.2");
            sendCommandSync("TVRCSessionStart", tvrc, 3000);
        } catch (Exception ignored) {
        }

        try {
            sendCommandSync("_tiStart", new LinkedHashMap<>(), 3000);
        } catch (Exception ignored) {
        }

        sendEventInternal("_interest", Collections.singletonMap("_regEvents", Collections.singletonList("_iMC")));

        this.sessionReady = true;
        notifyState(true, "✓ Conectado a Apple TV Sala", lastRttMs);
    }

    public void disconnectAsync() {
        ioPool.execute(this::closeSocketQuietly);
    }

    // =========================================================================
    // 3. Comandos de Control Remoto, Wii Gamepad, Teclado y Aplicaciones
    // =========================================================================

    /**
     * Envía el estado de un botón HID (_hBtS: 1 = presionado, 2 = soltado).
     * Permite mantener presionadas las direcciones o botones en juegos de Wii (Provenance) y tvOS.
     */
    public void sendHidButtonState(int hidCommand, boolean down) {
        ioPool.execute(() -> {
            if (!isConnected()) return;
            try {
                long t0 = System.nanoTime();
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("_hBtS", down ? 1 : 2);
                c.put("_hidC", hidCommand);
                sendCommandSync("_hidC", c, 3000);
                this.lastRttMs = (System.nanoTime() - t0) / 1_000_000L;
            } catch (Exception e) {
                notifyError("Error enviando comando HID: " + e.getMessage());
            }
        });
    }

    /**
     * Pulsa y suelta un botón HID con duración controlada.
     */
    public void pressHidButton(int hidCommand, int holdMs) {
        ioPool.execute(() -> {
            if (!isConnected()) return;
            try {
                long t0 = System.nanoTime();
                Map<String, Object> down = new LinkedHashMap<>();
                down.put("_hBtS", 1);
                down.put("_hidC", hidCommand);
                sendCommandSync("_hidC", down, 3000);
                if (holdMs > 0) {
                    Thread.sleep(holdMs);
                }
                Map<String, Object> up = new LinkedHashMap<>();
                up.put("_hBtS", 2);
                up.put("_hidC", hidCommand);
                sendCommandSync("_hidC", up, 3000);
                this.lastRttMs = (System.nanoTime() - t0) / 1_000_000L;
                notifyState(true, "✓ Conectado a Apple TV Sala", lastRttMs);
            } catch (Exception e) {
                notifyError("Error en botón HID: " + e.getMessage());
            }
        });
    }

    /**
     * Envía un evento táctil de superficie Siri Remote (coordenadas 0..1000).
     */
    public void sendTouchEvent(int x1000, int y1000, int touchPhase) {
        ioPool.execute(() -> {
            if (!isConnected()) return;
            try {
                int cx = Math.max(0, Math.min(1000, x1000));
                int cy = Math.max(0, Math.min(1000, y1000));
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("_ns", System.nanoTime() - touchBaseNs);
                c.put("_tFg", 1);
                c.put("_cx", cx);
                c.put("_tPh", touchPhase);
                c.put("_cy", cy);
                sendEventInternal("_hidT", c);
            } catch (Exception ignored) {
            }
        });
    }

    /**
     * Envía comando MediaControl (_mcc), por ejemplo saltar +/- 10s, reproducir, pausar.
     */
    public void sendMediaControl(int mccCommand, Map<String, Object> extraArgs) {
        ioPool.execute(() -> {
            if (!isConnected()) return;
            try {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("_mcc", mccCommand);
                if (extraArgs != null) {
                    c.putAll(extraArgs);
                }
                sendCommandSync("_mcc", c, 3000);
            } catch (Exception e) {
                notifyError("Error MediaControl: " + e.getMessage());
            }
        });
    }

    /**
     * Abre una aplicación por bundle identifier o URL scheme en el Apple TV.
     */
    public void launchApp(String bundleIdOrUrl, Runnable onSuccess) {
        ioPool.execute(() -> {
            if (!isConnected()) {
                notifyError("No conectado al Apple TV");
                return;
            }
            try {
                String key = bundleIdOrUrl.contains("://") ? "_urlS" : "_bundleID";
                Map<String, Object> c = new LinkedHashMap<>();
                c.put(key, bundleIdOrUrl);
                sendCommandSync("_launchApp", c, 5000);
                if (onSuccess != null) onSuccess.run();
            } catch (Exception e) {
                notifyError("No se pudo abrir " + bundleIdOrUrl + ": " + e.getMessage());
            }
        });
    }

    public interface AppListCallback {
        void onAppsLoaded(Map<String, String> bundleToName);
    }

    /**
     * Obtiene la lista de aplicaciones instaladas y lanzables en el Apple TV.
     */
    @SuppressWarnings("unchecked")
    public void fetchLaunchableApps(AppListCallback callback) {
        ioPool.execute(() -> {
            if (!isConnected()) return;
            try {
                Map<String, Object> resp = sendCommandSync("FetchLaunchableApplicationsEvent", new LinkedHashMap<>(), 5000);
                Object c = resp.get("_c");
                Map<String, String> apps = new LinkedHashMap<>();
                if (c instanceof Map) {
                    for (Map.Entry<?, ?> entry : ((Map<?, ?>) c).entrySet()) {
                        if (entry.getKey() instanceof String && entry.getValue() instanceof String) {
                            apps.put((String) entry.getKey(), (String) entry.getValue());
                        }
                    }
                }
                if (callback != null) {
                    callback.onAppsLoaded(apps);
                }
            } catch (Exception e) {
                notifyError("Error listando apps: " + e.getMessage());
            }
        });
    }

    /**
     * Activa Siri / Búsqueda en el Apple TV:
     * 1. Envía el comando nativo HID_SIRI (_hidC = 10).
     * 2. Si el Apple TV no tiene ya un campo de búsqueda activo (por ejemplo cuando Siri por voz
     *    está desactivado o en modo Buscar en tvOS), abre automáticamente com.apple.TVSearch
     *    para que la pantalla de búsqueda/dictado aparezca de inmediato en la TV.
     */
    @SuppressWarnings("unchecked")
    public void activateSiriOrSearch(Runnable onReady) {
        ioPool.execute(() -> {
            if (!isConnected()) {
                notifyError("No conectado al Apple TV");
                return;
            }
            try {
                long t0 = System.nanoTime();
                Map<String, Object> down = new LinkedHashMap<>();
                down.put("_hBtS", 1);
                down.put("_hidC", HID_SIRI);
                sendCommandSync("_hidC", down, 2500);
                Thread.sleep(120);
                Map<String, Object> up = new LinkedHashMap<>();
                up.put("_hBtS", 2);
                up.put("_hidC", HID_SIRI);
                sendCommandSync("_hidC", up, 2500);
                this.lastRttMs = (System.nanoTime() - t0) / 1_000_000L;

                // Verificar si se activó algún campo de entrada; si no, abrir Buscar (TVSearch)
                Map<String, Object> tiResp = sendCommandSync("_tiStart", new LinkedHashMap<>(), 2500);
                Object cObj = tiResp.get("_c");
                boolean hasTextFocus = (cObj instanceof Map) && (((Map<String, Object>) cObj).get("_tiD") instanceof byte[]);
                if (!hasTextFocus) {
                    Map<String, Object> launch = new LinkedHashMap<>();
                    launch.put("_bundleID", "com.apple.TVSearch");
                    sendCommandSync("_launchApp", launch, 4000);
                }
                notifyState(true, "✓ Siri / Buscar activo en Sala", lastRttMs);
                if (onReady != null) {
                    onReady.run();
                }
            } catch (Exception e) {
                notifyError("Error activando Siri/Buscar: " + e.getMessage());
            }
        });
    }

    /**
     * Escribe texto en el campo de texto activo del Apple TV usando RTIKeyedArchiver (_tiStart / _tiC).
     * Si no hay ningún campo de texto activo en pantalla, abre automáticamente Buscar (com.apple.TVSearch)
     * en el Apple TV, espera el foco del teclado e inyecta el texto.
     */
    @SuppressWarnings("unchecked")
    public void sendTextInput(String text, boolean clearPrevious) {
        ioPool.execute(() -> {
            if (!isConnected()) return;
            try {
                byte[] tiData = fetchActiveTextInputData();
                if (tiData == null) {
                    // Abrir automáticamente Buscar en el Apple TV para poder escribir/dictar
                    Map<String, Object> launch = new LinkedHashMap<>();
                    launch.put("_bundleID", "com.apple.TVSearch");
                    sendCommandSync("_launchApp", launch, 4000);

                    for (int attempt = 0; attempt < 7; attempt++) {
                        Thread.sleep(350);
                        tiData = fetchActiveTextInputData();
                        if (tiData != null) {
                            break;
                        }
                        if (attempt == 3) {
                            Map<String, Object> selDown = new LinkedHashMap<>();
                            selDown.put("_hBtS", 1);
                            selDown.put("_hidC", HID_SELECT);
                            sendCommandSync("_hidC", selDown, 2000);
                            Thread.sleep(40);
                            Map<String, Object> selUp = new LinkedHashMap<>();
                            selUp.put("_hBtS", 2);
                            selUp.put("_hidC", HID_SELECT);
                            sendCommandSync("_hidC", selUp, 2000);
                        }
                    }
                }
                if (tiData == null) {
                    notifyError("Abrí un cuadro de búsqueda en el Apple TV para dictar o escribir");
                    return;
                }
                byte[] sessionUuid = RtiPayload.extractSessionUuid(tiData);
                if (sessionUuid == null || sessionUuid.length != 16) {
                    notifyError("No se pudo obtener sessionUUID del campo de texto");
                    return;
                }
                if (clearPrevious) {
                    Map<String, Object> clearEv = new LinkedHashMap<>();
                    clearEv.put("_tiV", 1);
                    clearEv.put("_tiD", RtiPayload.buildClearTextPayload(sessionUuid));
                    sendEventInternal("_tiC", clearEv);
                }
                if (text != null && !text.isEmpty()) {
                    Map<String, Object> inputEv = new LinkedHashMap<>();
                    inputEv.put("_tiV", 1);
                    inputEv.put("_tiD", RtiPayload.buildInputTextPayload(sessionUuid, text));
                    sendEventInternal("_tiC", inputEv);
                }
            } catch (Exception e) {
                notifyError("Error enviando texto: " + e.getMessage());
            }
        });
    }

    @SuppressWarnings("unchecked")
    private byte[] fetchActiveTextInputData() throws Exception {
        sendCommandSync("_tiStop", new LinkedHashMap<>(), 2500);
        Map<String, Object> startResp = sendCommandSync("_tiStart", new LinkedHashMap<>(), 2500);
        Object cObj = startResp.get("_c");
        if (cObj instanceof Map) {
            Object tiDataObj = ((Map<String, Object>) cObj).get("_tiD");
            if (tiDataObj instanceof byte[]) {
                return (byte[]) tiDataObj;
            }
        }
        return null;
    }

    // =========================================================================
    // Transporte de bajo nivel y lectura de tramas
    // =========================================================================

    private void openTcpAndReader(String host, int port) throws IOException {
        Socket s = new Socket();
        s.setTcpNoDelay(true);
        s.setKeepAlive(true);
        s.connect(new InetSocketAddress(host, port), 4000);
        this.socket = s;
        this.outStream = s.getOutputStream();
        this.cipher = null;
        this.sessionReady = false;

        Thread reader = new Thread(() -> readLoop(s), "CompanionReader");
        reader.setDaemon(true);
        reader.start();
    }

    @SuppressWarnings("unchecked")
    private void readLoop(Socket s) {
        try {
            DataInputStream in = new DataInputStream(s.getInputStream());
            byte[] header = new byte[4];
            while (!s.isClosed()) {
                in.readFully(header);
                int frameType = header[0] & 0xFF;
                int payloadLen = ((header[1] & 0xFF) << 16)
                        | ((header[2] & 0xFF) << 8)
                        | (header[3] & 0xFF);
                byte[] payload = new byte[payloadLen];
                if (payloadLen > 0) {
                    in.readFully(payload);
                }

                Crypto25519.CompanionStreamCipher activeCipher = this.cipher;
                if (activeCipher != null && payloadLen > 0) {
                    payload = activeCipher.decrypt(payload, header);
                }

                if (payload.length == 0) {
                    continue;
                }

                Object unpacked = Opack.unpack(payload);
                if (!(unpacked instanceof Map)) {
                    continue;
                }
                Map<String, Object> map = (Map<String, Object>) unpacked;

                if (frameType == FRAME_PS_START || frameType == FRAME_PS_NEXT
                        || frameType == FRAME_PV_START || frameType == FRAME_PV_NEXT) {
                    CompletableFuture<Map<String, Object>> f = pendingAuthFrames.remove(frameType);
                    if (f != null) {
                        f.complete(map);
                    }
                } else if (frameType == FRAME_E_OPACK || frameType == FRAME_U_OPACK || frameType == FRAME_P_OPACK) {
                    Object tObj = map.get("_t");
                    int msgType = (tObj instanceof Number) ? ((Number) tObj).intValue() : 0;
                    if (msgType == MSG_RESPONSE) {
                        Object xObj = map.get("_x");
                        if (xObj instanceof Number) {
                            CompletableFuture<Map<String, Object>> f = pendingXids.remove(((Number) xObj).intValue());
                            if (f != null) {
                                f.complete(map);
                            }
                        }
                    } else if (msgType == MSG_EVENT && listener != null) {
                        Object iObj = map.get("_i");
                        Object cObj = map.get("_c");
                        if (iObj instanceof String && cObj instanceof Map) {
                            listener.onEventReceived((String) iObj, (Map<String, Object>) cObj);
                        }
                    }
                }
            }
        } catch (Exception e) {
            if (sessionReady) {
                sessionReady = false;
                notifyState(false, "Conexión cerrada", -1);
            }
        }
    }

    private Map<String, Object> exchangeAuthFrame(
            int sendFrameType, int waitFrameType, Map<String, Object> data, long timeoutMs) throws Exception {
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        pendingAuthFrames.put(waitFrameType, future);
        sendOpackFrame(sendFrameType, data);
        Map<String, Object> resp = future.get(timeoutMs, TimeUnit.MILLISECONDS);
        if (resp.containsKey("_em")) {
            throw new IOException("Error del Apple TV: " + resp.get("_em"));
        }
        return resp;
    }

    private Map<String, Object> sendCommandSync(String identifier, Map<String, Object> content, long timeoutMs)
            throws Exception {
        int xid = nextXid.getAndIncrement();
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("_i", identifier);
        msg.put("_t", MSG_REQUEST);
        msg.put("_c", content);
        msg.put("_x", xid);

        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        pendingXids.put(xid, future);
        sendOpackFrame(FRAME_E_OPACK, msg);
        Map<String, Object> resp = future.get(timeoutMs, TimeUnit.MILLISECONDS);
        if (resp.containsKey("_em")) {
            throw new IOException(String.valueOf(resp.get("_em")));
        }
        return resp;
    }

    private void sendEventInternal(String identifier, Map<String, Object> content) throws IOException {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("_i", identifier);
        msg.put("_t", MSG_EVENT);
        msg.put("_c", content);
        msg.put("_x", nextXid.getAndIncrement());
        sendOpackFrame(FRAME_E_OPACK, msg);
    }

    private synchronized void sendOpackFrame(int frameType, Map<String, Object> data) throws IOException {
        if (!data.containsKey("_x")) {
            data.put("_x", nextXid.getAndIncrement());
        }
        byte[] raw = Opack.pack(data);
        int payloadLen = raw.length;
        Crypto25519.CompanionStreamCipher activeCipher = this.cipher;
        if (activeCipher != null && payloadLen > 0) {
            payloadLen += 16; // Poly1305 tag
        }
        byte[] header = new byte[] {
            (byte) frameType,
            (byte) ((payloadLen >>> 16) & 0xFF),
            (byte) ((payloadLen >>> 8) & 0xFF),
            (byte) (payloadLen & 0xFF)
        };
        byte[] body = (activeCipher != null && raw.length > 0)
                ? activeCipher.encrypt(raw, header)
                : raw;

        OutputStream os = this.outStream;
        if (os == null) {
            throw new IOException("Socket cerrado");
        }
        os.write(header);
        os.write(body);
        os.flush();
    }

    private static Map<Integer, byte[]> extractPairingTlv(Map<String, Object> resp)
            throws GeneralSecurityException {
        Object pd = resp.get("_pd");
        if (!(pd instanceof byte[])) {
            throw new GeneralSecurityException("Respuesta sin datos _pd TLV");
        }
        Map<Integer, byte[]> tlv = HapTlv8.readTlv((byte[]) pd);
        if (tlv.containsKey(HapTlv8.TAG_ERROR)) {
            int errCode = tlv.get(HapTlv8.TAG_ERROR)[0] & 0xFF;
            throw new GeneralSecurityException("Apple TV devolvió error TLV código " + errCode);
        }
        return tlv;
    }

    private void closeSocketQuietly() {
        sessionReady = false;
        cipher = null;
        for (CompletableFuture<Map<String, Object>> f : pendingXids.values()) {
            f.cancel(true);
        }
        pendingXids.clear();
        for (CompletableFuture<Map<String, Object>> f : pendingAuthFrames.values()) {
            f.cancel(true);
        }
        pendingAuthFrames.clear();
        Socket s = this.socket;
        this.socket = null;
        this.outStream = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void notifyState(boolean connected, String status, long rttMs) {
        if (listener != null) {
            listener.onStateChanged(connected, status, rttMs);
        }
    }

    private void notifyError(String msg) {
        if (listener != null) {
            listener.onError(msg);
        }
    }
}
