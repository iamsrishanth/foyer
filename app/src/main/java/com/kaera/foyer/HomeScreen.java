package com.kaera.foyer;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Free My TV shaped home: "For You", tabs, one wide plus two portrait media slots, the glass
 * app dock and the lower Inputs / Shortcuts / Weather drawer.
 *
 * <p>The screen owns focus and motion; the activity owns launching, overlays and the phone API.
 */
public class HomeScreen extends FrameLayout {

    public interface Listener {
        void onAppSelected(AppEntry app);

        void onMediaSelected(MediaLibrary.Item item);

        void onTabChanged(int tab);

        void onTopControl(int index);

        void onDrawerAction(String action, String label);

        void onDrawerOpened(boolean open);

        void onFocusChanged();
    }

    private static final String[] DEFAULT_KEYS = {"shows", "movies", "anime", "dramas"};
    private static final String[] DEFAULT_LABELS = {"Shows", "Movies", "Anime", "Dramas"};
    private static final int MAX_TABS = 8;

    public static final int ZONE_TABS = 0;
    public static final int ZONE_TOP = 1;
    public static final int ZONE_MEDIA = 2;
    public static final int ZONE_DOCK = 3;
    public static final int ZONE_INPUTS = 4;
    public static final int ZONE_SHORTCUTS = 5;

    private static final float DRAWER_SHIFT = 278f;

    private final MediaLibrary library;
    private Listener listener;

    private BackgroundView background;
    private DesignLayout homeLayer;
    private DesignLayout drawerLayer;
    private DesignLayout dockLayer;

    private TextView headline;
    private TextView[] titles = new TextView[3];
    private TextView meta;
    private TextView status;
    private TextView remoteHint;
    private TabBar tabs;
    private final CircleButton[] top = new CircleButton[3];
    private final ArtView[] art = new ArtView[3];
    private GlassView glass;
    private DockRow dock;
    private TextView inputsLabel;
    private TextView shortcutsLabel;
    private TextView closeHint;
    private DesignLayout inputsRow;
    private DesignLayout shortcutsRow;
    private final List<MiniTile> inputTiles = new ArrayList<MiniTile>();
    private final List<MiniTile> shortcutTiles = new ArrayList<MiniTile>();
    private WeatherView weather;

    private final List<AppEntry> apps = new ArrayList<AppEntry>();
    private final List<MediaLibrary.Tab> tabsData = new ArrayList<MediaLibrary.Tab>(4);
    private final int[] window = new int[MAX_TABS];

    private int tab = 0;
    private int zone = ZONE_DOCK;
    private int topIndex = 0;
    private int mediaIndex = 0;
    private int dockIndex = 0;
    private int inputIndex = 0;
    private int shortcutIndex = 0;
    private boolean drawerOpen;

    private Palette palette = Palette.light();
    private float k = 1f;
    private boolean accessibility = true;
    private boolean jellyfinConfigured;
    private boolean jellyfinOffline;
    private boolean jellyfinEmpty;
    private long jellyfinCachedAt;
    private boolean libraryLoading;

    private final Spring drawerSpring = new Spring(0.46f, 0.9f, 0f);
    private boolean frameScheduled;
    private long lastFrameNs;

    public HomeScreen(Context c, MediaLibrary lib) {
        super(c);
        library = lib;
        setClipChildren(false);
        setClipToPadding(false);
        build();
    }

    public void setListener(Listener l) {
        listener = l;
    }

    // ------------------------------------------------------------------ build

    private void build() {
        background = new BackgroundView(getContext());
        addView(background, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        homeLayer = new DesignLayout(getContext());
        homeLayer.setClipChildren(false);
        addView(homeLayer, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        drawerLayer = new DesignLayout(getContext());
        drawerLayer.setClipChildren(false);
        drawerLayer.setVisibility(INVISIBLE);
        addView(drawerLayer, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        dockLayer = new DesignLayout(getContext());
        dockLayer.setClipChildren(false);
        addView(dockLayer, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        headline = text(34f, true);
        headline.setText("For You");
        homeLayer.add(headline, 40f, 31f, 420f, 48f);

        tabs = new TabBar(getContext());
        tabs.setLabels(DEFAULT_LABELS);
        tabs.setActive(tab);
        homeLayer.add(tabs, 28f, 79f, 480f, 34f);

        int[] icons = {Icons.PHONE, Icons.REMOTE, Icons.GEAR};
        String[] descriptions = {"Pair a phone", "Remote control status", "Settings"};
        for (int i = 0; i < 3; i++) {
            top[i] = new CircleButton(getContext());
            final int which = i;
            top[i].setIcon(icons[i], descriptions[i]);
            top[i].setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (listener != null) listener.onTopControl(which);
                }
            });
            top[i].setOnFocusChangeListener(new OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean has) {
                    if (has && !syncing) {
                        zone = ZONE_TOP;
                        topIndex = which;
                        applyFocus();
                        notifyFocus();
                    }
                }
            });
            homeLayer.add(top[i], 798f + i * 44f, 34f, 34f, 34f);
        }

