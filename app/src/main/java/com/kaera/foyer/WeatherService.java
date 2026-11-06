package com.kaera.foyer;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Open-Meteo weather for the projector's fixed coordinates. Cache first: the drawer always paints
 * the last good response, and a failure only downgrades the panel to "stale" - it never blocks the
 * home screen or Jellyfin navigation.
 */
public final class WeatherService {

    /**
     * Location used for the forecast. The shipped defaults are London and are only a placeholder:
     * set these three constants to wherever your projector actually lives. The location is fixed
     * on purpose - it is never derived from the device, the IP or the build host.
     */
    public static final double LAT = 51.5074;
    public static final double LON = -0.1278;
    public static final String TIMEZONE = "Europe/London";

    private static final long TTL_MS = 15 * 60 * 1000L;
    private static final long MIN_RETRY_MS = 5 * 60 * 1000L;
    private static final long MAX_RETRY_MS = 30 * 60 * 1000L;

    public static final class Hour {
        public final String label;
        public final int temp;
        public final int code;
        public final boolean day;

        Hour(String label, int temp, int code, boolean day) {
            this.label = label;
            this.temp = temp;
            this.code = code;
            this.day = day;
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("label", label);
                o.put("temp", temp);
                o.put("code", code);
                o.put("day", day);
            } catch (Exception ignored) {
            }
            return o;
        }

