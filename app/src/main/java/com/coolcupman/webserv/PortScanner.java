package com.coolcupman.webserv;

import android.os.Handler;
import android.os.Looper;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** Probes the well-known management ports of a host and reports which ones answer, and with which scheme. */
public final class PortScanner {

    public static final class KnownPort {
        public final int port;
        public final String label;

        KnownPort(int port, String label) {
            this.port = port;
            this.label = label;
        }
    }

    public static final class Result {
        public final int port;
        public final String label;
        public final String scheme;

        Result(int port, String label, String scheme) {
            this.port = port;
            this.label = label;
            this.scheme = scheme;
        }
    }

    public interface Callback {
        void onDone(String host, List<Result> open, String error);
    }

    public static final List<KnownPort> PORTS = Collections.unmodifiableList(java.util.Arrays.asList(
            new KnownPort(443, "HTTPS (router, AP, firewall, ESXi, vCenter)"),
            new KnownPort(80, "HTTP (router, AP, switch, printer)"),
            new KnownPort(8443, "HTTPS alt (UniFi, Sophos, Fortinet, Tomcat)"),
            new KnownPort(8080, "HTTP alt (proxies, Tomcat, Jenkins)"),
            new KnownPort(8006, "Proxmox VE"),
            new KnownPort(8007, "Proxmox Backup Server"),
            new KnownPort(9090, "Cockpit / Prometheus"),
            new KnownPort(3000, "Grafana"),
            new KnownPort(8081, "mongo-express (MongoDB GUI)"),
            new KnownPort(28017, "MongoDB legacy HTTP status"),
            new KnownPort(9443, "Portainer"),
            new KnownPort(9000, "Portainer (HTTP) / MinIO"),
            new KnownPort(5601, "Kibana"),
            new KnownPort(9200, "Elasticsearch"),
            new KnownPort(15672, "RabbitMQ management"),
            new KnownPort(5000, "Synology DSM (HTTP)"),
            new KnownPort(5001, "Synology DSM (HTTPS)"),
            new KnownPort(8123, "Home Assistant"),
            new KnownPort(4443, "HTTPS alt (Sophos UTM, OPNsense)"),
            new KnownPort(10443, "FortiGate / SSL VPN portal"),
            new KnownPort(4444, "Sophos UTM WebAdmin"),
            new KnownPort(8000, "HTTP alt"),
            new KnownPort(8888, "HTTP alt"),
            new KnownPort(81, "HTTP alt (cameras, NVR)"),
            new KnownPort(8384, "Syncthing"),
            new KnownPort(32400, "Plex"),
            new KnownPort(2087, "WHM / cPanel"),
            new KnownPort(10000, "Webmin")
    ));

    private static final int CONNECT_TIMEOUT_MS = 1200;
    private static final int TLS_TIMEOUT_MS = 2500;

    private final ExecutorService pool = Executors.newFixedThreadPool(12);
    private final Handler main = new Handler(Looper.getMainLooper());

    public void scan(String host, Callback cb) {
        new Thread(() -> {
            List<Result> open = new ArrayList<>();
            String error = null;
            try {
                InetAddress addr = InetAddress.getByName(host);
                List<Future<Result>> futures = new ArrayList<>();
                for (KnownPort kp : PORTS) futures.add(pool.submit(() -> probe(addr, kp)));
                for (Future<Result> f : futures) {
                    Result r = f.get(CONNECT_TIMEOUT_MS + TLS_TIMEOUT_MS + 5000, TimeUnit.MILLISECONDS);
                    if (r != null) open.add(r);
                }
            } catch (java.net.UnknownHostException e) {
                error = "Cannot resolve " + host;
            } catch (Exception e) {
                error = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            String err = error;
            main.post(() -> cb.onDone(host, open, err));
        }, "port-scan").start();
    }

    public void shutdown() {
        pool.shutdownNow();
    }

    private static Result probe(InetAddress addr, KnownPort kp) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(addr, kp.port), CONNECT_TIMEOUT_MS);
        } catch (Exception e) {
            return null;
        }
        return new Result(kp.port, kp.label, speaksTls(addr, kp.port) ? "https" : "http");
    }

    /**
     * Only checks whether a TLS handshake completes; no data is exchanged and nothing is trusted afterwards.
     * The real page load goes through WebView, which validates the certificate and asks the user.
     */
    private static boolean speaksTls(InetAddress addr, int port) {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new ProbeOnlyTrustManager()}, null);
            Socket raw = new Socket();
            raw.connect(new InetSocketAddress(addr, port), CONNECT_TIMEOUT_MS);
            raw.setSoTimeout(TLS_TIMEOUT_MS);
            try (SSLSocket ssl = (SSLSocket) ctx.getSocketFactory()
                    .createSocket(raw, addr.getHostAddress(), port, true)) {
                ssl.startHandshake();
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    /** Accepts anything: used solely to detect whether a port speaks TLS at all. */
    private static final class ProbeOnlyTrustManager implements X509TrustManager {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
