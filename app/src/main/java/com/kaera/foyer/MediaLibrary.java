package com.kaera.foyer;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Jellyfin library for the home screen: cache first, refresh in the background.
 *
 * <p>Everything on screen keeps its Jellyfin item id, so the Moonfin launcher can address the
 * exact title later. Nothing here ever logs the API key or puts it in a URL.
 */
public final class MediaLibrary {

    /** The order the Jellyfin libraries appear as tabs. */
    public static final String[] LIBRARY_NAMES = {"Shows", "Movies", "Anime", "Dramas"};
    public static final String TAB_SHOWS = "shows";
    public static final String TAB_MOVIES = "movies";
    public static final String TAB_ANIME = "anime";
    public static final String TAB_DRAMAS = "dramas";

    public static final String TYPE_MOVIE = "Movie";
    public static final String TYPE_SERIES = "Series";

    /** Refresh window while the server is healthy. */
    private static final long TTL_MS = 6 * 60 * 60 * 1000L;
    private static final long MIN_RETRY_MS = 2 * 60 * 1000L;
    private static final long MAX_RETRY_MS = 30 * 60 * 1000L;
    private static final int PER_TAB = 9;
    private static final int SEASON_LOOKUPS_PER_TAB = 3;

    public static final class Item {
        public final String id;
        public final String name;
        public final String type;
        public final int year;
        public final int seasons;
        public final int runtimeMinutes;
        public final String genres;
        public final boolean backdrop;

        Item(String id, String name, String type, int year, int seasons, int runtimeMinutes,
             String genres, boolean backdrop) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.year = year;
            this.seasons = seasons;
            this.runtimeMinutes = runtimeMinutes;
            this.genres = genres == null ? "" : genres;
            this.backdrop = backdrop;
        }

        public boolean isSeries() {
            return TYPE_SERIES.equals(type);
        }

