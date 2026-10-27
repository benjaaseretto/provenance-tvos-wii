package com.signature.atvremote.protocol;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Integración en tiempo real con el servidor web de Provenance en tvOS
 * y con el parche ckstub.m (refresh.flag -> PVRefreshLibraryNotification).
 */
public final class ProvenanceClient {

    private static final ExecutorService POOL = Executors.newCachedThreadPool();

    private ProvenanceClient() {}

    public interface WebStatusCallback {
        void onResult(boolean online, List<String> romFiles);
    }

    /**
     * Comprueba si el servidor web de Provenance está activo y obtiene en tiempo real
     * los archivos/ROMs listados por GCDWebUploader (/list?path=/).
     */
    public static void checkWebServerAndRoms(String host, WebStatusCallback cb) {
        POOL.execute(() -> {
            boolean online = false;
            List<String> roms = new ArrayList<>();
            try {
                URL url = new URL("http://" + host + "/list?path=/");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(1500);
                conn.setReadTimeout(1500);
                conn.setRequestMethod("GET");
                int code = conn.getResponseCode();
                if (code == 200) {
                    online = true;
                    String json = readStream(conn.getInputStream());
                    parseFilesFromListJson(json, roms);
                }
                conn.disconnect();
            } catch (Exception ignored) {
            }
            if (!online) {
                try {
                    URL url = new URL("http://" + host + "/");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(1200);
                    conn.setReadTimeout(1200);
                    conn.setRequestMethod("GET");
                    online = (conn.getResponseCode() == 200);
                    conn.disconnect();
                } catch (Exception ignored) {
                }
            }
            if (cb != null) {
                cb.onResult(online, roms);
            }
        });
    }

    private static String readStream(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void parseFilesFromListJson(String json, List<String> out) {
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.getJSONObject(i);
                String name = item.optString("name", "");
                if (!name.isEmpty() && !name.startsWith(".") && !name.equals("refresh.flag")) {
                    out.add(name);
                }
            }
        } catch (Exception ignored) {
        }
    }

    public interface ActionCallback {
        void onDone(boolean ok, String message);
    }

    public static void uploadRefreshFlag(String host, ActionCallback cb) {
        POOL.execute(() -> {
            try {
                String boundary = "----SignatureAtvBoundary" + System.currentTimeMillis();
                URL url = new URL("http://" + host + "/upload");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(3500);
                conn.setReadTimeout(3500);
                conn.setDoOutput(true);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

                StringBuilder sb = new StringBuilder();
                sb.append("--").append(boundary).append("\r\n");
                sb.append("Content-Disposition: form-data; name=\"path\"\r\n\r\n");
                sb.append("/\r\n");
                sb.append("--").append(boundary).append("\r\n");
                sb.append("Content-Disposition: form-data; name=\"files[]\"; filename=\"refresh.flag\"\r\n");
                sb.append("Content-Type: text/plain\r\n\r\n");
                sb.append("refresh\n\r\n");
                sb.append("--").append(boundary).append("--\r\n");

                byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body);
                }
                int code = conn.getResponseCode();
                conn.disconnect();
                if (cb != null) {
                    if (code == 200) {
                        cb.onDone(true, "Biblioteca de Provenance actualizada");
                    } else {
                        cb.onDone(false, "Abrí Provenance en el Apple TV para recargar ROMs");
                    }
                }
            } catch (Exception e) {
                if (cb != null) {
                    cb.onDone(false, "Abrí Provenance en el Apple TV para gestionar ROMs");
                }
            }
        });
    }
}
