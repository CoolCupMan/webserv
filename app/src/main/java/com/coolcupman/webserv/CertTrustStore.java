package com.coolcupman.webserv;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.http.SslCertificate;
import android.os.Build;
import android.os.Bundle;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Certificates the user explicitly accepted (typically self-signed router/firewall/hypervisor certs).
 * Pinned by SHA-256 fingerprint per host:port, so a changed certificate prompts again.
 */
public final class CertTrustStore {

    private static final String PREFS = "trusted_certs";

    private final SharedPreferences prefs;
    /** Accepted for this app session only ("Continue once"). */
    private static final Set<String> sessionTrust = new HashSet<>();

    public CertTrustStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean isTrusted(String hostKey, String fingerprint) {
        if (fingerprint == null) return false;
        String k = hostKey + "|" + fingerprint;
        synchronized (sessionTrust) {
            if (sessionTrust.contains(k)) return true;
        }
        return prefs.getBoolean(k, false);
    }

    public void trustPermanently(String hostKey, String fingerprint) {
        prefs.edit().putBoolean(hostKey + "|" + fingerprint, true).apply();
    }

    public void trustForSession(String hostKey, String fingerprint) {
        synchronized (sessionTrust) {
            sessionTrust.add(hostKey + "|" + fingerprint);
        }
    }

    public int count() {
        return prefs.getAll().size();
    }

    public void clear() {
        prefs.edit().clear().apply();
        synchronized (sessionTrust) {
            sessionTrust.clear();
        }
    }

    // ---- fingerprint helpers ---------------------------------------------

    public static String fingerprint(X509Certificate cert) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
        } catch (Exception e) {
            return null;
        }
    }

    /** Extracts the X.509 certificate WebView hands us in an SslError. */
    public static X509Certificate toX509(SslCertificate cert) {
        if (cert == null) return null;
        if (Build.VERSION.SDK_INT >= 29) return cert.getX509Certificate();
        // Older releases only expose the DER bytes through the saved-state bundle.
        Bundle b = SslCertificate.saveState(cert);
        byte[] der = b == null ? null : b.getByteArray("x509-certificate");
        if (der == null) return null;
        try {
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(der));
        } catch (Exception e) {
            return null;
        }
    }

    /** "AB:CD:..." */
    public static String pretty(String fp) {
        if (fp == null) return "?";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fp.length(); i += 2) {
            if (i > 0) sb.append(':');
            sb.append(fp, i, Math.min(i + 2, fp.length()));
        }
        return sb.toString();
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format(Locale.ROOT, "%02X", b));
        return sb.toString();
    }
}
