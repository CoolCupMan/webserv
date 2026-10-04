package com.coolcupman.webserv;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.CookieManager;
import android.webkit.URLUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Saves files (configuration backups, logs, exports) to Downloads.
 * Uses the WebView session cookies and honours certificates the user pinned, so it works against
 * self-signed admin interfaces where the system DownloadManager would refuse.
 */
public final class Downloader {

    public interface Callback {
        void onDone(String displayName, Uri savedUri, String error);
    }

    private final Context context;
    private final CertTrustStore certs;
    private final CredentialStore creds;
    private final Handler main = new Handler(Looper.getMainLooper());

    public Downloader(Context context, CertTrustStore certs, CredentialStore creds) {
        this.context = context.getApplicationContext();
        this.certs = certs;
        this.creds = creds;
    }

    public void download(String url, String userAgent, String contentDisposition, String mimeType, Callback cb) {
        new Thread(() -> {
            String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
            try {
                HttpURLConnection conn = openFollowingRedirects(url, userAgent);
                int code = conn.getResponseCode();
                if (code >= 400) throw new IOException("HTTP " + code);
                String cd = conn.getHeaderField("Content-Disposition");
                String type = conn.getContentType();
                if (cd != null) name = URLUtil.guessFileName(url, cd, type != null ? type : mimeType);
                Uri saved;
                try (InputStream in = conn.getInputStream()) {
                    saved = save(name, type != null ? type : mimeType, in);
                } finally {
                    conn.disconnect();
                }
                post(cb, name, saved, null);
            } catch (Exception e) {
                post(cb, name, null, e.getMessage() != null ? e.getMessage() : e.toString());
            }
        }, "download").start();
    }

    /** Saves a "data:" URL (also used for blob: downloads converted in the page). */
    public void saveDataUrl(String dataUrl, String fileName, Callback cb) {
        new Thread(() -> {
            String name = fileName == null || fileName.isEmpty() ? "download" : fileName;
            try {
                int comma = dataUrl.indexOf(',');
                if (!dataUrl.startsWith("data:") || comma < 0) throw new IOException("Unsupported data URL");
                String meta = dataUrl.substring(5, comma);
                String payload = dataUrl.substring(comma + 1);
                boolean b64 = meta.endsWith(";base64");
                String mime = meta.split(";")[0];
                byte[] bytes = b64 ? Base64.decode(payload, Base64.DEFAULT)
                        : URLDecoder.decode(payload, "UTF-8").getBytes(StandardCharsets.UTF_8);
                if (!name.contains(".")) {
                    String ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
                    if (ext != null) name = name + "." + ext;
                }
                Uri saved = save(name, mime.isEmpty() ? "application/octet-stream" : mime,
                        new java.io.ByteArrayInputStream(bytes));
                post(cb, name, saved, null);
            } catch (Exception e) {
                post(cb, name, null, e.getMessage() != null ? e.getMessage() : e.toString());
            }
        }, "download-data").start();
    }

    private void post(Callback cb, String name, Uri uri, String err) {
        main.post(() -> cb.onDone(name, uri, err));
    }

    /** Follows redirects manually so credentials are only ever sent to the host they were saved for. */
    private HttpURLConnection openFollowingRedirects(String url, String userAgent) throws Exception {
        String origin = UrlUtil.hostKey(url);
        String current = url;
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection conn = open(current, userAgent, UrlUtil.hostKey(current).equals(origin));
            int code = conn.getResponseCode();
            if (code < 300 || code >= 400 || code == 304) return conn;
            String location = conn.getHeaderField("Location");
            conn.disconnect();
            if (location == null) throw new IOException("HTTP " + code + " without Location");
            current = new URL(new URL(current), location).toString();
        }
        throw new IOException("Too many redirects");
    }

    private HttpURLConnection open(String url, String userAgent, boolean sendCredentials) throws Exception {
        String hostKey = UrlUtil.hostKey(url);
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setInstanceFollowRedirects(false);
        if (userAgent != null) conn.setRequestProperty("User-Agent", userAgent);
        String cookies = CookieManager.getInstance().getCookie(url);
        if (cookies != null) conn.setRequestProperty("Cookie", cookies);
        CredentialStore.Credential c = sendCredentials ? creds.anyForHost(hostKey) : null;
        if (c != null) {
            String token = Base64.encodeToString(
                    (c.username + ":" + c.password).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            conn.setRequestProperty("Authorization", "Basic " + token);
        }
        if (conn instanceof HttpsURLConnection) {
            HttpsURLConnection https = (HttpsURLConnection) conn;
            PinningTrustManager tm = new PinningTrustManager(hostKey, certs);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{tm}, null);
            https.setSSLSocketFactory(ctx.getSocketFactory());
            // A user-pinned certificate is accepted for this host even if its name does not match
            // (devices are usually reached by IP while the cert names something else).
            javax.net.ssl.HostnameVerifier def = HttpsURLConnection.getDefaultHostnameVerifier();
            https.setHostnameVerifier((h, session) -> tm.pinnedMatch || def.verify(h, session));
        }
        return conn;
    }

    private Uri save(String name, String mime, InputStream in) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = context.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, name);
            if (mime != null) v.put(MediaStore.Downloads.MIME_TYPE, mime.split(";")[0].trim());
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/WebServ");
            v.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("Cannot create file in Downloads");
            try (OutputStream out = cr.openOutputStream(uri)) {
                if (out == null) throw new IOException("Cannot write to Downloads");
                copy(in, out);
            } catch (IOException e) {
                cr.delete(uri, null, null);
                throw e;
            }
            v.clear();
            v.put(MediaStore.Downloads.IS_PENDING, 0);
            cr.update(uri, v, null, null);
            return uri;
        }
        // Android 8/9: app-specific Downloads folder, no storage permission required.
        File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) throw new IOException("External storage unavailable");
        File f = new File(dir, name);
        try (OutputStream out = new FileOutputStream(f)) {
            copy(in, out);
        }
        return Uri.fromFile(f);
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
    }

    /** System trust first; otherwise accept only a leaf certificate the user pinned for this host. */
    private static final class PinningTrustManager implements X509TrustManager {
        private final String hostKey;
        private final CertTrustStore store;
        private final X509TrustManager system;
        volatile boolean pinnedMatch;

        PinningTrustManager(String hostKey, CertTrustStore store) throws Exception {
            this.hostKey = hostKey;
            this.store = store;
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);
            X509TrustManager found = null;
            for (TrustManager t : tmf.getTrustManagers()) {
                if (t instanceof X509TrustManager) found = (X509TrustManager) t;
            }
            this.system = found;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            if (chain != null && chain.length > 0
                    && store.isTrusted(hostKey, CertTrustStore.fingerprint(chain[0]))) {
                pinnedMatch = true;
                return;
            }
            if (system == null) throw new CertificateException("No system trust manager");
            system.checkServerTrusted(chain, authType);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("Client certificates not supported");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return system != null ? system.getAcceptedIssuers() : new X509Certificate[0];
        }
    }
}
