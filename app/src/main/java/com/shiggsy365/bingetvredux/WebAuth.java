package com.shiggsy365.bingetvredux;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Who may use the web page. The TV's Settings screen shows a 4-digit PIN; a browser that
 * enters it is given a token (kept in a cookie) and is remembered until the TV forgets all
 * browsers. Only hashes of the tokens are stored. Too many wrong PINs lock pairing for a
 * minute, so the PIN can't be guessed by trying them all.
 */
public final class WebAuth {
    public static final String COOKIE = "redux_token";
    private static final String PREFS = "web_auth";
    private static final String PREF_PIN = "pin";
    private static final String PREF_TOKENS = "tokens";
    private static final int MAX_FAILURES = 5;
    private static final long LOCK_MS = 60_000;

    private static final SecureRandom sRandom = new SecureRandom();
    private static int sFailures;
    private static long sLockedUntil;

    private WebAuth() {}

    /** Thrown when pairing is refused; the message is shown to the user. */
    public static class PairingException extends Exception {
        public final boolean locked;

        PairingException(String message, boolean locked) {
            super(message);
            this.locked = locked;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The current PIN, made on first use. */
    public static synchronized String pin(Context context) {
        String pin = prefs(context).getString(PREF_PIN, null);
        return pin != null ? pin : newPin(context);
    }

    /** Replaces the PIN. Browsers already paired stay paired. */
    public static synchronized String newPin(Context context) {
        String pin = String.format(Locale.ROOT, "%04d", sRandom.nextInt(10_000));
        prefs(context).edit().putString(PREF_PIN, pin).apply();
        return pin;
    }

    /** Unpairs every browser; each has to enter the PIN again. */
    public static synchronized void forgetBrowsers(Context context) {
        prefs(context).edit().remove(PREF_TOKENS).apply();
    }

    public static synchronized int browserCount(Context context) {
        return prefs(context).getStringSet(PREF_TOKENS, new HashSet<>()).size();
    }

    public static synchronized boolean isPaired(Context context, String token) {
        if (token == null || token.isEmpty()) return false;
        return prefs(context).getStringSet(PREF_TOKENS, new HashSet<>()).contains(hash(token));
    }

    /** Checks a PIN and, if it's right, returns a new token for the browser. */
    public static synchronized String pair(Context context, String pin) throws PairingException {
        long now = System.currentTimeMillis();
        if (now < sLockedUntil) {
            throw new PairingException("Too many wrong PINs. Try again in "
                    + ((sLockedUntil - now) / 1000 + 1) + " seconds.", true);
        }
        if (pin == null || !pin.trim().equals(pin(context))) {
            if (++sFailures >= MAX_FAILURES) {
                sFailures = 0;
                sLockedUntil = now + LOCK_MS;
                throw new PairingException("Too many wrong PINs. Try again in a minute.", true);
            }
            throw new PairingException("That isn't the PIN on the TV", false);
        }
        sFailures = 0;
        byte[] bytes = new byte[32];
        sRandom.nextBytes(bytes);
        String token = hex(bytes);
        Set<String> tokens = new HashSet<>(
                prefs(context).getStringSet(PREF_TOKENS, new HashSet<>()));
        tokens.add(hash(token));
        prefs(context).edit().putStringSet(PREF_TOKENS, tokens).apply();
        return token;
    }

    private static String hash(String token) {
        try {
            return hex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }
}