        float[] xs = {40f, 580f, 760f};
        float[] ws = {520f, 160f, 160f};
        for (int i = 0; i < 3; i++) {
            art[i] = new ArtView(getContext(), library, i == 0);
            final int which = i;
            art[i].setPlaceholder(i == 0 ? "No titles yet" : "No titles yet");
            art[i].setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (syncing) return;
                    zone = ZONE_MEDIA;
                    mediaIndex = which;
                    applyFocus();
                    if (listener != null) {
                        MediaLibrary.Item item = art[which].item();
                        if (item != null) listener.onMediaSelected(item);
                    }
                }
            });
            homeLayer.add(art[i], xs[i], 131f, ws[i], 220f);

            titles[i] = text(18f, false);
            titles[i].setMaxLines(1);
            titles[i].setEllipsize(TextUtils.TruncateAt.END);
            titles[i].setGravity(Gravity.TOP | Gravity.START);
            homeLayer.add(titles[i], xs[i], 358f, ws[i], 28f);
        }

        meta = text(11f, false);
        meta.setMaxLines(1);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        homeLayer.add(meta, 40f, 383f, 520f, 18f);

        status = text(9.5f, false);
        status.setMaxLines(1);
        homeLayer.add(status, 40f, 506f, 720f, 18f);

        remoteHint = text(10f, false);
        remoteHint.setText("Remote off · fix in settings");
        remoteHint.setTextColor(palette.amber);
        remoteHint.setGravity(Gravity.END);
        remoteHint.setVisibility(GONE);
        homeLayer.add(remoteHint, 650f, 76f, 270f, 18f);

        buildDock();
        buildDrawer();
        applyScale();
        applyPalette();
        applyFocus();
    }

    private void buildDock() {
        glass = new GlassView(getContext());
        dockLayer.add(glass, 20f, 406f, 920f, 94f);

        dock = new DockRow(getContext());
        dock.setListener(new DockRow.Listener() {
            @Override
            public void onDockClicked(int index) {
                dockIndex = index;
                zone = ZONE_DOCK;
                applyFocus();
                if (listener != null) {
                    AppEntry e = dock.appAt(index);
                    if (e != null) listener.onAppSelected(e);
                }
            }
        });
        dockLayer.add(dock, 20f, 406f, 920f, 94f);
    }

    private void buildDrawer() {
        inputsLabel = text(11f, true);
        inputsLabel.setText("Inputs");
        drawerLayer.add(inputsLabel, 40f, 242f, 260f, 18f);

        shortcutsLabel = text(11f, true);
        shortcutsLabel.setText("Shortcuts");
        drawerLayer.add(shortcutsLabel, 550f, 242f, 260f, 18f);

        closeHint = text(10f, false);
        closeHint.setText("▲ Close");
        closeHint.setGravity(Gravity.END);
        drawerLayer.add(closeHint, 800f, 243f, 120f, 16f);

        inputsRow = null;
        shortcutsRow = null;

        weather = new WeatherView(getContext());
        drawerLayer.add(weather, 40f, 332f, 880f, 146f);

        setInputs(defaultInputs());
        setShortcuts(defaultShortcuts());
    }

    private List<MiniTile> defaultInputs() {
        List<MiniTile> list = new ArrayList<MiniTile>(5);
        list.add(new MiniTile(getContext(), Icons.INPUT, "Sources", true, "sources"));
        list.add(new MiniTile(getContext(), Icons.INPUT, "HDMI 1", false, "hdmi1"));
        list.add(new MiniTile(getContext(), Icons.INPUT, "HDMI 2", false, "hdmi2"));
        list.add(new MiniTile(getContext(), Icons.INPUT, "HDMI 3", false, "hdmi3"));
        list.add(new MiniTile(getContext(), Icons.CAST, "Cast", true, "cast"));
        return list;
    }

    private List<MiniTile> defaultShortcuts() {
        List<MiniTile> list = new ArrayList<MiniTile>(4);
        list.add(new MiniTile(getContext(), Icons.GRID, "All Apps", true, "apps"));
        list.add(new MiniTile(getContext(), Icons.GEAR, "Home Settings", true, "settings"));
        list.add(new MiniTile(getContext(), Icons.TV, "TV Settings", true, "tv"));
        list.add(new MiniTile(getContext(), Icons.PAIR, "Pair Remote", true, "pair"));
        return list;
    }

    private void setInputs(List<MiniTile> list) {
        for (MiniTile old : inputTiles) drawerLayer.removeView(old);
        inputTiles.clear();
        inputTiles.addAll(list);
        for (int i = 0; i < list.size(); i++) {
            final MiniTile t = list.get(i);
            t.setScale(k);
            t.setPalette(palette);
            t.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    activateDrawer(t);
                }
            });
            drawerLayer.add(t, 40f + i * 105f, 266f, 94f, 60f);
        }
    }

    private void setShortcuts(List<MiniTile> list) {
        for (MiniTile old : shortcutTiles) drawerLayer.removeView(old);
        shortcutTiles.clear();
        shortcutTiles.addAll(list);
        for (int i = 0; i < list.size(); i++) {
            final MiniTile t = list.get(i);
            t.setScale(k);
            t.setPalette(palette);
            t.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    activateDrawer(t);
                }
            });
            drawerLayer.add(t, 560f + i * 94f, 266f, 86f, 60f);
        }
    }

    private TextView text(float size, boolean bold) {
        TextView t = new TextView(getContext());
        t.setIncludeFontPadding(false);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * k);
        t.setTextColor(palette.ink);
        t.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        t.setSingleLine(true);
        return t;
    }

    // ------------------------------------------------------------------ data

    public void setApps(List<AppEntry> fresh) {
        apps.clear();
        if (fresh != null) apps.addAll(fresh);
        if (dockIndex >= apps.size()) dockIndex = Math.max(0, apps.size() - 1);
        dock.setApps(apps, dockIndex);
        if (zone == ZONE_DOCK) dock.setFocusIndex(dockIndex, true);
        if (apps.isEmpty()) {
            if (zone == ZONE_DOCK) zone = ZONE_MEDIA;
        }
        updateStatus();
    }

    public void setLibrary(List<MediaLibrary.Tab> fresh) {
        List<String> oldSignature = signature(tabsData);

        // Remember the visible title in every library so a refresh does not move it.
        Map<String, String> anchors = new HashMap<String, String>();
        for (int t = 0; t < tabsData.size(); t++) {
            MediaLibrary.Tab old = tabsData.get(t);
            int index = window[t] + Math.min(mediaIndex, 2);
            if (old != null && index < old.items.size()) {
                anchors.put(old.key, old.items.get(index).id);
            }
        }

        if (fresh != null && !fresh.isEmpty()) {
            tabsData.clear();
            tabsData.addAll(fresh);
            String[] labels = new String[fresh.size()];
            for (int i = 0; i < fresh.size(); i++) labels[i] = fresh.get(i).label;
            tabs.setLabels(labels);
            if (tab >= fresh.size()) tab = Math.max(0, fresh.size() - 1);
            tabs.setActive(tab);
        }

        for (int t = 0; t < tabsData.size() && t < window.length; t++) {
            MediaLibrary.Tab data = tabsData.get(t);
            int maxStart = Math.max(0, data.items.size() - 1);
            String anchor = anchors.get(data.key);
            if (anchor != null) {
                int idx = indexOf(data, anchor);
                if (idx >= 0) {
                    window[t] = clamp(idx - Math.min(mediaIndex, 2), 0, maxStart);
                    continue;
                }
            }
            window[t] = clamp(window[t], 0, maxStart);
        }
        for (int t = tabsData.size(); t < window.length; t++) window[t] = 0;

        if (!signature(tabsData).equals(oldSignature)) {
            rebuildMedia(false);
        } else {
            updateTitles();
        }
        for (ArtView a : art) a.retryIfMissing();
        updateStatus();
    }

    private static List<String> signature(List<MediaLibrary.Tab> tabs) {
        List<String> out = new ArrayList<String>();
        for (MediaLibrary.Tab t : tabs) {
            out.add("[" + t.key + "]");
            for (MediaLibrary.Item i : t.items) out.add(i.id);
            out.add("|");
        }
        return out;
    }

    private static int indexOf(MediaLibrary.Tab tab, String id) {
        for (int i = 0; i < tab.items.size(); i++) {
            if (tab.items.get(i).id.equals(id)) return i;
        }
        return -1;
    }

    private MediaLibrary.Tab tabAt(int t) {
        return t >= 0 && t < tabsData.size() ? tabsData.get(t) : null;
    }

    private int tabCount() {
        return tabsData.isEmpty() ? DEFAULT_LABELS.length : tabsData.size();
    }

    private String tabKey(int t) {
        MediaLibrary.Tab data = tabAt(t);
        if (data != null) return data.key;
        if (t >= 0 && t < DEFAULT_KEYS.length) return DEFAULT_KEYS[t];
        return "library" + t;
    }

    private String tabLabel(int t) {
        MediaLibrary.Tab data = tabAt(t);
        if (data != null) return data.label;
        if (t >= 0 && t < DEFAULT_LABELS.length) return DEFAULT_LABELS[t];
        return "Library";
    }

    public void setWeather(WeatherService.Data d, String problem) {
        weather.setData(d, problem);
    }

    public void setPalette(Palette p) {
        palette = p;
        applyPalette();
        applyScale();
    }

    public void setWallpaper(android.graphics.Bitmap bitmap, boolean photo) {
        if (background != null) background.setWallpaper(bitmap, photo);
    }

    private void applyPalette() {
        background.setPalette(palette);
        tabs.setPalette(palette);
        for (CircleButton b : top) if (b != null) b.setPalette(palette);
        for (ArtView a : art) if (a != null) a.setPalette(palette);
        glass.setPalette(palette);
        dock.setPalette(palette);
        for (MiniTile t : inputTiles) t.setPalette(palette);
        for (MiniTile t : shortcutTiles) t.setPalette(palette);
        weather.setPalette(palette);

        headline.setTextColor(palette.ink);
        for (TextView t : titles) if (t != null) t.setTextColor(palette.ink);
        if (meta != null) meta.setTextColor(palette.dim);
        if (status != null) status.setTextColor(palette.faint);
        if (remoteHint != null) remoteHint.setTextColor(palette.amber);
        if (inputsLabel != null) inputsLabel.setTextColor(palette.dim);
        if (shortcutsLabel != null) shortcutsLabel.setTextColor(palette.dim);
        if (closeHint != null) closeHint.setTextColor(palette.faint);
        invalidate();
    }

    public void setAccessibility(boolean ready) {
        accessibility = ready;
        top[1].setAlert(!ready, ready ? "Remote control connected"
                : "Remote control off. Opens accessibility settings");
        remoteHint.setVisibility(ready ? GONE : VISIBLE);
        if (listener != null) listener.onFocusChanged();
    }

    public void setJellyfinStatus(boolean configured, boolean loading, boolean offline,
                                  boolean empty, long cachedAt) {
        jellyfinConfigured = configured;
        libraryLoading = loading;
        jellyfinOffline = offline;
        jellyfinEmpty = empty;
        jellyfinCachedAt = cachedAt;
        updateStatus();
        updateTitles();
    }

    private void updateStatus() {
        if (status == null) return;
        String line = "";
        if (!jellyfinConfigured) {
            line = "Add your Jellyfin server from the phone remote";
        } else if (jellyfinEmpty) {
            line = "Jellyfin library is empty";
        } else if (jellyfinOffline) {
            String age = ageText(jellyfinCachedAt);
            line = "Jellyfin offline · showing cached titles" + (age.isEmpty() ? "" : " · " + age);
        } else if (libraryLoading && tabsData.isEmpty()) {
            line = "Loading your Jellyfin library…";
        }
        status.setText(line);
        status.setVisibility(line.isEmpty() ? GONE : VISIBLE);
    }

    private static String ageText(long at) {
        if (at <= 0) return "";
        long mins = Math.max(0, (System.currentTimeMillis() - at) / 60000L);
        if (mins < 1) return "just now";
        if (mins < 60) return "cached " + mins + " min ago";
        long h = mins / 60;
        if (h < 24) return "cached " + h + " h ago";
        return "cached " + (h / 24) + " d ago";
    }

    // ------------------------------------------------------------------ scale

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        k = Math.min(w / DesignLayout.DESIGN_W, h / DesignLayout.DESIGN_H);
        applyScale();
    }

    private void applyScale() {
        if (headline == null) return;
        headline.setTextSize(TypedValue.COMPLEX_UNIT_PX, 34f * k);
        for (TextView t : titles) {
            if (t != null) t.setTextSize(TypedValue.COMPLEX_UNIT_PX, 18f * k);
        }
        meta.setTextSize(TypedValue.COMPLEX_UNIT_PX, 11f * k);
        status.setTextSize(TypedValue.COMPLEX_UNIT_PX, 9.5f * k);
        remoteHint.setTextSize(TypedValue.COMPLEX_UNIT_PX, 10f * k);
        inputsLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, 11f * k);
        shortcutsLabel.setTextSize(TypedValue.COMPLEX_UNIT_PX, 11f * k);
        closeHint.setTextSize(TypedValue.COMPLEX_UNIT_PX, 10f * k);

        tabs.setScale(k);
        for (CircleButton b : top) if (b != null) b.setScale(k);
        for (ArtView a : art) if (a != null) a.setScale(k);
        glass.setDesignScale(k);
        dock.setScale(k);
        for (MiniTile t : inputTiles) t.setScale(k);
        for (MiniTile t : shortcutTiles) t.setScale(k);
        weather.setScale(k);
        updateTransforms();
    }

    // ------------------------------------------------------------------ media

    private void switchTab(int next) {
        if (next < 0 || next >= tabCount() || next == tab) return;
        tab = next;
        tabs.setActive(tab);
        rebuildMedia(true);
        if (listener != null) listener.onTabChanged(tab);
        notifyFocus();
    }

    private void rebuildMedia(boolean animate) {
        MediaLibrary.Tab data = tabAt(tab);
        int start = window[tab];
        for (int i = 0; i < 3; i++) {
            MediaLibrary.Item item = data != null && start + i < data.items.size()
                    ? data.items.get(start + i) : null;
            art[i].setItem(item);
            if (animate) {
                art[i].setAlpha(0.15f);
                art[i].setTranslationY(8f * k);
                art[i].animate().alpha(1f).translationY(0f)
                        .setStartDelay(i * 35L).setDuration(230L).start();
            }
        }
        updateTitles();
        MediaLibrary.Item feature = data != null && start < data.items.size()
                ? data.items.get(start) : null;
        if (feature != null) {
            final String want = feature.id;
            library.ambient(feature, new MediaLibrary.ImageListener() {
                @Override
                public void onImage(android.graphics.Bitmap bitmap) {
                    MediaLibrary.Item now = art[0].item();
                    if (now != null && now.id.equals(want)) background.setAmbient(bitmap);
                }
            });
        } else {
            background.setAmbient(null);
        }
    }

    private void updateTitles() {
        MediaLibrary.Tab data = tabAt(tab);
        if (data == null) {
            // Library discovery is still running (or the cache is empty on a cold start).
            for (int i = 0; i < 3; i++) {
                titles[i].setText("");
                titles[i].setTextColor(palette.ink);
            }
            String loading = tabsData.isEmpty() ? "Loading your Jellyfin libraries…"
                    : "Jellyfin is offline";
            meta.setText(loading);
            meta.setTextColor(palette.dim);
            art[0].setPlaceholder(tabsData.isEmpty() ? "Loading library…" : loading);
            return;
        }
        int start = window[tab];
        for (int i = 0; i < 3; i++) {
            MediaLibrary.Item item = data != null && start + i < data.items.size()
                    ? data.items.get(start + i) : null;
            titles[i].setText(item == null ? "" : item.name);
            titles[i].setTextColor(palette.ink);
        }
        MediaLibrary.Item feature = data != null && start < data.items.size()
                ? data.items.get(start) : null;
        if (feature != null) {
            meta.setText(feature.metaLine());
        } else {
            String empty = "No titles in " + tabLabel(tab);
            meta.setText(empty);
            art[0].setPlaceholder(empty);
        }
        meta.setTextColor(palette.dim);
    }

    private void ensureWindow() {
        MediaLibrary.Tab data = tabAt(tab);
        int size = data == null ? 0 : data.items.size();
        if (tab >= 0 && tab < window.length) {
            window[tab] = clamp(window[tab], 0, Math.max(0, size - 1));
        }
    }

    // ------------------------------------------------------------------ focus

    private boolean syncing;

    public void requestInitialFocus() {
        if (apps.isEmpty() && art[0].item() != null) zone = ZONE_MEDIA;
        else zone = ZONE_DOCK;
        applyFocus();
    }

    public void restoreFocus() {
        applyFocus();
    }

    private void applyFocus() {
        if (tabs == null) return;
        syncing = true;
        try {
            tabs.setFocused(zone == ZONE_TABS);
            tabs.setFocusIndex(zone == ZONE_TABS ? tab : -1);
            if (zone == ZONE_TABS) tabs.requestFocus();

            for (int i = 0; i < 3; i++) {
                top[i].setFocusVisual(zone == ZONE_TOP && topIndex == i);
                if (zone == ZONE_TOP && topIndex == i) top[i].requestFocus();
            }

            for (int i = 0; i < 3; i++) {
                art[i].setFocusVisual(zone == ZONE_MEDIA && mediaIndex == i);
                if (zone == ZONE_MEDIA && mediaIndex == i) art[i].requestFocus();
            }

            dock.setFocusIndex(zone == ZONE_DOCK ? dockIndex : -1, zone == ZONE_DOCK);
            if (dock.isSettling()) requestAnimation();

            for (int i = 0; i < inputTiles.size(); i++) {
                boolean on = zone == ZONE_INPUTS && inputIndex == i && inputTiles.get(i).usable();
                inputTiles.get(i).setFocusVisual(on);
                if (on) inputTiles.get(i).requestFocus();
            }
            for (int i = 0; i < shortcutTiles.size(); i++) {
                boolean on = zone == ZONE_SHORTCUTS && shortcutIndex == i
                        && shortcutTiles.get(i).usable();
                shortcutTiles.get(i).setFocusVisual(on);
                if (on) shortcutTiles.get(i).requestFocus();
            }
        } finally {
            syncing = false;
        }
        updateTitles();
        notifyFocus();
    }

    private void notifyFocus() {
        if (listener != null) listener.onFocusChanged();
    }

    public void move(String dir) {
        if (dir == null) return;
        if ("ok".equals(dir) || "enter".equals(dir)) {
            activate();
            return;
        }
        if ("home".equals(dir)) return;
        if (drawerOpen) moveDrawer(dir);
        else moveHome(dir);
    }

    private void moveHome(String dir) {
        boolean right = "right".equals(dir);
        boolean left = "left".equals(dir);
        boolean up = "up".equals(dir);
        boolean down = "down".equals(dir);
        if (!right && !left && !up && !down) return;

        switch (zone) {
            case ZONE_DOCK:
                if (right || left) {
                    int next = dockIndex + (right ? 1 : -1);
                    if (next >= 0 && next < dock.count()) {
                        dockIndex = next;
                        applyFocus();
                    }
                } else if (up) {
                    zone = ZONE_MEDIA;
                    mediaIndex = clamp(mediaIndex, 0, 2);
                    applyFocus();
                } else {
                    openDrawer();
                }
                break;
            case ZONE_MEDIA:
                if (right) {
                    if (mediaIndex < 2) {
                        mediaIndex++;
                        applyFocus();
                    } else {
                        pageMedia(1);
                    }
                } else if (left) {
                    if (mediaIndex > 0) {
                        mediaIndex--;
                        applyFocus();
                    } else {
                        pageMedia(-1);
                    }
                } else if (up) {
                    zone = ZONE_TABS;
                    applyFocus();
                } else {
                    zone = ZONE_DOCK;
                    applyFocus();
                }
                break;
            case ZONE_TABS:
                if (right) {
                    switchTab(tab + 1);
                    applyFocus();
                } else if (left) {
                    switchTab(tab - 1);
                    applyFocus();
                } else if (up) {
                    zone = ZONE_TOP;
                    applyFocus();
                } else {
                    zone = ZONE_MEDIA;
                    applyFocus();
                }
                break;
            case ZONE_TOP:
                if (right || left) {
                    int next = topIndex + (right ? 1 : -1);
                    if (next >= 0 && next < 3) {
                        topIndex = next;
                        applyFocus();
                    }
                } else if (down) {
                    zone = ZONE_TABS;
                    applyFocus();
                } else if (up) {
                    // already at the top
                }
                break;
            default:
                zone = ZONE_DOCK;
                applyFocus();
                break;
        }
    }

    private void pageMedia(int direction) {
        MediaLibrary.Tab data = tabAt(tab);
        if (data == null || data.items.size() <= 3) {
            applyFocus();
            return;
        }
        int size = data.items.size();
        int next = window[tab] + direction * 3;
        next = clamp(next, 0, Math.max(0, size - 3));
        if (next == window[tab]) {
            // At the ends: wrap the focus to the other edge of the row.
            mediaIndex = direction > 0 ? 0 : 2;
        } else {
            window[tab] = next;
            mediaIndex = clamp(mediaIndex, 0, 2);
            rebuildMedia(true);
        }
        applyFocus();
    }

    private void openDrawer() {
        if (drawerOpen) return;
        drawerOpen = true;
        drawerSpring.setTarget(1f);
        drawerLayer.setVisibility(VISIBLE);
        ensureDrawerFocus();
        if (listener != null) listener.onDrawerOpened(true);
        requestAnimation();
        notifyFocus();
    }

    public void closeDrawer() {
        if (!drawerOpen) return;
        drawerOpen = false;
        drawerSpring.setTarget(0f);
        if (zone == ZONE_INPUTS || zone == ZONE_SHORTCUTS) zone = ZONE_DOCK;
        applyFocus();
        if (listener != null) listener.onDrawerOpened(false);
        requestAnimation();
        notifyFocus();
    }

    private void moveDrawer(String dir) {
        boolean right = "right".equals(dir);
        boolean left = "left".equals(dir);
        boolean up = "up".equals(dir);
        boolean down = "down".equals(dir);
        switch (zone) {
            case ZONE_DOCK:
                if (right || left) {
                    int next = dockIndex + (right ? 1 : -1);
                    if (next >= 0 && next < dock.count()) {
                        dockIndex = next;
                        applyFocus();
                    }
                } else if (up) {
                    closeDrawer();
                } else if (down) {
                    if (!firstUsable(inputTiles).isEmpty()) {
                        zone = ZONE_INPUTS;
                        inputIndex = firstUsable(inputTiles).get(0);
                    } else if (!firstUsable(shortcutTiles).isEmpty()) {
                        zone = ZONE_SHORTCUTS;
                        shortcutIndex = firstUsable(shortcutTiles).get(0);
                    }
                    applyFocus();
                }
                break;
            case ZONE_INPUTS: {
                List<Integer> usable = firstUsable(inputTiles);
                int at = usable.indexOf(inputIndex);
                if (right) {
                    if (at >= 0 && at < usable.size() - 1) inputIndex = usable.get(at + 1);
                    else if (!firstUsable(shortcutTiles).isEmpty()) {
                        zone = ZONE_SHORTCUTS;
                        shortcutIndex = firstUsable(shortcutTiles).get(0);
                    }
                    applyFocus();
                } else if (left) {
                    if (at > 0) inputIndex = usable.get(at - 1);
                    applyFocus();
                } else if (up) {
                    zone = ZONE_DOCK;
                    applyFocus();
                }
                break;
            }
            case ZONE_SHORTCUTS: {
                List<Integer> usable = firstUsable(shortcutTiles);
                int at = usable.indexOf(shortcutIndex);
                if (right) {
                    if (at >= 0 && at < usable.size() - 1) shortcutIndex = usable.get(at + 1);
                    applyFocus();
                } else if (left) {
                    if (at > 0) shortcutIndex = usable.get(at - 1);
                    else if (!firstUsable(inputTiles).isEmpty()) {
                        zone = ZONE_INPUTS;
                        List<Integer> ins = firstUsable(inputTiles);
                        inputIndex = ins.get(ins.size() - 1);
                    }
                    applyFocus();
                } else if (up) {
                    zone = ZONE_DOCK;
                    applyFocus();
                }
                break;
            }
            default:
                zone = ZONE_DOCK;
                applyFocus();
                break;
        }
    }

    private static List<Integer> firstUsable(List<MiniTile> tiles) {
        List<Integer> out = new ArrayList<Integer>();
        for (int i = 0; i < tiles.size(); i++) {
            if (tiles.get(i).usable()) out.add(i);
        }
        return out;
    }

    private void ensureDrawerFocus() {
        if (zone != ZONE_INPUTS && zone != ZONE_SHORTCUTS) {
            zone = ZONE_DOCK;
        }
        applyFocus();
    }

    public void activate() {
        switch (zone) {
            case ZONE_DOCK:
                if (listener != null && dock.appAt(dockIndex) != null) {
                    listener.onAppSelected(dock.appAt(dockIndex));
                }
                break;
            case ZONE_MEDIA:
                if (listener != null) {
                    MediaLibrary.Item item = art[clamp(mediaIndex, 0, 2)].item();
                    if (item != null) listener.onMediaSelected(item);
                }
                break;
            case ZONE_TABS:
                zone = ZONE_MEDIA;
                applyFocus();
                break;
            case ZONE_TOP:
                if (listener != null) listener.onTopControl(topIndex);
                break;
            case ZONE_INPUTS:
                if (inputIndex >= 0 && inputIndex < inputTiles.size()) {
                    activateDrawer(inputTiles.get(inputIndex));
                }
                break;
            case ZONE_SHORTCUTS:
                if (shortcutIndex >= 0 && shortcutIndex < shortcutTiles.size()) {
                    activateDrawer(shortcutTiles.get(shortcutIndex));
                }
                break;
            default:
                break;
        }
    }

    private void activateDrawer(MiniTile tile) {
        if (tile == null || !tile.usable()) return;
        if (listener != null) listener.onDrawerAction(tile.action(), tile.getContentDescription() == null
                ? "" : tile.getContentDescription().toString());
    }

    public boolean back() {
        if (drawerOpen) {
            closeDrawer();
            return true;
        }
        if (zone != ZONE_DOCK) {
            zone = ZONE_DOCK;
            applyFocus();
            return true;
        }
        return false;
    }

    public boolean drawerOpen() {
        return drawerOpen;
    }

    // ------------------------------------------------------------------ motion

    private static final class Spring {
        float value;
        float velocity;
        float target;
        final float response;
        final float damping;

        Spring(float response, float damping, float initial) {
            this.response = response;
            this.damping = damping;
            value = target = initial;
        }

        void setTarget(float t) {
            target = t;
        }

        boolean step(float dt) {
            if (Math.abs(value - target) < 0.0008f && Math.abs(velocity) < 0.004f) {
                value = target;
                velocity = 0f;
                return false;
            }
            float omega = (float) (2 * Math.PI / response);
            float stiffness = omega * omega;
            float friction = 2 * damping * omega;
            int steps = Math.max(1, (int) Math.ceil(dt / 0.004f));
            float h = dt / steps;
            for (int i = 0; i < steps; i++) {
                float accel = -stiffness * (value - target) - friction * velocity;
                velocity += accel * h;
                value += velocity * h;
            }
            return true;
        }
    }

    private void requestAnimation() {
        if (frameScheduled) return;
        frameScheduled = true;
        lastFrameNs = 0L;
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            float dt = lastFrameNs == 0L ? 0.016f
                    : Math.min(0.05f, (frameTimeNanos - lastFrameNs) / 1_000_000_000f);
            lastFrameNs = frameTimeNanos;
            boolean more = drawerSpring.step(dt);
            more |= dock.step(dt);
            updateTransforms();
            if (more) {
                Choreographer.getInstance().postFrameCallback(this);
            } else {
                frameScheduled = false;
                lastFrameNs = 0L;
                if (!drawerOpen) drawerLayer.setVisibility(INVISIBLE);
            }
        }
    };

    private void updateTransforms() {
        float d = drawerSpring.value;
        if (d < 0f) d = 0f;
        if (d > 1f) d = 1f;
        float homeAlpha = 1f - d;
        homeLayer.setAlpha(homeAlpha);
        homeLayer.setTranslationY(-64f * k * d);
        dockLayer.setTranslationY(-DRAWER_SHIFT * k * d);
        drawerLayer.setTranslationY((1f - d) * DRAWER_SHIFT * k);
        drawerLayer.setAlpha(Math.min(1f, d * 1.5f));
        if (d > 0.001f) drawerLayer.setVisibility(VISIBLE);
        else if (!drawerOpen) drawerLayer.setVisibility(INVISIBLE);
    }

    @Override
    protected void onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frameCallback);
        frameScheduled = false;
        super.onDetachedFromWindow();
    }

    // ------------------------------------------------------------------ reporting

    public int selectedApp() {
        return dockIndex;
    }

    public int dockCount() {
        return dock.count();
    }

    public int tab() {
        return tab;
    }

    public String zoneName() {
        switch (zone) {
            case ZONE_TABS: return "tabs";
            case ZONE_TOP: return "top";
            case ZONE_MEDIA: return "media";
            case ZONE_INPUTS: return "inputs";
            case ZONE_SHORTCUTS: return "shortcuts";
            default: return "dock";
        }
    }

    public String focusedLabel() {
        switch (zone) {
            case ZONE_TABS:
                return tabLabel(tab);
            case ZONE_TOP:
                return topIndex == 0 ? "Pair phone" : (topIndex == 1 ? "Remote status" : "Settings");
            case ZONE_MEDIA: {
                MediaLibrary.Item item = art[clamp(mediaIndex, 0, 2)].item();
                return item == null ? "Media" : item.name;
            }
            case ZONE_INPUTS:
                return inputIndex < inputTiles.size()
                        ? String.valueOf(inputTiles.get(inputIndex).getContentDescription()) : "Input";
            case ZONE_SHORTCUTS:
                return shortcutIndex < shortcutTiles.size()
                        ? String.valueOf(shortcutTiles.get(shortcutIndex).getContentDescription())
                        : "Shortcut";
            default:
                return dock.appAt(dockIndex) == null ? "" : dock.appAt(dockIndex).label;
        }
    }

    public String focusJson() {
        return "\"zone\":\"" + zoneName() + "\",\"index\":" + zoneIndex()
                + ",\"tab\":\"" + tabKey(tab) + "\",\"tabIndex\":" + tab
                + ",\"label\":\"" + Json.esc(focusedLabel()) + "\""
                + ",\"drawer\":" + drawerOpen;
    }

    private int zoneIndex() {
        switch (zone) {
            case ZONE_TABS: return tab;
            case ZONE_TOP: return topIndex;
            case ZONE_MEDIA: return mediaIndex;
            case ZONE_INPUTS: return inputIndex;
            case ZONE_SHORTCUTS: return shortcutIndex;
            default: return dockIndex;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }
}
