package com.kaera.foyer;

import android.content.Context;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;

/**
 * Sends the phone's D-pad/OK to whichever window owns input on the projector.
 *
 * <p>Android 12 gives an ordinary app no way to inject key events: {@code INJECT_EVENTS} is a
 * signature permission, so running {@code /system/bin/input} from Foyer's own uid throws
 * {@code SecurityException}. Accessibility cannot stand in for it either - {@code
 * GLOBAL_ACTION_DPAD_*} only exists from API 33, and the TV apps people actually use here (YouTube
 * on Cobalt, Spotify TV on WebView, ...) publish no usable node tree, so node focusing/clicking
 * would be blind guesswork.
 *
 * <p>The commands are therefore handed to a tiny helper that runs in the adb shell uid - the only
 * identity that may inject key events without root. {@code tools/provision-input-helper.ps1}
 * starts it and {@code tools/foyer-inputd.sh} is the daemon: it listens on 127.0.0.1 only, accepts
 * exactly {@code up/down/left/right/center} plus a health {@code ping}, and authenticates every
 * request with the token this class keeps in Foyer's own external files directory (readable by the
 * shell uid, not by other apps).
 *
 * <p>Injection is deliberately the primary route even while Foyer is in front: the system delivers
 * the key to the focused window, and Foyer's own {@code dispatchKeyEvent} turns it into exactly the
 * same {@code HomeScreen.move/activate} call the old local path made.
 *
 * <p>The result distinguishes "never sent" from "sent but unconfirmed". Only a command that was
 * provably not delivered may be retried or applied to Foyer's own model; a key that may already have
 * been injected must never be dispatched a second time (that is how one press would become two).
 */
final class InputRoute {

    static final int HELPER_PORT = 8486;

    /** Kept in the app's external files dir: the shell-uid helper can read it, other apps cannot. */
    static final String TOKEN_NAME = "input-helper.token";

    static final String STATUS_INJECTED = "injected";
    static final String STATUS_UNAVAILABLE = "unavailable";
    static final String STATUS_REJECTED = "rejected";
    static final String STATUS_ERROR = "error";
    /** The command reached the helper but no confirmation came back: it may or may not have landed. */
    static final String STATUS_UNKNOWN = "unknown";

    private static final Object LOCK = new Object();
    private static final SecureRandom RND = new SecureRandom();

    /** Worst-case wall time for one command, so the phone's own timeout can be larger than this. */
    static final int BUDGET_MS = 2800;
    private static final int CONNECT_MS = 600;
    private static final int READ_MS = 1400;
    private static final int RETRY_CONNECT_MS = 500;
    private static final int RETRY_READ_MS = 1000;
    private static final long RETRY_MIN_MS = 700;

    private static String cachedToken;
    private static long cachedTokenAt;
    private static boolean cachedAvailable;
    private static long cachedAvailableAt;

    private InputRoute() {
    }

    static final class Result {
        final String status;
        final String detail;
        final long millis;

        Result(String status, String detail, long millis) {
            this.status = status;
            this.detail = detail;
            this.millis = millis;
        }

        /** True when the caller must not also apply the command to Foyer's own navigation. */
        boolean mayFallBackLocally() {
            return STATUS_UNAVAILABLE.equals(status);
        }
    }

    /** The commands the remote may request; anything else is refused instead of silently ignored. */
    static boolean isSupported(String cmd) {
        return "up".equals(cmd) || "down".equals(cmd) || "left".equals(cmd) || "right".equals(cmd)
                || "ok".equals(cmd) || "enter".equals(cmd) || "center".equals(cmd);
    }

    /** Foyer's own model knows "ok"/"enter"; the wire and the remote also use "center". */
    static String localName(String cmd) {
        return ("center".equals(cmd) || "enter".equals(cmd)) ? "ok" : cmd;
    }

    /** OK, Enter and Center all mean KEYCODE_DPAD_CENTER to the app on the other end. */
    private static String wireName(String cmd) {
        return ("ok".equals(cmd) || "enter".equals(cmd) || "center".equals(cmd)) ? "center" : cmd;
    }

    /**
     * One command, one injection. Safe to call from the HTTP thread; never touches the UI thread.
     *
     * <p>The whole call is bounded by {@link #BUDGET_MS}. That matters to the phone: it must be able
     * to tell "the projector refused" from "the projector never answered" inside its own timeout, and
     * a budget longer than the phone's would make a key that did land look like a failure - the user
     * presses again and the key doubles.
     */
    static Result dispatch(Context ctx, String cmd) {
        long start = SystemClock.uptimeMillis();
        String token = token(ctx);
        if (token == null) {
            return new Result(STATUS_UNAVAILABLE, "input helper is not provisioned", 0);
        }
        Reply reply = request(token, wireName(cmd), CONNECT_MS, READ_MS);
        if (reply.kind == REFUSED || reply.kind == NOT_SENT) {
            // Nothing was delivered, so one retry cannot double-press. The daemon keeps listening,
            // so this is only a rare race with the listener. Skip it when the budget is spent.
            long left = BUDGET_MS - (SystemClock.uptimeMillis() - start);
            if (left > RETRY_MIN_MS) {
                sleep(80);
                reply = request(token, wireName(cmd),
                        (int) Math.min(RETRY_CONNECT_MS, left / 3),
                        (int) Math.min(RETRY_READ_MS, left - 200));
            }
        }
        long millis = SystemClock.uptimeMillis() - start;
        if (reply.kind == UNKNOWN) {
            // A key may already be on its way to the TV app. Never dispatch this command again.
            return new Result(STATUS_UNKNOWN, "input helper did not confirm the key", millis);
        }
        if (reply.kind != OK || reply.text == null) {
            return new Result(STATUS_UNAVAILABLE, "input helper is not running", millis);
        }
        String text = reply.text;
        if (text.startsWith("OK")) {
            return new Result(STATUS_INJECTED, null, millis);
        }
        if (text.contains("auth") || text.contains("notoken")) {
            synchronized (LOCK) {
                cachedToken = null;
            }
            return new Result(STATUS_ERROR, "input helper rejected the token", millis);
        }
        if (text.contains("cmd")) {
            return new Result(STATUS_REJECTED, "input helper rejected the command", millis);
        }
        return new Result(STATUS_ERROR, text, millis);
    }

