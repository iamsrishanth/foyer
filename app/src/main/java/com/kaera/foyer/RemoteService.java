package com.kaera.foyer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;

/**
 * Keeps the phone-remote HTTP server alive when another app is in front, and answers
 * UDP discovery probes so the phone app can find the projector without typing an address.
 */
public class RemoteService extends Service {

    private static final String CHANNEL = "foyer_remote";
    private static final int NOTIFICATION_ID = 1001;

    /** Phone broadcasts FOYER_DISCOVER here; we reply with our port, name and pairing state. */
    public static final int DISCOVERY_PORT = 8485;
    private static final String PROBE = "FOYER_DISCOVER";

    private LauncherServer server;
    private volatile boolean discovering;
    private DatagramSocket discoverySocket;

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(NOTIFICATION_ID, buildNotification());
        if (server == null) {
            server = new LauncherServer(this, LauncherServer.PORT);
            server.start();
        }
        startDiscovery();
        // The input helper reads this token; write it as soon as the remote comes up so a fresh
        // install can be provisioned without waiting for a phone to poll /api/state. Off the main
        // thread because it touches the app's external files directory.
        new Thread(new Runnable() {
            @Override
            public void run() {
                InputRoute.ensureToken(getApplicationContext());
            }
        }, "foyer-input-token").start();
    }

    private void startDiscovery() {
        if (discovering) return;
        discovering = true;
        final Context ctx = getApplicationContext();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    DatagramSocket ds = new DatagramSocket(null);
                    ds.setReuseAddress(true);
                    ds.bind(new InetSocketAddress(DISCOVERY_PORT));
                    discoverySocket = ds;
                    byte[] buf = new byte[128];
                    while (discovering) {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        ds.receive(p);
                        String msg = new String(p.getData(), 0, p.getLength(), "UTF-8").trim();
                        if (!msg.startsWith(PROBE)) continue;
                        String name = String.valueOf(Build.MODEL).replace('|', ' ');
                        String reply = "FOYER|1|" + LauncherServer.PORT + "|" + name + "|"
                                + (Pairing.hasPaired(ctx) ? "paired" : "new");
                        byte[] out = reply.getBytes("UTF-8");
                        ds.send(new DatagramPacket(out, out.length, p.getAddress(), p.getPort()));
                    }
                } catch (Exception ignored) {
                    // socket closed on shutdown
                }
            }
        }, "foyer-discovery");
        t.setDaemon(true);
        t.start();
    }

    private Notification buildNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel c = new NotificationChannel(
                        CHANNEL, "Phone remote", NotificationManager.IMPORTANCE_MIN);
                c.setShowBadge(false);
                nm.createNotificationChannel(c);
            }
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setContentTitle("Foyer remote")
                .setContentText("Listening on port " + LauncherServer.PORT)
                .setSmallIcon(R.drawable.ic_launcher)
                .setOngoing(true)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        discovering = false;
        try {
            if (discoverySocket != null) discoverySocket.close();
        } catch (Exception ignored) {
        }
        discoverySocket = null;
        if (server != null) {
            server.stop();
            server = null;
        }
        super.onDestroy();
    }
}
