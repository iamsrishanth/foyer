package com.kaera.foyer;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;

/**
 * Launches an exact Jellyfin item in Moonfin.
 *
 * <p>Contract verified against the installed Moonfin 2.6.0 (org.moonfin.androidtv, versionCode
 * 2000020), its manifest and Moonfin-Core tag 2.6.0:
 *
 * <pre>
 *   moonfin://item?id=&lt;itemId&gt;[&amp;serverId=&lt;moonfinServerId&gt;][&amp;userId=&lt;userId&gt;]
 *   moonfin://play?id=&lt;itemId&gt;...   (autoPlay=true, starts playback)
 * </pre>
 *
 * Moonfin's MainActivity is the registered VIEW handler for the {@code moonfin} scheme; the Flutter
 * app_links listener replays the launch link on a cold start and a live link while running, and
 * routes it to {@code /item/<id>}. Foyer deliberately uses the details route (not {@code play}),
 * leaving playback to Moonfin.
 *
 * <p>Moonfin's serverId is a UUID it generates for its own server record (Uuid().v4()), not the
 * Jellyfin server id, and it lives in Moonfin's private storage. Foyer therefore sends only the
 * Jellyfin item id; Moonfin resolves it against its own active session. That is correct for the
 * usual single-server setup (Moonfin is signed in to the same Jellyfin server as Foyer), and
 * it is the only external route Moonfin 2.6.0 exposes. If Moonfin is switched to a different
 * server, the item cannot be resolved there - Foyer can detect a missing app but cannot see inside
 * Moonfin's session to detect that mismatch. This limitation is reported rather than hidden.
 */
public final class Moonfin {

    public static final String PKG = "org.moonfin.androidtv";

    public static final class Result {
        public final boolean ok;
        public final String message;

        Result(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }
    }

    private Moonfin() {
    }

    public static boolean installed(Context c) {
        try {
            c.getPackageManager().getPackageInfo(PKG, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static String version(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(PKG, 0).versionName;
        } catch (Throwable t) {
            return "";
        }
    }

    /** The exact external route Moonfin 2.6.0 documents and registers. */
    public static Intent itemIntent(String itemId) {
        Uri uri = new Uri.Builder()
                .scheme("moonfin")
                .authority("item")
                .appendQueryParameter("id", itemId)
                .build();
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        intent.setPackage(PKG);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    public static Result open(Context c, MediaLibrary.Item item, String jellyfinServerId) {
        if (item == null || item.id == null || item.id.isEmpty()) {
            return new Result(false, "That title has no Jellyfin id yet.");
        }
        if (!installed(c)) {
            return new Result(false, "Moonfin is not installed on this projector.");
        }
        Intent intent = itemIntent(item.id);
        ResolveInfo resolved;
        try {
            resolved = c.getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
        } catch (Throwable t) {
            resolved = null;
        }
        if (resolved == null) {
            return new Result(false, "Moonfin " + version(c)
                    + " does not accept item links. Update Moonfin, or open the title there manually.");
        }
        try {
            c.startActivity(intent);
            return new Result(true, "Opening " + item.name + " in Moonfin");
        } catch (Throwable t) {
            return new Result(false, "Could not open Moonfin: " + simple(t));
        }
    }

    private static String simple(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
    }
}