    /** Cheap health probe, cached: /api/state asks for it on every phone refresh (about every 5 s). */
    static boolean helperAvailable(Context ctx) {
        synchronized (LOCK) {
            if (SystemClock.uptimeMillis() - cachedAvailableAt < (cachedAvailable ? 5000 : 2000)) {
                return cachedAvailable;
            }
        }
        String token = token(ctx);
        Reply reply = token == null ? null : request(token, "ping", 400, 800);
        if (reply == null || reply.kind == REFUSED || reply.kind == NOT_SENT) {
            // A busy system can miss one probe; only a second failure means "offline". The probe is
            // on the /api/state path, so it stays short: a 3.7 s probe here used to make a healthy
            // pairing look unreachable to the phone.
            sleep(60);
            reply = token == null ? null : request(token, "ping", 300, 600);
        }
        boolean ok = reply != null && reply.kind == OK && reply.text != null
                && reply.text.startsWith("OK");
        synchronized (LOCK) {
            cachedAvailable = ok;
            cachedAvailableAt = SystemClock.uptimeMillis();
        }
        return ok;
    }

    /** Writes the token file if it is missing, so provisioning can be verified before the phone polls. */
    static void ensureToken(Context ctx) {
        token(ctx);
    }

    /** Package that last took a foreground window, for the phone's status line. May be null. */
    static String foregroundPackage() {
        return FoyerAccessibility.lastActivePackage();
    }

    private static String token(Context ctx) {
        synchronized (LOCK) {
            long now = SystemClock.uptimeMillis();
            if (cachedToken != null && now - cachedTokenAt < 2000) return cachedToken;
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) {
                cachedToken = null;
                cachedTokenAt = now;
                return null;
            }
            File f = new File(dir, TOKEN_NAME);
            String t = readSmall(f);
            if (t == null || t.length() < 16) {
                t = makeToken();
                if (!writeSmall(f, t)) {
                    cachedToken = null;
                    cachedTokenAt = now;
                    return null;
                }
            }
            cachedToken = t;
            cachedTokenAt = now;
            return t;
        }
    }

    private static String makeToken() {
        byte[] raw = new byte[16];
        RND.nextBytes(raw);
        StringBuilder b = new StringBuilder(32);
        for (byte v : raw) {
            b.append(Character.forDigit((v >> 4) & 0xf, 16));
            b.append(Character.forDigit(v & 0xf, 16));
        }
        return b.toString();
    }

    private static String readSmall(File f) {
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream(64);
            byte[] buf = new byte[128];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                if (bo.size() > 256) break;
            }
            return new String(bo.toByteArray(), "UTF-8").trim();
        } catch (Exception e) {
            return null;
        } finally {
            if (in != null) try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static boolean writeSmall(File f, String value) {
        OutputStream out = null;
        try {
            out = new FileOutputStream(f, false);
            out.write(value.getBytes("UTF-8"));
            out.flush();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (out != null) try {
                out.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- one request -------------------------------------------------------------------------

    private static final int OK = 0;
    /** Connection refused: definitely not delivered. */
    private static final int REFUSED = 1;
    /** Failed before the request was handed over: not delivered either. */
    private static final int NOT_SENT = 2;
    /** Written but no reply: the key may or may not have been injected. */
    private static final int UNKNOWN = 3;

    private static final class Reply {
        final int kind;
        final String text;

        Reply(int kind, String text) {
            this.kind = kind;
            this.text = text;
        }
    }

    private static Reply request(String token, String cmd, int connectMs, int readMs) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", HELPER_PORT), connectMs);
        } catch (ConnectException refused) {
            close(s);
            return new Reply(REFUSED, null);
        } catch (Exception e) {
            close(s);
            return new Reply(NOT_SENT, null);
        }
        Reply result = new Reply(UNKNOWN, null);
        try {
            s.setSoTimeout(readMs);
            OutputStream out = s.getOutputStream();
            out.write((token + " " + cmd + "\n").getBytes("UTF-8"));
            out.flush();
            // The request is out of our hands now: from here a failure means "unknown", never
            // "not sent", so callers must not replay it.
            InputStream in = s.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream(48);
            int c;
            while ((c = in.read()) != -1) {
                if (c == '\n') break;
                bo.write(c);
                if (bo.size() > 160) break;
            }
            String text = new String(bo.toByteArray(), "UTF-8").trim();
            if (!text.isEmpty()) result = new Reply(OK, text);
        } catch (Exception ignored) {
            // falls through as UNKNOWN
        } finally {
            close(s);
        }
        return result;
    }

    private static void close(Socket s) {
        try {
            s.close();
        } catch (Exception ignored) {
        }
    }
}