        static Hour fromJson(JSONObject o) {
            return new Hour(o.optString("label", ""), o.optInt("temp", 0), o.optInt("code", 0),
                    o.optBoolean("day", true));
        }
    }

    public static final class Data {
        public final boolean available;
        public final long fetchedAt;
        public final int temp;
        public final int code;
        public final boolean day;
        public final int high;
        public final int low;
        public final String sunrise;   // formatted local time, e.g. "6:05 AM"
        public final String sunset;
        public final List<Hour> hours;
        public final String condition;
        public final String updated;

        Data(boolean available, long fetchedAt, int temp, int code, boolean day, int high, int low,
             String sunrise, String sunset, List<Hour> hours, String condition, String updated) {
            this.available = available;
            this.fetchedAt = fetchedAt;
            this.temp = temp;
            this.code = code;
            this.day = day;
            this.high = high;
            this.low = low;
            this.sunrise = sunrise == null ? "" : sunrise;
            this.sunset = sunset == null ? "" : sunset;
            this.hours = hours == null ? Collections.<Hour>emptyList() : hours;
            this.condition = condition == null ? "" : condition;
            this.updated = updated == null ? "" : updated;
        }

        public boolean stale() {
            return System.currentTimeMillis() - fetchedAt > TTL_MS;
        }
    }

    public interface Listener {
        void onWeatherChanged();
    }

    private final Context ctx;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile Data data;
    private volatile boolean loading;
    private volatile String problem = "";
    private volatile Listener listener;
    private long nextAttemptAt;
    private long retryMs = MIN_RETRY_MS;
    private Thread worker;

    public WeatherService(Context context) {
        ctx = context.getApplicationContext();
        retryMs = prefs().getLong("weather_retry", MIN_RETRY_MS);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences("foyer", Context.MODE_PRIVATE);
    }

    private File cacheFile() {
        return new File(ctx.getFilesDir(), "weather.json");
    }

    public Data data() {
        return data;
    }

    public boolean loading() {
        return loading;
    }

    public String problem() {
        return problem;
    }

    public void start() {
        loadCache();
        maybeRefresh();
    }

    private void loadCache() {
        try {
            File f = cacheFile();
            if (!f.exists()) return;
            String raw = new String(readAll(f), "UTF-8");
            JSONObject o = new JSONObject(raw);
            long at = o.optLong("fetchedAt", 0L);
            if (at == 0L) return;
            List<Hour> hours = new ArrayList<Hour>();
            JSONArray arr = o.optJSONArray("hours");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject h = arr.optJSONObject(i);
                    if (h != null) hours.add(Hour.fromJson(h));
                }
            }
            data = new Data(true, at, o.optInt("temp", 0), o.optInt("code", 0),
                    o.optBoolean("day", true), o.optInt("high", 0), o.optInt("low", 0),
                    o.optString("sunrise", ""), o.optString("sunset", ""), hours,
                    o.optString("condition", ""), updatedText(at));
            notifyChanged();
        } catch (Throwable ignored) {
        }
    }

    public void maybeRefresh() {
        if (loading) return;
        if (data != null && System.currentTimeMillis() - data.fetchedAt < TTL_MS) return;
        if (System.currentTimeMillis() < nextAttemptAt) return;
        refresh(false);
    }

    public void refreshNow() {
        refresh(true);
    }

    private synchronized void refresh(final boolean force) {
        if (loading) return;
        loading = true;
        notifyChanged();
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                String why = "";
                try {
                    ok = fetch();
                } catch (Throwable t) {
                    why = t.getMessage() == null ? "Weather unavailable" : t.getMessage();
                }
                final boolean success = ok;
                final String reason = why;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        loading = false;
                        if (success) {
                            problem = "";
                            retryMs = MIN_RETRY_MS;
                            nextAttemptAt = 0L;
                            prefs().edit().putLong("weather_retry", MIN_RETRY_MS).apply();
                        } else {
                            problem = reason;
                            retryMs = Math.min(MAX_RETRY_MS, Math.max(MIN_RETRY_MS, retryMs * 2));
                            nextAttemptAt = System.currentTimeMillis() + retryMs;
                            prefs().edit().putLong("weather_retry", retryMs).apply();
                            if (data != null) {
                                data = new Data(true, data.fetchedAt, data.temp, data.code, data.day,
                                        data.high, data.low, data.sunrise, data.sunset, data.hours,
                                        data.condition, updatedText(data.fetchedAt));
                            }
                        }
                        notifyChanged();
                    }
                });
            }
        }, "foyer-weather");
        worker.setDaemon(true);
        worker.start();
    }

    private boolean fetch() throws Exception {
        String url = "https://api.open-meteo.com/v1/forecast"
                + "?latitude=" + LAT
                + "&longitude=" + LON
                + "&current=temperature_2m,weather_code,is_day,relative_humidity_2m"
                + "&hourly=temperature_2m,weather_code,precipitation_probability"
                + "&daily=temperature_2m_max,temperature_2m_min,sunrise,sunset,weather_code"
                + "&timezone=" + TIMEZONE.replace("/", "%2F")
                + "&forecast_days=2";
        String raw = httpGet(url);
        JSONObject root = new JSONObject(raw);
        JSONObject cur = root.optJSONObject("current");
        JSONObject daily = root.optJSONObject("daily");
        JSONObject hourly = root.optJSONObject("hourly");
        if (cur == null || daily == null || hourly == null) return false;

        int temp = (int) Math.round(cur.optDouble("temperature_2m", 0));
        int code = cur.optInt("weather_code", 0);
        boolean day = cur.optInt("is_day", 1) == 1;
        String now = cur.optString("time", "");

        JSONArray tmax = daily.optJSONArray("temperature_2m_max");
        JSONArray tmin = daily.optJSONArray("temperature_2m_min");
        int high = tmax != null && tmax.length() > 0 ? (int) Math.round(tmax.optDouble(0, temp)) : temp;
        int low = tmin != null && tmin.length() > 0 ? (int) Math.round(tmin.optDouble(0, temp)) : temp;

        JSONArray sunriseArr = daily.optJSONArray("sunrise");
        JSONArray sunsetArr = daily.optJSONArray("sunset");
        String sunrise = sunriseArr != null && sunriseArr.length() > 0
                ? clock(sunriseArr.optString(0, "")) : "";
        String sunset = sunsetArr != null && sunsetArr.length() > 0
                ? clock(sunsetArr.optString(0, "")) : "";

        List<Hour> hours = new ArrayList<Hour>(8);
        JSONArray times = hourly.optJSONArray("time");
        JSONArray temps = hourly.optJSONArray("temperature_2m");
        JSONArray codes = hourly.optJSONArray("weather_code");
        if (times != null && temps != null) {
            int start = 0;
            for (int i = 0; i < times.length(); i++) {
                if (times.optString(i, "").compareTo(now) >= 0) {
                    start = i;
                    break;
                }
            }
            int sunriseMin = minutesOfDay(sunriseArr != null && sunriseArr.length() > 0
                    ? sunriseArr.optString(0, "") : "");
            int sunsetMin = minutesOfDay(sunsetArr != null && sunsetArr.length() > 0
                    ? sunsetArr.optString(0, "") : "");
            for (int i = start; i < times.length() && hours.size() < 7; i += 1) {
                String t = times.optString(i, "");
                if (t.length() < 13) continue;
                int tempI = (int) Math.round(temps.optDouble(i, temp));
                int codeI = codes == null ? 0 : codes.optInt(i, 0);
                int mins = minutesOfDay(t);
                boolean hourDay = sunriseMin > 0 && sunsetMin > 0
                        ? mins >= sunriseMin && mins < sunsetMin : day;
                hours.add(new Hour(hourLabel(t), tempI, codeI, hourDay));
            }
        }

        long at = System.currentTimeMillis();
        Data fresh = new Data(true, at, temp, code, day, high, low, sunrise, sunset, hours,
                condition(code), updatedText(at));
        data = fresh;
        writeCache(fresh);
        return true;
    }

    private void writeCache(Data d) {
        try {
            JSONObject o = new JSONObject();
            o.put("fetchedAt", d.fetchedAt);
            o.put("temp", d.temp);
            o.put("code", d.code);
            o.put("day", d.day);
            o.put("high", d.high);
            o.put("low", d.low);
            o.put("sunrise", d.sunrise);
            o.put("sunset", d.sunset);
            o.put("condition", d.condition);
            JSONArray arr = new JSONArray();
            for (Hour h : d.hours) arr.put(h.toJson());
            o.put("hours", arr);
            File tmp = new File(ctx.getFilesDir(), "weather.json.part");
            FileOutputStream fos = new FileOutputStream(tmp);
            fos.write(o.toString().getBytes("UTF-8"));
            fos.close();
            File dest = cacheFile();
            if (dest.exists()) dest.delete();
            tmp.renameTo(dest);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ formatting

    static String updatedText(long at) {
        long mins = Math.max(0L, (System.currentTimeMillis() - at) / 60000L);
        if (mins < 1) return "Updated just now";
        if (mins < 60) return "Updated " + mins + " min ago";
        long h = mins / 60L;
        if (h < 24) return "Updated " + h + " h ago";
        return "Updated " + (h / 24L) + " d ago";
    }

    private static String hourLabel(String iso) {
        int h = parseHour(iso);
        if (h < 0) return "";
        String ampm = h < 12 ? "AM" : "PM";
        int h12 = h % 12;
        if (h12 == 0) h12 = 12;
        return h12 + " " + ampm;
    }

    private static String clock(String iso) {
        int h = parseHour(iso);
        if (h < 0) return "";
        int m = parseMinute(iso);
        String ampm = h < 12 ? "AM" : "PM";
        int h12 = h % 12;
        if (h12 == 0) h12 = 12;
        return h12 + ":" + String.format(Locale.US, "%02d", m) + " " + ampm;
    }

    private static int parseHour(String iso) {
        if (iso == null || iso.length() < 13) return -1;
        try {
            return Integer.parseInt(iso.substring(11, 13));
        } catch (Exception e) {
            return -1;
        }
    }

    private static int parseMinute(String iso) {
        if (iso == null || iso.length() < 16) return 0;
        try {
            return Integer.parseInt(iso.substring(14, 16));
        } catch (Exception e) {
            return 0;
        }
    }

    private static int minutesOfDay(String iso) {
        int h = parseHour(iso);
        if (h < 0) return -1;
        return h * 60 + parseMinute(iso);
    }

    public static String condition(int code) {
        if (code == 0) return "Clear";
        if (code == 1) return "Mainly clear";
        if (code == 2) return "Partly cloudy";
        if (code == 3) return "Overcast";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Rain showers";
        if (code >= 85 && code <= 86) return "Snow showers";
        if (code == 95) return "Thunderstorm";
        if (code == 96 || code == 99) return "Thunderstorm, hail";
        return "Weather";
    }

    // ------------------------------------------------------------------ http

    private static String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(7000);
        c.setReadTimeout(9000);
        c.setRequestProperty("Accept", "application/json");
        try {
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("Weather HTTP " + code);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return new String(bo.toByteArray(), "UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } finally {
            in.close();
        }
    }

    private void notifyChanged() {
        final Listener l = listener;
        if (l == null) return;
        if (Looper.myLooper() == Looper.getMainLooper()) l.onWeatherChanged();
        else ui.post(new Runnable() {
            @Override
            public void run() {
                l.onWeatherChanged();
            }
        });
    }

    public void shutdown() {
        if (worker != null) worker.interrupt();
    }
}
