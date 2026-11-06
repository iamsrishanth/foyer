package com.kaera.foyer;

import android.content.Context;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * The phone-remote endpoint. Serves the remote web app and a small JSON API.
 * Every mutating call needs the bearer token handed out at pairing time.
 */
public class LauncherServer {

    public static final int PORT = 8484;

    private final Context ctx;
    private final int port;
    private volatile boolean running;
    private volatile String html;
    private ServerSocket server;

    public LauncherServer(Context ctx, int port) {
        this.ctx = ctx.getApplicationContext();
        this.port = port;
    }

    public void start() {
        if (running) return;
        running = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "foyer-http");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {
        }
        server = null;
    }

    private void loop() {
        try {
            server = new ServerSocket(port);
            server.setReuseAddress(true);
            while (running) {
                Socket s;
                try {
                    s = server.accept();
                } catch (Exception e) {
                    if (!running) break;
                    continue;
                }
                try {
                    handle(s);
                } catch (Exception ignored) {
                } finally {
                    try {
                        s.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception e) {
            // port busy or socket closed
        }
    }

    private String page() {
        String h = html;
        if (h == null) {
            try {
                InputStream in = ctx.getAssets().open("remote/index.html");
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                in.close();
                h = new String(bo.toByteArray(), "UTF-8");
            } catch (Exception e) {
                h = "<html><body style='font-family:sans-serif;background:#0a0e13;color:#eaf2fa'>"
                        + "<h2>Foyer</h2><p>Remote page missing from the APK.</p></body></html>";
            }
            html = h;
        }
        return h;
    }

    private void handle(Socket s) throws Exception {
        s.setSoTimeout(20000);
        InputStream in = s.getInputStream();
        String reqLine = readLine(in);
        if (reqLine == null) return;
        String[] bits = reqLine.split(" ");
        if (bits.length < 2) return;

        String method = bits[0];
        String target = bits[1];
        String query = "";
        int qm = target.indexOf('?');
        if (qm >= 0) {
            query = target.substring(qm + 1);
            target = target.substring(0, qm);
        }

        int len = 0;
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int c = line.indexOf(':');
            if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase("content-length")) {
                try {
                    len = Integer.parseInt(line.substring(c + 1).trim());
                } catch (Exception ignored) {
                }
            }
        }

        // Read the body once, as bytes: JSON calls decode it, the wallpaper upload keeps it raw.
        byte[] raw = readN(in, len);
        String body = new String(raw, "UTF-8");
        OutputStream out = s.getOutputStream();

        if ("GET".equals(method) && ("/".equals(target) || "/index.html".equals(target))) {
            send(out, "200 OK", "text/html; charset=utf-8", page());
            return;
        }

        MainActivity host = MainActivity.instance;

        if ("GET".equals(method) && "/api/state".equals(target)) {
            if (host == null) {
                // Honest and distinct: the phone keeps its pairing and retries, it does not re-pair.
                send(out, "503 Service Unavailable", "application/json",
                        "{\"ok\":false,\"error\":\"launcher not running\",\"status\":\"backend\"}");
                return;
            }
            send(out, "200 OK", "application/json", host.stateJson());
            return;
        }

        // Wallpaper upload authenticates through the query string because the body is binary.
        if ("POST".equals(method) && "/api/wallpaper".equals(target)) {
            if (!tokenMatches(param(query, "token"))) {
                send(out, "401 Unauthorized", "application/json", "{\"ok\":false,\"error\":\"not paired\"}");
                return;
            }
            if (host == null) {
                send(out, "503 Service Unavailable", "application/json",
                        "{\"ok\":false,\"error\":\"launcher not running\",\"status\":\"backend\"}");
                return;
            }
            if (raw.length == 0) {
                send(out, "400 Bad Request", "application/json", "{\"ok\":false,\"error\":\"empty upload\"}");
                return;
            }
            boolean ok = host.saveWallpaper(raw);
            send(out, "200 OK", "application/json", ok ? "{\"ok\":true}" : "{\"ok\":false}");
            return;
        }

        if (!"POST".equals(method)) {
            send(out, "404 Not Found", "application/json", "{\"ok\":false,\"error\":\"not found\"}");
            return;
        }
        if ("/api/pair".equals(target)) {
            if (host == null) {
                send(out, "503 Service Unavailable", "application/json",
                        "{\"ok\":false,\"error\":\"launcher not running\",\"status\":\"backend\"}");
                return;
            }
            String pin = Json.field(body, "pin");
            if (pin != null && pin.trim().equals(host.pin())) {
                host.onPaired();
                send(out, "200 OK", "application/json",
                        "{\"ok\":true,\"token\":\"" + Json.esc(host.token()) + "\"}");
            } else {
                send(out, "403 Forbidden", "application/json", "{\"ok\":false,\"error\":\"wrong PIN\"}");
            }
            return;
        }

        if (!tokenMatches(Json.field(body, "token"))) {
            send(out, "401 Unauthorized", "application/json", "{\"ok\":false,\"error\":\"not paired\"}");
            return;
        }

        // ---- commands that must keep working while the launcher Activity is gone ----------

        if ("/api/cmd".equals(target)) {
            String cmd = Json.field(body, "cmd");
            // Both clients use this probe to test the pairing token. It must answer for a valid
            // token even when the Activity is dead or the input helper is down, otherwise the phone
            // and the web remote throw the pairing away over a recoverable problem.
            if ("noop".equals(cmd)) {
                send(out, "200 OK", "application/json", noopJson(host));
                return;
            }
            remoteCmd(out, host, cmd);
            return;
        }
        if ("/api/global".equals(target)) {
            String action = Json.field(body, "action");
            boolean ok = host != null ? host.globalAction(action) : FoyerAccessibility.global(action);
            send(out, "200 OK", "application/json", "{\"ok\":" + ok + "}");
            return;
        }

        // ---- everything below really needs the launcher UI --------------------------------

        if (host == null) {
            send(out, "503 Service Unavailable", "application/json",
                    "{\"ok\":false,\"error\":\"launcher not running\",\"status\":\"backend\"}");
            return;
        }

        if ("/api/launch".equals(target)) {
            host.launchById(Json.field(body, "id"));
            send(out, "200 OK", "application/json", "{\"ok\":true}");
            return;
        }
        if ("/api/home".equals(target)) {
            host.goHome();
            send(out, "200 OK", "application/json", "{\"ok\":true}");
            return;
        }
        if ("/api/tap".equals(target)) {
            float nx = floatField(body, "x", 0.5f);
            float ny = floatField(body, "y", 0.5f);
            boolean ok = host.tap(nx, ny);
            send(out, "200 OK", "application/json", "{\"ok\":" + ok + "}");
            return;
        }
        if ("/api/volume".equals(target)) {
            boolean ok = host.volume(Json.field(body, "dir"));
            send(out, "200 OK", "application/json", "{\"ok\":" + ok + "}");
            return;
        }
        if ("/api/tiles".equals(target)) {
            host.applyTiles(Json.field(body, "order"), Json.field(body, "hidden"));
            send(out, "200 OK", "application/json", "{\"ok\":true}");
            return;
        }
        if ("/api/jellyfin".equals(target)) {
            host.setJellyfin(Json.field(body, "url"), Json.field(body, "key"));
            send(out, "200 OK", "application/json", "{\"ok\":true}");
            return;
        }
        if ("/api/wallpaper/clear".equals(target)) {
            host.clearWallpaper();
            send(out, "200 OK", "application/json", "{\"ok\":true}");
            return;
        }
        if ("/api/pin".equals(target)) {
            send(out, "200 OK", "application/json",
                    "{\"ok\":true,\"pin\":\"" + Json.esc(host.resetPin()) + "\"}");
            return;
        }
        send(out, "404 Not Found", "application/json", "{\"ok\":false,\"error\":\"not found\"}");
    }

    /**
     * Pairing probe. A valid token always gets {@code ok:true} here: the input backend being down,
     * or the launcher Activity being gone, are not pairing failures.
     */
    private String noopJson(MainActivity host) {
        int sel = host == null ? 0 : host.selected();
        return "{\"ok\":true,\"noop\":true,\"sel\":" + sel
                + ",\"input\":{\"helper\":" + InputRoute.helperAvailable(ctx) + "}}";
    }

    /**
     * One D-pad/OK command, dispatched exactly once.
     *
     * <p>Preferred route is a real key event through the on-device helper: the system delivers it to
     * whichever window owns input, and Foyer's own key handler maps it onto the same
     * {@code HomeScreen} calls the old code made locally. When the helper is not available the
     * command may still be applied to Foyer's own navigation, but only if Foyer provably owns input;
     * otherwise nothing is touched and {@code status:"unavailable"} says so, so a command can never
     * move a hidden launcher selection while another app is in front.
     *
     * <p>{@code ok} stays true for every recognised command (the old web remote paints its selection
     * from {@code sel} and must not log itself out); {@code status} and {@code route} carry the truth.
     */
    private void remoteCmd(OutputStream out, MainActivity host, String cmd) throws Exception {
        if (cmd == null || !InputRoute.isSupported(cmd)) {
            send(out, "200 OK", "application/json",
                    "{\"ok\":false,\"error\":\"unknown cmd\",\"status\":\"rejected\"}");
            return;
        }
        long start = SystemClock.uptimeMillis();
        InputRoute.Result sent = InputRoute.dispatch(ctx, cmd);
        String route;
        String status;
        String reason = sent.detail;
        if (InputRoute.STATUS_INJECTED.equals(sent.status)) {
            route = "system";
            status = InputRoute.STATUS_INJECTED;
        } else if (sent.mayFallBackLocally() && host != null && host.foyerOwnsInput()) {
            // Only a command that was provably not delivered may also be applied locally; a key that
            // may already be on its way must never be sent twice.
            boolean applied = host.move(InputRoute.localName(cmd));
            if (applied) {
                route = "foyer";
                status = "foyer";
                reason = sent.detail;
            } else {
                route = "none";
                status = InputRoute.STATUS_UNKNOWN;
                reason = "Foyer did not apply the command in time";
            }
        } else if (InputRoute.STATUS_UNKNOWN.equals(sent.status)
                || InputRoute.STATUS_REJECTED.equals(sent.status)
                || InputRoute.STATUS_ERROR.equals(sent.status)) {
            route = "none";
            status = sent.status;
            reason = sent.detail;
        } else {
            route = "none";
            status = InputRoute.STATUS_UNAVAILABLE;
        }
        long millis = SystemClock.uptimeMillis() - start;
        int sel = host == null ? 0 : host.selected();
        String target = InputRoute.foregroundPackage();
        StringBuilder b = new StringBuilder(200);
        b.append("{\"ok\":true,\"cmd\":\"").append(Json.esc(cmd)).append("\",\"sel\":").append(sel)
                .append(",\"status\":\"").append(status).append("\",\"route\":\"").append(route)
                .append("\",\"ms\":").append(millis);
        if (target != null) b.append(",\"target\":\"").append(Json.esc(target)).append('"');
        if (reason != null) b.append(",\"reason\":\"").append(Json.esc(reason)).append('"');
        b.append('}');
        send(out, "200 OK", "application/json", b.toString());
    }

    private boolean tokenMatches(String token) {
        return token != null && token.equals(Pairing.token(ctx));
    }

    /** Reads a bare JSON number such as {"x":0.42}; Json.field only handles quoted strings. */
    private static float floatField(String json, String key, float fallback) {
        if (json == null) return fallback;
        String pat = "\"" + key + "\"";
        int i = json.indexOf(pat);
        if (i < 0) return fallback;
        int c = json.indexOf(':', i + pat.length());
        if (c < 0) return fallback;
        int k = c + 1;
        while (k < json.length() && Character.isWhitespace(json.charAt(k))) k++;
        int start = k;
        while (k < json.length()) {
            char ch = json.charAt(k);
            if (ch == '-' || ch == '+' || ch == '.' || (ch >= '0' && ch <= '9') || ch == 'e' || ch == 'E') k++;
            else break;
        }
        try {
            return Float.parseFloat(json.substring(start, k));
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String param(String query, String key) {        if (query == null) return null;
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            String name = eq >= 0 ? part.substring(0, eq) : part;
            if (name.equals(key)) {
                return eq >= 0 ? part.substring(eq + 1) : "";
            }
        }
        return null;
    }

    /** Reads a CRLF (or LF) terminated ASCII line straight off the socket. */
    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') buf.write(c);
        }
        if (c == -1 && buf.size() == 0) return null;
        return new String(buf.toByteArray(), "UTF-8");
    }

    private static byte[] readN(InputStream in, int len) throws Exception {
        if (len <= 0) return new byte[0];
        byte[] data = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(data, off, len - off);
            if (n < 0) break;
            off += n;
        }
        if (off == len) return data;
        byte[] shorter = new byte[off];
        System.arraycopy(data, 0, shorter, 0, off);
        return shorter;
    }

    private void send(OutputStream out, String status, String type, String body) throws Exception {
        byte[] b = body.getBytes("UTF-8");
        String head = "HTTP/1.1 " + status + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + b.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        out.write(b);
        out.flush();
    }
}