        /** "Series · Sci-Fi & Fantasy · 2 seasons · 2022" - only what the server actually gave us. */
        public String metaLine() {
            List<String> bits = new ArrayList<String>(4);
            if (!TextUtils.isEmpty(type)) bits.add(type);
            String g = genres;
            if (!TextUtils.isEmpty(g)) {
                // One or two genres read better than the whole tag list.
                String[] all = g.split(", ");
                if (all.length > 2) g = all[0] + " & " + all[1];
                bits.add(g);
            }
            if (isSeries() && seasons > 0) bits.add(seasons + (seasons == 1 ? " season" : " seasons"));
            if (year > 0) bits.add(String.valueOf(year));
            if (!isSeries() && runtimeMinutes > 0) bits.add(runtimeMinutes + " min");
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < bits.size(); i++) {
                if (i > 0) b.append("  ·  ");
                b.append(bits.get(i));
            }
            return b.toString();
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("name", name);
                o.put("type", type);
                o.put("year", year);
                o.put("seasons", seasons);
                o.put("runtime", runtimeMinutes);
                o.put("genres", genres);
                o.put("backdrop", backdrop);
            } catch (Exception ignored) {
            }
            return o;
        }
    }

    public static final class Tab {
        public final String key;
        public final String label;
        public final String libraryId;
        public final List<Item> items;

        Tab(String key, String label, String libraryId, List<Item> items) {
            this.key = key;
            this.label = label;
            this.libraryId = libraryId == null ? "" : libraryId;
            this.items = Collections.unmodifiableList(items);
        }
    }

    public interface Listener {
        /** Called on the main thread whenever the library, images or status change. */
        void onLibraryChanged();
    }

    public interface ImageListener {
        void onImage(Bitmap bitmap);
    }

    private final Context ctx;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker;
    private final ExecutorService images;
    private final Object lock = new Object();

    private volatile List<Tab> tabs = Collections.emptyList();
    private volatile long fetchedAt;
    private volatile String cacheUrl = "";
    private volatile String serverId = "";
    private volatile boolean loading;
    private volatile boolean everLoaded;
    private volatile boolean cacheLoaded;
    private volatile String lastProblem = "";
    private volatile Listener listener;

    private long nextAttemptAt;
    private long retryMs;
    private int generation;

    /** Small LRU of decoded images. Keys already include the requested size. */
    private final LinkedHashMap<String, Bitmap> cache =
            new LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
                    return size() > 7;
                }
            };
    private final Set<String> inFlight = new HashSet<String>();
    private final Map<String, List<ImageListener>> waiting = new HashMap<String, List<ImageListener>>();

    private static final AtomicInteger THREADS = new AtomicInteger();

    public MediaLibrary(Context context) {
        ctx = context.getApplicationContext();
        ThreadFactory tf = new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "foyer-media-" + THREADS.incrementAndGet());
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY + 1);
                return t;
            }
        };
        worker = Executors.newSingleThreadExecutor(tf);
        images = Executors.newSingleThreadExecutor(tf);
        retryMs = prefs().getLong("jellyfin_retry", MIN_RETRY_MS);
        fetchedAt = prefs().getLong("jellyfin_last_ok", 0L);
        nextAttemptAt = 0L;
    }

    public void setListener(Listener l) {
        listener = l;
    }

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences("foyer", Context.MODE_PRIVATE);
    }

    public boolean configured() {
        return !jfBase().isEmpty() && !jfKey().isEmpty();
    }

    public List<Tab> tabs() {
        return tabs;
    }

    public boolean isLoading() {
        return loading;
    }

    public long lastOk() {
        return prefs().getLong("jellyfin_last_ok", fetchedAt);
    }

    public long nextAttempt() {
        return nextAttemptAt;
    }

    public String problem() {
        return lastProblem;
    }

    public boolean empty() {
        List<Tab> t = tabs;
        if (t.isEmpty()) return false;
        for (Tab tab : t) {
            if (!tab.items.isEmpty()) return false;
        }
        return true;
    }

    public Tab tab(String key) {
        for (Tab t : tabs) {
            if (t.key.equals(key)) return t;
        }
        return null;
    }

    public Item item(String id) {
        if (id == null) return null;
        for (Tab t : tabs) {
            for (Item i : t.items) {
                if (id.equals(i.id)) return i;
            }
        }
        return null;
    }

    private String jfBase() {
        String u = prefs().getString("jellyfin_url", "");
        if (u == null) return "";
        u = u.trim();
        if (u.isEmpty()) return "";
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://" + u;
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    private String jfKey() {
        String k = prefs().getString("jellyfin_key", "");
        return k == null ? "" : k.trim();
    }

    private File dir() {
        File d = new File(ctx.getFilesDir(), "jellyfin");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public File posterFile(Item item) {
        return new File(dir(), "poster_" + item.id + ".jpg");
    }

    public File wideFile(Item item) {
        return new File(dir(), "wide_" + item.id + ".jpg");
    }

    /** The file the wide slot should draw: a real backdrop when there is one, else the poster. */
    public File wideSource(Item item) {
        File w = wideFile(item);
        if (item.backdrop && w.exists() && w.length() > 512) return w;
        return posterFile(item);
    }

    // ------------------------------------------------------------------ loading

    public void start() {
        if (everLoaded) {
            maybeRefresh();
            return;
        }
        everLoaded = true;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                final List<Tab> cached = readCache();
                final long at = prefs().getLong("jellyfin_cache_at", 0L);
                final String url = prefs().getString("jellyfin_cache_url", "");
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        // Apply the cache before deciding whether a refresh is due: the six-hour
                        // window is only meaningful once the cached tabs count as the shelf.
                        if (!cached.isEmpty() && (url.isEmpty() || sameServer(url, jfBase()))) {
                            tabs = cached;
                            fetchedAt = at;
                            cacheUrl = url;
                            notifyChanged();
                        }
                        // From here on the cache (even an empty one) is the shelf to compare
                        // against; onResume's earlier maybeRefresh call must not start a fetch.
                        cacheLoaded = true;
                        maybeRefresh();
                    }
                });
            }
        });
    }

    public void maybeRefresh() {
        if (!cacheLoaded) return;
        if (!configured()) {
            notifyChanged();
            return;
        }
        long last = prefs().getLong("jellyfin_last_ok", 0L);
        boolean fresh = System.currentTimeMillis() - last < TTL_MS;
        if (fresh && !tabs.isEmpty()) return;
        if (System.currentTimeMillis() < nextAttemptAt) return;
        refresh(false);
    }

    public void refreshNow() {
        refresh(true);
    }

    public void setConfig(String url, String key) {
        String u = url == null ? "" : url.trim();
        String k = key == null ? "" : key.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        String old = prefs().getString("jellyfin_url", "");
        prefs().edit()
                .putString("jellyfin_url", u)
                .putString("jellyfin_key", k)
                .putLong("jellyfin_last_ok", 0L)
                .putLong("jellyfin_retry", MIN_RETRY_MS)
                .apply();
        if (!sameServer(old, u)) {
            // Item ids are server scoped: never show the previous server's shelf.
            deleteCache();
            tabs = Collections.emptyList();
            fetchedAt = 0L;
            cacheUrl = "";
        }
        retryMs = MIN_RETRY_MS;
        nextAttemptAt = 0L;
        notifyChanged();
        refresh(true);
    }

    private void clearConfig() {
        prefs().edit().remove("jellyfin_url").remove("jellyfin_key").apply();
        deleteCache();
        tabs = Collections.emptyList();
        fetchedAt = 0L;
        notifyChanged();
    }

    private void deleteCache() {
        File d = dir();
        File[] files = d.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.getName().startsWith("poster_") || f.getName().startsWith("wide_")
                        || f.getName().equals("items.json") || f.getName().equals("media.json")) {
                    try {
                        f.delete();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    private void refresh(final boolean force) {
        if (loading) return;
        if (!configured()) return;
        loading = true;
        final int gen = ++generation;
        notifyChanged();

        worker.execute(new Runnable() {
            @Override
            public void run() {
                boolean ok;
                String problem = "";
                try {
                    ok = fetch(gen);
                } catch (Throwable t) {
                    ok = false;
                    problem = friendly(t);
                }
                final boolean success = ok;
                final String why = problem;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != generation) return;
                        loading = false;
                        if (success) {
                            lastProblem = "";
                            retryMs = MIN_RETRY_MS;
                            nextAttemptAt = 0L;
                            prefs().edit()
                                    .putLong("jellyfin_last_ok", System.currentTimeMillis())
                                    .putLong("jellyfin_cache_at", System.currentTimeMillis())
                                    .putString("jellyfin_cache_url", jfBase())
                                    .putLong("jellyfin_retry", MIN_RETRY_MS)
                                    .apply();
                        } else {
                            lastProblem = why;
                            long backoff = Math.max(MIN_RETRY_MS, retryMs * 2);
                            if (backoff > MAX_RETRY_MS) backoff = MAX_RETRY_MS;
                            retryMs = backoff;
                            prefs().edit().putLong("jellyfin_retry", backoff).apply();
                            nextAttemptAt = System.currentTimeMillis() + backoff;
                            // Keep whatever is on screen: a cache is better than an empty shelf.
                            if (tabs.isEmpty()) {
                                List<Tab> cached = readCache();
                                if (!cached.isEmpty()) tabs = cached;
                            }
                        }
                        notifyChanged();
                    }
                });
            }
        });
    }

    private static String friendly(Throwable t) {
        String m = t.getMessage() == null ? "" : t.getMessage();
        if (m.contains("401") || m.contains("403")) return "Jellyfin refused the API key";
        if (m.contains("timeout") || m.contains("timed out")) return "Jellyfin timed out";
        if (m.contains("host") || m.contains("resolve")) return "Jellyfin address is unreachable";
        return "Jellyfin is offline";
    }

    // ------------------------------------------------------------------ fetch

    /** One refresh attempt. Discovers the four Jellyfin libraries and fills one tab per library. */
    private boolean fetch(int gen) throws Exception {
        String base = jfBase();
        String key = jfKey();
        if (base.isEmpty() || key.isEmpty()) return false;

        String serverId = "";
        try {
            JSONObject info = new JSONObject(httpGet(base + "/System/Info", key));
            serverId = info.optString("Id", "");
        } catch (Throwable ignored) {
            // Metadata is still worth trying; the id is only informational.
        }

        List<Library> libraries = fetchLibraries(base, key);
        if (libraries == null || libraries.isEmpty()) return false;

        List<Tab> fresh = new ArrayList<Tab>(libraries.size());
        boolean anySuccess = false;
        for (Library lib : libraries) {
            List<Item> items = fetchLibraryItems(base, key, lib.id, PER_TAB);
            if (items != null) anySuccess = true;
            else items = Collections.emptyList();
            items = preferBackdrop(items);
            items = addSeasons(base, key, items, SEASON_LOOKUPS_PER_TAB);
            fresh.add(new Tab(lib.key, lib.name, lib.id, items));
        }
        if (!anySuccess) return false;

        writeCache(fresh, serverId, base);

        final List<Tab> posted = fresh;
        ui.post(new Runnable() {
            @Override
            public void run() {
                tabs = posted;
                fetchedAt = System.currentTimeMillis();
                notifyChanged();
            }
        });

        // Artwork after the metadata is already usable. Missing files are retried next refresh.
        for (Tab t : fresh) {
            for (Item it : t.items) {
                if (gen != generation) return true;
                File poster = posterFile(it);
                if (!poster.exists() || poster.length() < 512) {
                    download(base + "/Items/" + it.id + "/Images/Primary?maxWidth=440&quality=88",
                            poster, key);
                }
                if (it.backdrop) {
                    File wide = wideFile(it);
                    if (!wide.exists() || wide.length() < 512) {
                        download(base + "/Items/" + it.id
                                + "/Images/Backdrop/0?maxWidth=1280&quality=84", wide, key);
                    }
                }
            }
        }
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (gen == generation) notifyChanged();
            }
        });
        return true;
    }

    private static final class Library {
        final String key;
        final String name;
        final String id;

        Library(String key, String name, String id) {
            this.key = key;
            this.name = name;
            this.id = id;
        }
    }

    /** The four requested Jellyfin libraries, in tab order. Null when discovery fails. */
    private List<Library> fetchLibraries(String base, String key) {
        try {
            JSONArray arr = null;
            try {
                JSONObject o = new JSONObject(httpGet(base + "/Library/MediaFolders", key));
                arr = o.optJSONArray("Items");
            } catch (Throwable ignored) {
                // Some keys are not admin keys; the generic Items endpoint still lists the folders.
            }
            if (arr == null) {
                try {
                    JSONObject o = new JSONObject(httpGet(base
                            + "/Items?Recursive=false&IncludeItemTypes=CollectionFolder&Limit=50",
                            key));
                    arr = o.optJSONArray("Items");
                } catch (Throwable ignored) {
                }
            }
            if (arr == null) return null;
            List<Library> all = new ArrayList<Library>();
            Map<String, Library> byName = new HashMap<String, Library>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject it = arr.optJSONObject(i);
                if (it == null) continue;
                String type = it.optString("Type", "");
                if (!"CollectionFolder".equals(type) && !"UserView".equals(type)) continue;
                String collectionType = it.optString("CollectionType", "");
                if ("boxsets".equalsIgnoreCase(collectionType)
                        || "playlists".equalsIgnoreCase(collectionType)) continue;
                String name = it.optString("Name", "").trim();
                String id = it.optString("Id", "");
                if (name.isEmpty() || id.isEmpty()) continue;
                String lower = name.toLowerCase(Locale.US);
                if (lower.equals("collections") || lower.equals("playlists")) continue;
                Library lib = new Library(keyForLibrary(name), name, id);
                all.add(lib);
                byName.put(lower, lib);
            }
            List<Library> out = new ArrayList<Library>(LIBRARY_NAMES.length);
            for (String wanted : LIBRARY_NAMES) {
                Library lib = byName.get(wanted.toLowerCase(Locale.US));
                if (lib != null && !hasLibrary(out, lib.id)) out.add(lib);
            }
            if (out.isEmpty()) {
                // Names changed on the server: keep the home useful with its video libraries.
                for (Library lib : all) {
                    if (out.size() >= LIBRARY_NAMES.length) break;
                    if (!hasLibrary(out, lib.id)) out.add(lib);
                }
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean hasLibrary(List<Library> list, String id) {
        for (Library l : list) {
            if (l.id.equals(id)) return true;
        }
        return false;
    }

    private static String keyForLibrary(String name) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = Character.toLowerCase(name.charAt(i));
            if (Character.isLetterOrDigit(c)) b.append(c);
            else if (b.length() > 0 && b.charAt(b.length() - 1) != '_') b.append('_');
        }
        if (b.length() == 0) return "library";
        return b.toString();
    }

    /** Fetches up to [limit] titles from one library; null when the request itself failed. */
    private List<Item> fetchLibraryItems(String base, String key, String libraryId, int limit) {
        List<Item> first = fetchLibraryPage(base, key, libraryId, limit * 6, false);
        if (first == null) return null;
        if (first.size() < limit) {
            // Movie libraries list BoxSets first; IsNotFolder is the quick way past them.
            List<Item> extra = fetchLibraryPage(base, key, libraryId, limit * 6, true);
            if (extra != null) {
                for (Item e : extra) {
                    if (first.size() >= limit) break;
                    boolean seen = false;
                    for (Item f : first) {
                        if (f.id.equals(e.id)) {
                            seen = true;
                            break;
                        }
                    }
                    if (!seen) first.add(e);
                }
            }
        }
        if (first.size() > limit) return new ArrayList<Item>(first.subList(0, limit));
        return first;
    }

    private List<Item> fetchLibraryPage(String base, String key, String libraryId, int limit,
                                        boolean notFolder) {
        try {
            String url = base + "/Items?ParentId=" + libraryId
                    + "&Recursive=true&IncludeItemTypes=Movie,Series"
                    + (notFolder ? "&Filters=IsNotFolder" : "")
                    + "&SortBy=DateCreated&SortOrder=Descending&Limit=" + limit
                    + "&Fields=ProductionYear,Genres,RunTimeTicks,BackdropImageTags,ImageTags,Type,MediaType";
            JSONObject o = new JSONObject(httpGet(url, key));
            JSONArray arr = o.optJSONArray("Items");
            if (arr == null) return null;
            List<Item> out = new ArrayList<Item>(limit);
            for (int i = 0; i < arr.length() && out.size() < limit; i++) {
                JSONObject it = arr.optJSONObject(i);
                if (it == null) continue;
                String type = it.optString("Type", "");
                // Library listings can include BoxSets and folders; keep real playable titles.
                if (!TYPE_MOVIE.equals(type) && !TYPE_SERIES.equals(type)) continue;
                String id = it.optString("Id", "");
                String name = it.optString("Name", "");
                if (id.isEmpty() || name.isEmpty()) continue;
                JSONArray genreArr = it.optJSONArray("Genres");
                StringBuilder genres = new StringBuilder();
                if (genreArr != null) {
                    for (int g = 0; g < genreArr.length(); g++) {
                        if (g > 0) genres.append(", ");
                        genres.append(genreArr.optString(g, ""));
                    }
                }
                JSONArray backdrops = it.optJSONArray("BackdropImageTags");
                boolean hasBackdrop = backdrops != null && backdrops.length() > 0;
                long ticks = it.optLong("RunTimeTicks", 0L);
                int minutes = ticks > 0 ? (int) Math.round(ticks / 600000000.0) : 0;
                out.add(new Item(id, name, type, it.optInt("ProductionYear", 0),
                        it.optInt("ChildCount", 0), minutes, genres.toString(), hasBackdrop));
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Season counts for the first few series of a tab, keeping the refresh bounded. */
    private List<Item> addSeasons(String base, String key, List<Item> items, int maxLookups) {
        if (items.isEmpty()) return items;
        List<Item> out = new ArrayList<Item>(items.size());
        int lookups = 0;
        for (Item s : items) {
            int n = s.seasons;
            if (TYPE_SERIES.equals(s.type) && n <= 0 && lookups < maxLookups) {
                lookups++;
                n = seasonCount(base, key, s.id);
            }
            out.add(n == s.seasons ? s : new Item(s.id, s.name, s.type, s.year, n,
                    s.runtimeMinutes, s.genres, s.backdrop));
        }
        return out;
    }

    private int seasonCount(String base, String key, String id) {
        try {
            JSONObject o = new JSONObject(httpGet(base + "/Shows/" + id + "/Seasons", key));
            JSONArray items = o.optJSONArray("Items");
            return items == null ? 0 : items.length();
        } catch (Throwable t) {
            return 0;
        }
    }

    private static List<Item> preferBackdrop(List<Item> in) {
        List<Item> yes = new ArrayList<Item>(in.size());
        List<Item> no = new ArrayList<Item>(in.size());
        for (Item i : in) {
            if (i.backdrop) yes.add(i);
            else no.add(i);
        }
        yes.addAll(no);
        return yes;
    }

    private void download(String url, File dest, String key) {
        File tmp = new File(dest.getAbsolutePath() + ".part");
        HttpURLConnection c = null;
        InputStream in = null;
        FileOutputStream fos = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            auth(c, key);
            in = c.getInputStream();
            fos = new FileOutputStream(tmp);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            fos.close();
            fos = null;
            if (tmp.length() > 512) {
                if (dest.exists()) dest.delete();
                tmp.renameTo(dest);
            } else {
                tmp.delete();
            }
        } catch (Throwable ignored) {
            try {
                if (tmp.exists()) tmp.delete();
            } catch (Exception ignored2) {
            }
        } finally {
            try {
                if (fos != null) fos.close();
            } catch (Exception ignored) {
            }
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
            if (c != null) c.disconnect();
        }
    }

    // ------------------------------------------------------------------ cache

    private void writeCache(List<Tab> fresh, String serverId, String base) {
        try {
            JSONObject root = new JSONObject();
            root.put("v", 3);
            root.put("serverId", serverId == null ? "" : serverId);
            root.put("url", base);
            root.put("fetchedAt", System.currentTimeMillis());
            JSONArray tabsJson = new JSONArray();
            for (Tab t : fresh) {
                JSONObject tab = new JSONObject();
                tab.put("key", t.key);
                tab.put("name", t.label);
                tab.put("libraryId", t.libraryId);
                JSONArray arr = new JSONArray();
                for (Item i : t.items) arr.put(i.toJson());
                tab.put("items", arr);
                tabsJson.put(tab);
            }
            root.put("tabs", tabsJson);
            File tmp = new File(dir(), "media.json.part");
            FileOutputStream fos = new FileOutputStream(tmp);
            fos.write(root.toString().getBytes("UTF-8"));
            fos.close();
            File dest = new File(dir(), "media.json");
            if (dest.exists()) dest.delete();
            tmp.renameTo(dest);
            prefs().edit()
                    .putLong("jellyfin_cache_at", System.currentTimeMillis())
                    .putString("jellyfin_cache_url", base)
                    .putString("jellyfin_server_id", serverId == null ? "" : serverId)
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    /** Reads the library cache written by v3+. The old type-tab cache is intentionally ignored. */
    private List<Tab> readCache() {
        File f = new File(dir(), "media.json");
        if (!f.exists()) return Collections.emptyList();
        try {
            JSONObject root = new JSONObject(new String(readAll(f), "UTF-8"));
            if (root.optInt("v", 0) < 3) return Collections.emptyList();
            JSONArray arr = root.optJSONArray("tabs");
            if (arr == null) return Collections.emptyList();
            List<Tab> out = new ArrayList<Tab>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject tab = arr.optJSONObject(i);
                if (tab == null) continue;
                String key = tab.optString("key", "");
                String name = tab.optString("name", "");
                if (key.isEmpty() || name.isEmpty()) continue;
                List<Item> items = new ArrayList<Item>();
                JSONArray itemArr = tab.optJSONArray("items");
                if (itemArr != null) {
                    for (int j = 0; j < itemArr.length(); j++) {
                        JSONObject o = itemArr.optJSONObject(j);
                        if (o == null) continue;
                        String id = o.optString("id", "");
                        String title = o.optString("name", "");
                        if (id.isEmpty() || title.isEmpty()) continue;
                        items.add(new Item(id, title, o.optString("type", ""), o.optInt("year", 0),
                                o.optInt("seasons", 0), o.optInt("runtime", 0),
                                o.optString("genres", ""), o.optBoolean("backdrop", false)));
                    }
                }
                out.add(new Tab(key, name, tab.optString("libraryId", ""), items));
            }
            serverId = root.optString("serverId", "");
            fetchedAt = root.optLong("fetchedAt", fetchedAt);
            return out;
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    private static boolean sameServer(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        a = a.trim().toLowerCase(Locale.US).replaceAll("^https?://", "");
        b = b.trim().toLowerCase(Locale.US).replaceAll("^https?://", "");
        while (a.endsWith("/")) a = a.substring(0, a.length() - 1);
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return a.equals(b);
    }

    private static byte[] readAll(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } finally {
            in.close();
        }
    }

    // ------------------------------------------------------------------ images

    /**
     * Decodes artwork off the main thread. The listener always fires on the main thread; a null
     * bitmap means the file is missing or unreadable and the card should keep its placeholder.
     */
    public void image(final Item item, final boolean wide, final int reqW, final int reqH,
                      final ImageListener cb) {
        if (item == null) {
            cb.onImage(null);
            return;
        }
        File f = wide ? wideSource(item) : posterFile(item);
        final String key = f.getAbsolutePath() + "@" + reqW + "x" + reqH;
        synchronized (lock) {
            Bitmap hit = cache.get(key);
            if (hit != null && !hit.isRecycled()) {
                cb.onImage(hit);
                return;
            }
            List<ImageListener> list = waiting.get(key);
            if (list != null) {
                list.add(cb);
                return;
            }
            list = new ArrayList<ImageListener>(2);
            list.add(cb);
            waiting.put(key, list);
            inFlight.add(key);
        }
        final File file = f;
        images.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap bmp = decode(file, reqW, reqH);
                final List<ImageListener> callbacks;
                synchronized (lock) {
                    if (bmp != null) cache.put(key, bmp);
                    callbacks = waiting.remove(key);
                    inFlight.remove(key);
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (callbacks == null) return;
                        for (ImageListener l : callbacks) l.onImage(bmp);
                    }
                });
            }
        });
    }

    private static Bitmap decode(File f, int reqW, int reqH) {
        try {
            if (!f.exists() || f.length() < 512) return null;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                f.delete();
                return null;
            }
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= reqW && bounds.outHeight / (sample * 2) >= reqH) {
                sample *= 2;
            }
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inSampleSize = sample;
            opt.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath(), opt);
            if (bmp == null) {
                f.delete();
                return null;
            }
            return bmp;
        } catch (Throwable t) {
            try {
                f.delete();
            } catch (Exception ignored) {
            }
            return null;
        }
    }

    /**
     * A tiny, blurred version of the wide art used for the pale ambient background. Generated on
     * a worker thread and cached; the caller never pays for a full-resolution blur.
     */
    public void ambient(final Item item, final ImageListener cb) {
        if (item == null) {
            cb.onImage(null);
            return;
        }
        File f = wideSource(item);
        final String key = f.getAbsolutePath() + "@ambient";
        synchronized (lock) {
            Bitmap hit = cache.get(key);
            if (hit != null && !hit.isRecycled()) {
                cb.onImage(hit);
                return;
            }
            List<ImageListener> list = waiting.get(key);
            if (list != null) {
                list.add(cb);
                return;
            }
            list = new ArrayList<ImageListener>(2);
            list.add(cb);
            waiting.put(key, list);
            inFlight.add(key);
        }
        final File file = f;
        images.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap small = null;
                try {
                    if (file.exists() && file.length() > 512) {
                        BitmapFactory.Options o = new BitmapFactory.Options();
                        o.inSampleSize = 4;
                        o.inPreferredConfig = Bitmap.Config.RGB_565;
                        Bitmap rawBmp = BitmapFactory.decodeFile(file.getAbsolutePath(), o);
                        if (rawBmp != null) {
                            small = boxBlur(rawBmp, 5);
                            rawBmp.recycle();
                        }
                    }
                } catch (Throwable ignored) {
                }
                final Bitmap result = small;
                final List<ImageListener> callbacks;
                synchronized (lock) {
                    if (result != null) cache.put(key, result);
                    callbacks = waiting.remove(key);
                    inFlight.remove(key);
                }
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (callbacks == null) return;
                        for (ImageListener l : callbacks) l.onImage(result);
                    }
                });
            }
        });
    }

    private static Bitmap boxBlur(Bitmap src, int radius) {
        int w = src.getWidth();
        int h = src.getHeight();
        int scaleW = 160;
        if (w > scaleW) {
            h = Math.max(1, h * scaleW / w);
            w = scaleW;
        }
        Bitmap scaled = Bitmap.createScaledBitmap(src, w, h, true);
        int[] px = new int[w * h];
        scaled.getPixels(px, 0, w, 0, 0, w, h);
        int[] tmp = new int[px.length];
        int r = Math.max(1, radius);
        // Two cheap separable passes: good enough behind a heavy pale wash.
        for (int pass = 0; pass < 2; pass++) {
            for (int y = 0; y < h; y++) {
                int row = y * w;
                int sr = 0, sg = 0, sb = 0, count = 0;
                for (int x = -r; x < w + r; x++) {
                    if (x + r < w) {
                        int c = px[row + Math.min(w - 1, x + r)];
                        sr += (c >> 16) & 0xFF;
                        sg += (c >> 8) & 0xFF;
                        sb += c & 0xFF;
                        count++;
                    }
                    if (x - r - 1 >= 0) {
                        int c = px[row + (x - r - 1)];
                        sr -= (c >> 16) & 0xFF;
                        sg -= (c >> 8) & 0xFF;
                        sb -= c & 0xFF;
                        count--;
                    }
                    if (x >= 0 && x < w && count > 0) {
                        int nr = sr / count, ng = sg / count, nb = sb / count;
                        tmp[row + x] = 0xFF000000 | (nr << 16) | (ng << 8) | nb;
                    }
                }
            }
            for (int x = 0; x < w; x++) {
                int sr = 0, sg = 0, sb = 0, count = 0;
                for (int y = -r; y < h + r; y++) {
                    if (y + r < h) {
                        int c = tmp[Math.min(h - 1, y + r) * w + x];
                        sr += (c >> 16) & 0xFF;
                        sg += (c >> 8) & 0xFF;
                        sb += c & 0xFF;
                        count++;
                    }
                    if (y - r - 1 >= 0) {
                        int c = tmp[(y - r - 1) * w + x];
                        sr -= (c >> 16) & 0xFF;
                        sg -= (c >> 8) & 0xFF;
                        sb -= c & 0xFF;
                        count--;
                    }
                    if (y >= 0 && y < h && count > 0) {
                        int nr = sr / count, ng = sg / count, nb = sb / count;
                        px[y * w + x] = 0xFF000000 | (nr << 16) | (ng << 8) | nb;
                    }
                }
            }
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        out.setPixels(px, 0, w, 0, 0, w, h);
        scaled.recycle();
        return out;
    }

    // ------------------------------------------------------------------ http

    private static void auth(HttpURLConnection c, String key) {
        if (key != null && !key.isEmpty()) {
            // Jellyfin 12 only honours this header - on JSON and image requests alike.
            c.setRequestProperty("Authorization", "MediaBrowser Token=\"" + key + "\"");
        }
    }

    private static String httpGet(String url, String key) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Accept", "application/json");
        auth(c, key);
        try {
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code);
            }
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return new String(bo.toByteArray(), "UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private void notifyChanged() {
        final Listener l = listener;
        if (l == null) return;
        if (Looper.myLooper() == Looper.getMainLooper()) l.onLibraryChanged();
        else ui.post(new Runnable() {
            @Override
            public void run() {
                l.onLibraryChanged();
            }
        });
    }

    /** The Jellyfin server's own id, kept for diagnostics; Moonfin uses its own internal id. */
    public String serverId() {
        String id = serverId;
        if (id == null || id.isEmpty()) id = prefs().getString("jellyfin_server_id", "");
        return id == null ? "" : id;
    }

    public void shutdown() {
        worker.shutdownNow();
        images.shutdownNow();
    }
}
