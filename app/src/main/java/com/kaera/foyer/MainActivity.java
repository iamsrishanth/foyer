package com.kaera.foyer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.nayuki.qrcodegen.QrCode;

/**
 * Foyer: the projector's home screen. The Free My TV composition (For You, three media slots, the
 * glass app dock and the lower drawer) filled from Jellyfin, with the phone remote, pairing and the
 * existing HOME behaviour left intact.
 */
public class MainActivity extends Activity
        implements HomeScreen.Listener, MediaLibrary.Listener, WeatherService.Listener {

    public static volatile MainActivity instance;

    private static final String PREFS = "foyer";

    private static final String[] PREFERRED = {
            "com.google.android.youtube.tv",
            "com.google.android.youtube",
            "com.spotify.tv.android",
            "in.startv.hotstar",
            "com.github.damontecres.wholphin",
            "org.moonfin.androidtv",
            "com.netflix.ninja",
            "com.amazon.amazonvideo.livingroom",
            "com.disney.disneyplus",
            "com.zhiying.settings"
    };

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<AppEntry> apps = new ArrayList<AppEntry>();
    private final List<String> orderList = new ArrayList<String>();
    private final List<String> hiddenList = new ArrayList<String>();
    private volatile List<AppEntry> snapshot = Collections.emptyList();

    private HomeScreen screen;
    private MediaLibrary library;
    private WeatherService weather;
    private Palette palette = Palette.light();
    private FrameLayout root;
    private FrameLayout overlayHost;

    private String ip = "127.0.0.1";
    private long lastLaunchAt;
    private int lastDark = -1;

    private FrameLayout modal;
    private boolean qrVisible;
    private boolean settingsVisible;
    private boolean appsVisible;
    private ImageView qrImage;
    private TextView qrUrl;
    private TextView qrPin;
    private LinearLayout settingsAppearance;
    private LinearLayout settingsBackground;
    private LinearLayout settingsLibrary;
    private TextView settingsJellyfinInfo;

    private Bitmap wallpaper;
    private int wallpaperGen;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        instance = this;

        ip = Net.localIpv4();
        loadTilePrefs();
        library = new MediaLibrary(this);
        weather = new WeatherService(this);

        int dark = Palette.isDark(this, prefs()) ? 1 : 0;
        lastDark = dark;
        palette = dark == 1 ? Palette.dark() : Palette.light();

        screen = new HomeScreen(this, library);
        screen.setPalette(palette);
        screen.setListener(this);

        root = new FrameLayout(this);
        root.setBackgroundColor(palette.bgTop);
        root.addView(screen, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlayHost = new FrameLayout(this);
        overlayHost.setVisibility(View.GONE);
        root.addView(overlayHost, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        library.setListener(this);
        weather.setListener(this);

        refreshApps();
        screen.setAccessibility(FoyerAccessibility.ready());
        maybeLoadWallpaper();

        library.start();
        weather.start();

        screen.post(new Runnable() {
            @Override
            public void run() {
                screen.requestInitialFocus();
            }
        });
        // The accessibility service binds just after the activity; reflect that without waiting
        // for the 30 s tick.
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (screen != null) screen.setAccessibility(FoyerAccessibility.ready());
            }
        }, 2200L);
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (screen != null) screen.setAccessibility(FoyerAccessibility.ready());
            }
        }, 7000L);

        if (!Pairing.hasPaired(this)) {
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    showQr();
                }
            }, 900L);
        }

        try {
            startService(new Intent(this, RemoteService.class));
        } catch (Exception ignored) {
        }
        tick();
    }

    @Override
    protected void onResume() {
        super.onResume();
        instance = this;
        refreshApps();
        screen.setAccessibility(FoyerAccessibility.ready());
        applyThemeIfNeeded(false);
        maybeLoadWallpaper();
        library.maybeRefresh();
        weather.maybeRefresh();
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!qrVisible && !settingsVisible && !appsVisible) screen.restoreFocus();
            }
        }, 220L);
    }

    @Override
    protected void onDestroy() {
        if (instance == this) instance = null;
        if (library != null) library.shutdown();
        if (weather != null) weather.shutdown();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && screen != null && !qrVisible && !settingsVisible && !appsVisible) {
            screen.postDelayed(new Runnable() {
                @Override
                public void run() {
                    screen.restoreFocus();
                }
            }, 120L);
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ app list

    private void refreshApps() {
        List<AppEntry> fresh = queryApps(false);
        boolean same = fresh.size() == apps.size();
        if (same) {
            for (int i = 0; i < fresh.size(); i++) {
                if (!fresh.get(i).id().equals(apps.get(i).id())) {
                    same = false;
                    break;
                }
            }
        }
        if (same && !apps.isEmpty()) return;
        apps.clear();
        apps.addAll(fresh);
        snapshot = Collections.unmodifiableList(new ArrayList<AppEntry>(apps));
        if (screen != null) screen.setApps(apps);
    }

    private List<AppEntry> queryApps(boolean includeHidden) {
        PackageManager pm = getPackageManager();
        Map<String, AppEntry> byPkg = new HashMap<String, AppEntry>();

        Intent lean = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Intent plain = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> found = new ArrayList<ResolveInfo>();
        found.addAll(pm.queryIntentActivities(lean, 0));
        found.addAll(pm.queryIntentActivities(plain, 0));

        for (ResolveInfo ri : found) {
            if (ri == null || ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg == null || pkg.equals(getPackageName())) continue;
            if (byPkg.containsKey(pkg)) continue;
            CharSequence cs = ri.loadLabel(pm);
            String label = cs == null ? pkg : cs.toString().trim();
            if (label.isEmpty()) label = pkg;
            if (isJunkLabel(label)) continue;
            ComponentName cn = new ComponentName(pkg, ri.activityInfo.name);
            byPkg.put(pkg, new AppEntry(pkg, label, cn));
        }

        List<AppEntry> out = new ArrayList<AppEntry>(byPkg.values());
        Collections.sort(out, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                int oa = orderIndex(a.pkg);
                int ob = orderIndex(b.pkg);
                if (oa != ob) return oa < ob ? -1 : 1;
                int ra = prefRank(a.pkg);
                int rb = prefRank(b.pkg);
                if (ra != rb) return ra < rb ? -1 : 1;
                return a.label.compareToIgnoreCase(b.label);
            }
        });

        if (!includeHidden && !hiddenList.isEmpty()) {
            List<AppEntry> visible = new ArrayList<AppEntry>(out.size());
            for (AppEntry e : out) {
                if (!hiddenList.contains(e.pkg)) visible.add(e);
            }
            out = visible;
        }
        return out;
    }

    private int orderIndex(String pkg) {
        int i = orderList.indexOf(pkg);
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    private void loadTilePrefs() {
        String o = prefs().getString("order", "");
        String h = prefs().getString("hidden", "");
        orderList.clear();
        hiddenList.clear();
        if (o != null && !o.isEmpty()) Collections.addAll(orderList, o.split(","));
        if (h != null && !h.isEmpty()) Collections.addAll(hiddenList, h.split(","));
    }

    public void applyTiles(String orderCsv, String hiddenCsv) {
        prefs().edit()
                .putString("order", orderCsv == null ? "" : orderCsv)
                .putString("hidden", hiddenCsv == null ? "" : hiddenCsv)
                .apply();
        ui.post(new Runnable() {
            @Override
            public void run() {
                loadTilePrefs();
                refreshApps();
            }
        });
    }

    private static boolean isBlocked(AppEntry e) {
        return "com.hisilicon.miracast".equals(e.pkg) || "com.newlink.cast".equals(e.pkg);
    }

    private static boolean isRisky(AppEntry e) {
        String l = e.label == null ? "" : e.label.toLowerCase(Locale.US);
        if (l.contains("miracast") || l.contains("speaker") || l.contains("hdmi")
                || l.contains("input") || l.contains("cast")) return true;
        return "com.zhiying.settings".equals(e.pkg);
    }

    private static boolean isJunkLabel(String s) {
        if (s == null || s.length() < 6) return false;
        if (s.indexOf('_') < 0) return false;
        return s.matches("[A-Z0-9_]+");
    }

    private static int prefRank(String pkg) {
        for (int i = 0; i < PREFERRED.length; i++) {
            if (PREFERRED[i].equals(pkg)) return i;
        }
        return PREFERRED.length;
    }

    // ------------------------------------------------------------------ launch

    private void launchApp(AppEntry e, boolean confirmRisky) {
        if (e == null) return;
        if (confirmRisky && (isRisky(e) || isBlocked(e))) {
            showRiskDialog(e);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastLaunchAt < 900L) return;
        lastLaunchAt = now;

        Intent i = getPackageManager().getLaunchIntentForPackage(e.pkg);
        if (i == null) {
            i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            i.setComponent(e.component);
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (Exception ex) {
            showError("Cannot open " + e.label, "The projector refused to launch this app.");
        }
    }

    private void showRiskDialog(final AppEntry e) {
        String warning = isBlocked(e)
                ? "This input switcher can take the projector off Wi-Fi for a few minutes. "
                + "Foyer will be waiting when it returns."
                : "This app can switch the projector's input or wireless mode. "
                + "The screen may go blank or the remote may disconnect for a while.";
        new AlertDialog.Builder(this)
                .setTitle("Open " + e.label + "?")
                .setMessage(warning)
                .setPositiveButton("Open", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        launchApp(e, false);
                    }
                })
                .setNegativeButton("Cancel", null)
                .setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(android.content.DialogInterface d) {
                        if (screen != null) screen.restoreFocus();
                    }
                })
                .show();
    }

    private void openInMoonfin(MediaLibrary.Item item) {
        long now = System.currentTimeMillis();
        if (now - lastLaunchAt < 900L) return;
        lastLaunchAt = now;
        Moonfin.Result r = Moonfin.open(this, item, library == null ? "" : library.serverId());
        if (!r.ok) {
            showError("Moonfin", r.message);
        } else {
            toast(r.message);
        }
    }

    private void showError(String title, String message) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(android.content.DialogInterface d) {
                        if (screen != null) screen.restoreFocus();
                    }
                })
                .show();
    }

    private void toast(String text) {
        try {
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ home screen callbacks

    @Override
    public void onAppSelected(AppEntry app) {
        launchApp(app, true);
    }

    @Override
    public void onMediaSelected(MediaLibrary.Item item) {
        openInMoonfin(item);
    }

    @Override
    public void onTabChanged(int tab) {
    }

    @Override
    public void onTopControl(int index) {
        if (index == 0) showQr();
        else if (index == 1) openAccessibility();
        else showSettings();
    }

    @Override
    public void onDrawerAction(String action, String label) {
        if ("sources".equals(action)) openSources();
        else if ("cast".equals(action)) {
            AppEntry cast = findApp("com.newlink.cast");
            if (cast != null) showRiskDialog(cast);
            else toast("Casting is not available on this projector");
        } else if ("apps".equals(action)) showApps();
        else if ("settings".equals(action)) showSettings();
        else if ("tv".equals(action)) openTvSettings();
        else if ("pair".equals(action)) showQr();
        else if ("hdmi1".equals(action) || "hdmi2".equals(action) || "hdmi3".equals(action)) {
            openSources();
        }
    }

    @Override
    public void onDrawerOpened(boolean open) {
    }

    @Override
    public void onFocusChanged() {
    }

    private AppEntry findApp(String pkg) {
        for (AppEntry e : snapshot) {
            if (e.pkg.equals(pkg)) return e;
        }
        return null;
    }

    // ------------------------------------------------------------------ system settings / inputs

    private void openSources() {
        Intent[] tries = new Intent[]{
                new Intent("com.hisilicon.tvsetting.action.quicksource")
                        .addCategory(Intent.CATEGORY_DEFAULT),
                new Intent("android.cmcc.settings.TV.CHANGE_SCREEN")
                        .addCategory(Intent.CATEGORY_DEFAULT),
                new Intent().setComponent(new ComponentName(
                        "com.newlink.nlsource", "com.newlink.nlsource.MainActivity")),
                new Intent().setComponent(new ComponentName(
                        "com.zhiying.sourceui", "com.zhiying.sourceui.SourceUIActivity"))
        };
        for (Intent i : tries) {
            try {
                if (getPackageManager().resolveActivity(i, 0) == null) continue;
                startActivity(i);
                return;
            } catch (Exception ignored) {
            }
        }
        toast("The projector has no source menu available");
    }

    private void openTvSettings() {
        Intent[] tries = new Intent[]{
                new Intent().setComponent(new ComponentName(
                        "com.android.tv.settings", "com.android.tv.settings.MainSettings")),
                new Intent(android.provider.Settings.ACTION_SETTINGS),
                new Intent().setComponent(new ComponentName(
                        "com.hisilicon.tvsetting", "com.hisilicon.tvsetting.main.SettingActivity"))
        };
        for (Intent i : tries) {
            try {
                if (getPackageManager().resolveActivity(i, 0) == null) continue;
                startActivity(i);
                return;
            } catch (Exception ignored) {
            }
        }
        toast("TV settings is not available");
    }

    private void openAccessibility() {
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Exception e) {
            toast("Accessibility settings not available");
        }
    }

    // ------------------------------------------------------------------ pairing

    public void showQr() {
        closeModal();
        qrVisible = true;
        String fresh = Net.localIpv4();
        if (fresh != null && !fresh.startsWith("127.")) ip = fresh;
        final String url = "http://" + ip + ":" + LauncherServer.PORT;
        String payload = url + "/?pin=" + Pairing.pin(this);

        FrameLayout scrim = scrim();
        LinearLayout card = card(500);
        card.addView(label("Pair your phone", 24f, palette.ink, true));
        TextView sub = label("Scan the code, or type the address and PIN.", 13f, palette.dim, false);
        sub.setPadding(0, dp(4), 0, dp(14));
        card.addView(sub);

        qrImage = new ImageView(this);
        qrImage.setBackgroundColor(0xFFFFFFFF);
        qrImage.setPadding(dp(8), dp(8), dp(8), dp(8));
        qrImage.setContentDescription("Pairing QR code");
        Bitmap bmp = buildQrBitmap(payload, 560);
        if (bmp != null) qrImage.setImageBitmap(bmp);
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(dp(228), dp(228));
        qlp.gravity = Gravity.CENTER_HORIZONTAL;
        card.addView(qrImage, qlp);

        qrUrl = label(url, 15f, palette.ink, false);
        qrUrl.setGravity(Gravity.CENTER_HORIZONTAL);
        qrUrl.setPadding(0, dp(14), 0, 0);
        card.addView(qrUrl);

        qrPin = label(Pairing.pin(this), 30f, palette.accent, true);
        qrPin.setLetterSpacing(0.18f);
        qrPin.setGravity(Gravity.CENTER_HORIZONTAL);
        card.addView(qrPin);

        TextView hint = label("or type the address and PIN on your phone  ·  BACK closes",
                11f, palette.faint, false);
        hint.setGravity(Gravity.CENTER_HORIZONTAL);
        hint.setPadding(0, dp(10), 0, 0);
        card.addView(hint);

        scrim.addView(card, centered(500));
        showModal(scrim);
        scrim.requestFocus();
    }

    public void hideQr() {
        qrVisible = false;
        closeModal();
        if (screen != null) screen.restoreFocus();
    }

    public void onPaired() {
        Pairing.markPaired(this);
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (qrVisible) hideQr();
                toast("Phone paired");
            }
        });
    }

    private static Bitmap buildQrBitmap(String text, int targetPx) {
        try {
            QrCode qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM);
            int quiet = 4;
            int modules = qr.size + quiet * 2;
            int scale = Math.max(1, targetPx / modules);
            int dim = modules * scale;
            int[] px = new int[dim * dim];
            for (int y = 0; y < dim; y++) {
                int my = y / scale - quiet;
                for (int x = 0; x < dim; x++) {
                    int mx = x / scale - quiet;
                    boolean dark = mx >= 0 && my >= 0 && mx < qr.size && my < qr.size
                            && qr.getModule(mx, my);
                    px[y * dim + x] = dark ? 0xFF000000 : 0xFFFFFFFF;
                }
            }
            return Bitmap.createBitmap(px, dim, dim, Bitmap.Config.ARGB_8888);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ settings

    private void showSettings() {
        closeModal();
        settingsVisible = true;

        FrameLayout scrim = scrim();
        LinearLayout card = card(540);

        TextView title = label("Settings", 22f, palette.ink, true);
        card.addView(title);
        TextView sub = label("Projector · Foyer", 11f, palette.dim, false);
        sub.setPadding(0, dp(2), 0, dp(10));
        card.addView(sub);

        card.addView(settingRow("Pair phone", "QR · PIN", new Runnable() {
            @Override
            public void run() {
                settingsVisible = false;
                showQr();
            }
        }));

        settingsAppearance = settingRow("Appearance", themeLabel(), new Runnable() {
            @Override
            public void run() {
                cycleTheme();
            }
        });
        card.addView(settingsAppearance);

        settingsBackground = settingRow("Background", backgroundLabel(), new Runnable() {
            @Override
            public void run() {
                toggleBackground();
            }
        });
        card.addView(settingsBackground);

        settingsLibrary = settingRow("Jellyfin library", libraryValue(), new Runnable() {
            @Override
            public void run() {
                if (library != null) library.refreshNow();
                toast("Refreshing Jellyfin");
            }
        });
        card.addView(settingsLibrary);

        card.addView(settingRow("TV settings", "", new Runnable() {
            @Override
            public void run() {
                settingsVisible = false;
                closeModal();
                openTvSettings();
            }
        }));
        card.addView(settingRow("Accessibility", FoyerAccessibility.ready() ? "on" : "off",
                new Runnable() {
                    @Override
                    public void run() {
                        settingsVisible = false;
                        closeModal();
                        openAccessibility();
                    }
                }));

        settingsJellyfinInfo = label(jellyfinInfo(), 11f, palette.faint, false);
        settingsJellyfinInfo.setPadding(dp(14), dp(10), dp(14), dp(6));
        card.addView(settingsJellyfinInfo);

        card.addView(settingRow("Close", "", new Runnable() {
            @Override
            public void run() {
                hideSettings();
            }
        }));

        TextView hint = label("BACK closes  ·  D-pad chooses", 10f, palette.faint, false);
        hint.setPadding(dp(14), dp(10), 0, 0);
        card.addView(hint);

        scrim.addView(card, centered(540));
        showModal(scrim);
        View first = card.getChildAt(3);
        if (first != null) first.requestFocus();
    }

    private void hideSettings() {
        settingsVisible = false;
        closeModal();
        if (screen != null) screen.restoreFocus();
    }

    private String themeLabel() {
        String mode = prefs().getString("theme", "light");
        if ("dark".equals(mode)) return "Dark";
        if ("auto".equals(mode)) return "Auto (sunrise/sunset)";
        return "Light";
    }

    private void cycleTheme() {
        String mode = prefs().getString("theme", "light");
        if ("light".equals(mode)) mode = "dark";
        else if ("dark".equals(mode)) mode = "auto";
        else mode = "light";
        prefs().edit().putString("theme", mode).apply();
        lastDark = -1;
        applyThemeIfNeeded(true);
        if (settingsVisible) {
            hideSettings();
            showSettings();
        }
    }

    private void applyThemeIfNeeded(boolean force) {
        boolean dark = Palette.isDark(this, prefs());
        int now = dark ? 1 : 0;
        if (!force && now == lastDark) return;
        lastDark = now;
        palette = dark ? Palette.dark() : Palette.light();
        if (root != null) root.setBackgroundColor(palette.bgTop);
        if (screen != null) screen.setPalette(palette);
    }

    private String backgroundLabel() {
        return usePhoto() ? "Photo" : "Ambient";
    }

    private boolean usePhoto() {
        return "photo".equals(prefs().getString("background", "ambient")) && wallpaper != null;
    }

    private void toggleBackground() {
        if (wallpaper == null) {
            toast("Upload a photo from the phone remote first");
            return;
        }
        boolean photo = usePhoto();
        prefs().edit().putString("background", photo ? "ambient" : "photo").apply();
        screen.setWallpaper(wallpaper, !photo);
        if (settingsBackground != null && settingsBackground.getChildCount() > 1) {
            ((TextView) settingsBackground.getChildAt(1)).setText(backgroundLabel());
        }
    }

    private String libraryValue() {
        if (library == null || !library.configured()) return "not configured";
        long at = library.lastOk();
        if (at <= 0) return library.isLoading() ? "loading…" : "no cache";
        long mins = Math.max(0, (System.currentTimeMillis() - at) / 60000L);
        return mins < 60 ? (mins + " min ago") : ((mins / 60) + " h ago");
    }

    private String jellyfinInfo() {
        StringBuilder b = new StringBuilder();
        b.append("Moonfin ").append(Moonfin.installed(this) ? Moonfin.version(this) : "not installed");
        b.append("  ·  Jellyfin ").append(library != null && library.configured()
                ? prefs().getString("jellyfin_url", "") : "not configured");
        return b.toString();
    }

    // ------------------------------------------------------------------ all apps

    private void showApps() {
        closeModal();
        appsVisible = true;
        FrameLayout scrim = scrim();
        LinearLayout card = card(880);

        TextView title = label("All apps", 22f, palette.ink, true);
        card.addView(title);
        TextView sub = label("Every launcher on the projector, including the ones hidden from the dock.",
                11f, palette.dim, false);
        sub.setPadding(0, dp(2), 0, dp(10));
        card.addView(sub);

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(6);
        List<AppEntry> all = queryApps(true);
        final List<LinearLayout> cells = new ArrayList<LinearLayout>();
        for (final AppEntry e : all) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setFocusable(true);
            cell.setClickable(true);
            cell.setPadding(dp(6), dp(10), dp(6), dp(8));
            cell.setBackground(rowBackground(false));
            cell.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean has) {
                    v.setBackground(rowBackground(has));
                }
            });
            ImageView icon = new ImageView(this);
            try {
                icon.setImageDrawable(getPackageManager().getApplicationIcon(e.pkg));
            } catch (Exception ignored) {
            }
            cell.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));
            TextView name = label(e.label, 11f, palette.ink, false);
            name.setMaxLines(2);
            name.setGravity(Gravity.CENTER_HORIZONTAL);
            name.setPadding(0, dp(6), 0, 0);
            cell.addView(name, new LinearLayout.LayoutParams(dp(122),
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            cell.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    appsVisible = false;
                    closeModal();
                    launchApp(e, true);
                }
            });
            GridLayout.LayoutParams glp = new GridLayout.LayoutParams();
            glp.width = dp(138);
            glp.height = dp(104);
            grid.addView(cell, glp);
            cells.add(cell);
        }
        card.addView(grid);

        TextView hint = label("BACK closes  ·  OK opens", 10f, palette.faint, false);
        hint.setPadding(0, dp(10), 0, 0);
        card.addView(hint);

        scrim.addView(card, centered(880));
        showModal(scrim);
        if (!cells.isEmpty()) cells.get(0).requestFocus();
    }

    private void hideApps() {
        appsVisible = false;
        closeModal();
        if (screen != null) screen.restoreFocus();
    }

    // ------------------------------------------------------------------ modal plumbing

    private FrameLayout scrim() {
        FrameLayout f = new FrameLayout(this);
        f.setBackgroundColor(palette.dark ? 0xCC000000 : 0x99000000);
        f.setClickable(true);
        f.setFocusable(true);
        f.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeModal();
            }
        });
        return f;
    }

    private LinearLayout card(int widthDp) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(26), dp(22), dp(26), dp(22));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(palette.dark ? 0xFF191C21 : 0xFFFDFDFD);
        bg.setCornerRadius(dp(22));
        card.setBackground(bg);
        card.setElevation(dp(20));
        card.setFocusable(false);
        return card;
    }

    private FrameLayout.LayoutParams centered(int widthDp) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(widthDp),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        return lp;
    }

    private void showModal(View content) {
        overlayHost.removeAllViews();
        overlayHost.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlayHost.setVisibility(View.VISIBLE);
    }

    private void closeModal() {
        if (overlayHost != null) {
            overlayHost.removeAllViews();
            overlayHost.setVisibility(View.GONE);
        }
        qrVisible = false;
        settingsVisible = false;
        appsVisible = false;
        if (screen != null) screen.restoreFocus();
    }

    private TextView label(String text, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        return t;
    }

    private LinearLayout settingRow(String labelText, String value, final Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setPadding(dp(14), dp(11), dp(14), dp(11));
        row.setBackground(rowBackground(false));

        TextView l = label(labelText, 15f, palette.ink, false);
        row.addView(l, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = label(value, 12f, palette.dim, false);
        v.setGravity(Gravity.END);
        row.addView(v);
        row.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean has) {
                view.setBackground(rowBackground(has));
            }
        });
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v2) {
                if (action != null) action.run();
            }
        });
        return row;
    }

    private android.graphics.drawable.Drawable rowBackground(boolean focused) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(12));
        if (focused) g.setColor(palette.dark ? 0x33FFFFFF : 0x141D1D1F);
        else g.setColor(0x00000000);
        return g;
    }

    private FrameLayout.LayoutParams centered(int widthDp, int heightDp) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(widthDp), dp(heightDp));
        lp.gravity = Gravity.CENTER;
        return lp;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------ wallpaper

    private void maybeLoadWallpaper() {
        String path = prefs().getString("wallpaper", null);
        if (path == null || !new File(path).exists()) {
            wallpaper = null;
            if (screen != null) screen.setWallpaper(null, false);
            return;
        }
        if (wallpaper != null) {
            screen.setWallpaper(wallpaper, usePhoto());
            return;
        }
        final String p = path;
        final int gen = ++wallpaperGen;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                Bitmap bmp = null;
                try {
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(p, bounds);
                    int sample = 1;
                    while (bounds.outWidth / sample > 1280 || bounds.outHeight / sample > 720) {
                        sample *= 2;
                    }
                    BitmapFactory.Options opt = new BitmapFactory.Options();
                    opt.inSampleSize = sample;
                    bmp = BitmapFactory.decodeFile(p, opt);
                } catch (Throwable ignored) {
                }
                final Bitmap result = bmp;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != wallpaperGen) return;
                        wallpaper = result;
                        if (screen != null) screen.setWallpaper(result, usePhoto());
                    }
                });
            }
        }, "foyer-wallpaper");
        t.setDaemon(true);
        t.start();
    }

    public boolean saveWallpaper(byte[] data) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false;

            int sample = 1;
            while (bounds.outWidth / sample > 1920 || bounds.outHeight / sample > 1080) sample *= 2;

            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, opt);
            if (bmp == null) return false;

            File f = new File(getFilesDir(), "wallpaper.jpg");
            FileOutputStream fos = new FileOutputStream(f);
            bmp.compress(Bitmap.CompressFormat.JPEG, 86, fos);
            fos.close();
            bmp.recycle();

            prefs().edit().putString("wallpaper", f.getAbsolutePath())
                    .putString("background", "photo").apply();
            wallpaper = null;
            ui.post(new Runnable() {
                @Override
                public void run() {
                    maybeLoadWallpaper();
                }
            });
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public void clearWallpaper() {
        String path = prefs().getString("wallpaper", null);
        if (path != null) {
            try {
                new File(path).delete();
            } catch (Exception ignored) {
            }
        }
        prefs().edit().remove("wallpaper").putString("background", "ambient").apply();
        wallpaper = null;
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (screen != null) screen.setWallpaper(null, false);
            }
        });
    }

    // ------------------------------------------------------------------ jellyfin status from the library

    @Override
    public void onLibraryChanged() {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (screen == null || library == null) return;
                screen.setLibrary(library.tabs());
                boolean configured = library.configured();
                boolean offline = configured && !library.problem().isEmpty();
                if (configured && library.lastOk() == 0 && !library.isLoading()) offline = true;
                screen.setJellyfinStatus(configured, library.isLoading(), offline,
                        library.empty(), library.lastOk());
                if (settingsLibrary != null) {
                    TextView v = settingsLibrary.getChildCount() > 1
                            ? (TextView) settingsLibrary.getChildAt(1) : null;
                    if (v != null) v.setText(libraryValue());
                }
                if (settingsJellyfinInfo != null) settingsJellyfinInfo.setText(jellyfinInfo());
            }
        });
    }

    @Override
    public void onWeatherChanged() {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (screen != null && weather != null) {
                    screen.setWeather(weather.data(), weather.problem());
                }
            }
        });
    }

    public void setJellyfin(String url, String key) {
        if (library != null) library.setConfig(url, key);
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (screen != null) screen.restoreFocus();
            }
        });
    }

    // ------------------------------------------------------------------ tick

    private void tick() {
        updateTime();
        String fresh = Net.localIpv4();
        if (fresh != null && !fresh.startsWith("127.")) ip = fresh;
        if (screen != null) screen.setAccessibility(FoyerAccessibility.ready());
        applyThemeIfNeeded(false);
        if (library != null) library.maybeRefresh();
        if (weather != null) weather.maybeRefresh();
        if (library != null && screen != null) {
            screen.setJellyfinStatus(library.configured(), library.isLoading(),
                    library.configured() && !library.problem().isEmpty(), library.empty(),
                    library.lastOk());
        }
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                tick();
            }
        }, 30000L);
    }

    private void updateTime() {
        // The reference has no clock; the time still gates the automatic theme.
    }

    // ------------------------------------------------------------------ keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;

        if (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_INFO) {
            if (down) {
                if (qrVisible) hideQr();
                else if (settingsVisible || appsVisible) closeModal();
                else showQr();
            }
            return true;
        }

        if (qrVisible || settingsVisible || appsVisible) {
            if (code == KeyEvent.KEYCODE_BACK) {
                if (down) closeModal();
                return true;
            }
            return super.dispatchKeyEvent(event);
        }

        if (down) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    screen.move("up");
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    screen.move("down");
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    screen.move("left");
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    screen.move("right");
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (event.getRepeatCount() == 0) screen.move("ok");
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (screen.back()) return true;
                    break;
                default:
                    break;
            }
        } else {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    return true;
                default:
                    break;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    // ------------------------------------------------------------------ remote host API

    public String stateJson() {
        StringBuilder b = new StringBuilder(700);
        int sel = screen == null ? 0 : screen.selectedApp();
        if (sel < 0) sel = 0;
        if (!apps.isEmpty() && sel >= apps.size()) sel = apps.size() - 1;
        b.append("{\"ok\":true,\"pin\":\"").append(Json.esc(pin()))
                .append("\",\"sel\":").append(sel)
                .append(",\"cols\":").append(apps.size())
                .append(",\"ip\":\"").append(Json.esc(ip))
                .append("\",\"port\":").append(LauncherServer.PORT)
                .append(",\"a11y\":").append(FoyerAccessibility.ready())
                .append(",\"jellyfin\":").append(library != null && library.configured())
                .append(",\"jellyfinUrl\":\"").append(Json.esc(prefs().getString("jellyfin_url", ""))).append("\"")
                .append(",\"name\":\"").append(Json.esc(android.os.Build.MODEL)).append("\"")
                .append(",\"appearance\":\"").append(palette.dark ? "dark" : "light").append("\"")
                // Whether the on-device input helper is up. Deliberately NOT derived from a11y:
                // accessibility being enabled does not give an app any way to press D-pad keys.
                .append(",\"input\":{\"helper\":").append(InputRoute.helperAvailable(this)).append("}");
        if (screen != null) b.append(",\"focus\":{").append(screen.focusJson()).append("}");
        b.append(",\"tiles\":[");
        List<AppEntry> list = snapshot;
        for (int i = 0; i < list.size(); i++) {
            AppEntry e = list.get(i);
            if (i > 0) b.append(',');
            b.append("{\"id\":\"").append(Json.esc(e.id()))
                    .append("\",\"label\":\"").append(Json.esc(e.label))
                    .append("\",\"pkg\":\"").append(Json.esc(e.pkg))
                    .append("\",\"risky\":").append(isRisky(e))
                    .append(",\"blocked\":").append(isBlocked(e)).append("}");
        }
        b.append("],\"all\":[");
        List<AppEntry> every = queryApps(true);
        for (int i = 0; i < every.size(); i++) {
            AppEntry e = every.get(i);
            if (i > 0) b.append(',');
            b.append("{\"id\":\"").append(Json.esc(e.id()))
                    .append("\",\"label\":\"").append(Json.esc(e.label))
                    .append("\",\"pkg\":\"").append(Json.esc(e.pkg))
                    .append("\",\"hidden\":").append(hiddenList.contains(e.pkg))
                    .append(",\"risky\":").append(isRisky(e))
                    .append(",\"blocked\":").append(isBlocked(e))
                    .append("}");
        }
        b.append("]}");
        return b.toString();
    }

    public int selected() {
        return screen == null ? 0 : screen.selectedApp();
    }

    public String pin() {
        return Pairing.pin(this);
    }

    public String token() {
        return Pairing.token(this);
    }

    public String resetPin() {
        String p = Pairing.rotatePin(this);
        return p;
    }

    public void goHome() {
        ui.post(new Runnable() {
            @Override
            public void run() {
                Intent i = new Intent(Intent.ACTION_MAIN);
                i.addCategory(Intent.CATEGORY_HOME);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(i);
                } catch (Exception ignored) {
                }
            }
        });
    }

    public void launchById(final String id) {
        if (id == null) return;
        for (AppEntry e : snapshot) {
            if (id.equals(e.id())) {
                final AppEntry found = e;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        launchApp(found, false);
                    }
                });
                return;
            }
        }
    }

    /** Applies a command to Foyer's own focus model. False means it was not applied in time. */
    public boolean move(final String dir) {
        if (dir == null) return false;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (screen == null) return false;
            screen.move(dir);
            return true;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] applied = new boolean[1];
        ui.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (screen != null) {
                        screen.move(dir);
                        applied[0] = true;
                    }
                } finally {
                    latch.countDown();
                }
            }
        });
        try {
            // A null screen (activity being torn down) must not be reported as applied.
            return latch.await(2000, TimeUnit.MILLISECONDS) && applied[0];
        } catch (InterruptedException ignored) {
            return false;
        }
    }

    /**
     * True only with positive evidence that Foyer's own window owns remote input right now.
     *
     * <p>Used solely by the fallback path taken when the input helper is not running: without the
     * helper the remote can still drive Foyer itself, but it must never touch the focus model while
     * another app is in front (that was the defect). Evidence must be live: the accessibility
     * window-change event names the package that last took a window (it also covers Foyer's own
     * dialogs, which are separate windows and would otherwise look like "not Foyer"), and the
     * service must still be bound - a stale package name from before an unbind proves nothing.
     * Without fresh evidence, window focus is the only local signal; if that is false too, the
     * caller reports the command as unavailable instead of guessing.
     */
    public boolean foyerOwnsInput() {
        if (screen == null || !FoyerAccessibility.ready()) {
            // No accessibility: fall back to window focus only, which is false whenever another
            // app is in front.
            return hasWindowFocus();
        }
        String pkg = FoyerAccessibility.lastActivePackage();
        long at = FoyerAccessibility.lastActiveAt();
        if (pkg != null && SystemClock.uptimeMillis() - at < 1500) {
            return getPackageName().equals(pkg);
        }
        return hasWindowFocus();
    }

    // ------------------------------------------------------------------ global remote

    public boolean volume(String dir) {
        if (dir == null) return false;
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am == null) return false;
            int action;
            if ("up".equals(dir)) action = AudioManager.ADJUST_RAISE;
            else if ("down".equals(dir)) action = AudioManager.ADJUST_LOWER;
            else if ("mute".equals(dir)) action = AudioManager.ADJUST_TOGGLE_MUTE;
            else return false;
            if (action == AudioManager.ADJUST_TOGGLE_MUTE) {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, action, AudioManager.FLAG_SHOW_UI);
                return true;
            }
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int now = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            int step = Math.max(1, Math.round(max / 20f));
            int next = action == AudioManager.ADJUST_RAISE
                    ? Math.min(max, now + step)
                    : Math.max(0, now - step);
            am.setStreamVolume(AudioManager.STREAM_MUSIC, next, AudioManager.FLAG_SHOW_UI);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean globalAction(String action) {
        return FoyerAccessibility.global(action);
    }

    public boolean tap(float nx, float ny) {
        return FoyerAccessibility.tap(nx, ny);
    }
}
