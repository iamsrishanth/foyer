package com.kaera.foyer;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;

/** Pairing PIN shown on the TV, and the bearer token the phone keeps. */
public final class Pairing {
    private static final String PREFS = "foyer";
    private static final SecureRandom RND = new SecureRandom();

    private Pairing() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized String pin(Context c) {
        SharedPreferences p = prefs(c);
        String v = p.getString("pin", null);
        if (v == null || v.length() != 6) {
            v = String.format("%06d", RND.nextInt(1000000));
            p.edit().putString("pin", v).apply();
        }
        return v;
    }

    /** New PIN, keeps the existing token unless the caller also clears it. */
    public static synchronized String rotatePin(Context c) {
        String v = String.format("%06d", RND.nextInt(1000000));
        prefs(c).edit().putString("pin", v).apply();
        return v;
    }

    public static synchronized String token(Context c) {
        SharedPreferences p = prefs(c);
        String t = p.getString("token", null);
        if (t == null || t.length() < 16) {
            t = newToken();
            p.edit().putString("token", t).apply();
        }
        return t;
    }

    public static synchronized void forget(Context c) {
        prefs(c).edit().remove("token").apply();
    }

    /** True once any phone has completed pairing, so the QR overlay only auto-opens on a fresh install. */
    public static synchronized boolean hasPaired(Context c) {
        return prefs(c).getBoolean("paired", false);
    }

    public static synchronized void markPaired(Context c) {
        prefs(c).edit().putBoolean("paired", true).apply();
    }

    private static String newToken() {
        byte[] b = new byte[16];
        RND.nextBytes(b);
        StringBuilder s = new StringBuilder(32);
        for (byte x : b) {
            s.append(String.format("%02x", x));
        }
        return s.toString();
    }
}
