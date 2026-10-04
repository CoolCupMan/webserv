package com.coolcupman.webserv;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * HTTP authentication (Basic/Digest) credentials remembered per host and realm.
 * Passwords are encrypted with an AES key that lives in the Android Keystore and never leaves it.
 */
public final class CredentialStore {

    public static final class Credential {
        public final String username;
        public final String password;

        Credential(String username, String password) {
            this.username = username;
            this.password = password;
        }
    }

    private static final String PREFS = "credentials";
    private static final String KEY_ALIAS = "webserv_credentials";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";

    private final SharedPreferences prefs;

    public CredentialStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public Credential get(String hostKey, String realm) {
        String k = key(hostKey, realm);
        String user = prefs.getString(k + "|u", null);
        String blob = prefs.getString(k + "|p", null);
        if (user == null || blob == null) return null;
        String pass = decrypt(blob);
        return pass == null ? null : new Credential(user, pass);
    }

    /** Finds any credential saved for the host (used for file downloads, where the realm is unknown). */
    public Credential anyForHost(String hostKey) {
        for (String k : prefs.getAll().keySet()) {
            if (k.startsWith(hostKey + "|") && k.endsWith("|u")) {
                String base = k.substring(0, k.length() - 2);
                String user = prefs.getString(base + "|u", null);
                String pass = decrypt(prefs.getString(base + "|p", ""));
                if (user != null && pass != null) return new Credential(user, pass);
            }
        }
        return null;
    }

    public boolean put(String hostKey, String realm, String username, String password) {
        String blob = encrypt(password);
        if (blob == null) return false;
        String k = key(hostKey, realm);
        prefs.edit().putString(k + "|u", username).putString(k + "|p", blob).apply();
        return true;
    }

    public void remove(String hostKey, String realm) {
        String k = key(hostKey, realm);
        prefs.edit().remove(k + "|u").remove(k + "|p").apply();
    }

    public int count() {
        int n = 0;
        for (String k : prefs.getAll().keySet()) if (k.endsWith("|u")) n++;
        return n;
    }

    public void clear() {
        prefs.edit().clear().apply();
    }

    private static String key(String hostKey, String realm) {
        return hostKey + "|" + (realm == null ? "" : realm);
    }

    // ---- crypto -------------------------------------------------------------

    private static SecretKey secretKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        gen.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return gen.generateKey();
    }

    private static String encrypt(String plain) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, secretKey());
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buf = ByteBuffer.allocate(1 + iv.length + ct.length);
            buf.put((byte) iv.length).put(iv).put(ct);
            return Base64.encodeToString(buf.array(), Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    private static String decrypt(String blob) {
        try {
            byte[] all = Base64.decode(blob, Base64.NO_WRAP);
            int ivLen = all[0];
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(128, all, 1, ivLen));
            byte[] pt = c.doFinal(all, 1 + ivLen, all.length - 1 - ivLen);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
