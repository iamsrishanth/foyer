package com.kaera.foyer;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;
import java.util.Locale;

/**
 * Light (the Free My TV reference) and dark palettes, plus the real sunrise/sunset for the
 * projector's coordinates. Theme "auto" follows that sun, never the phone's or build host's city.
 */
public final class Palette {

    public final boolean dark;
    public final int ink;
    public final int dim;
    public final int faint;
    public final int tabInk;
    public final int tabIdle;
    public final int accent;
    public final int amber;
    public final int glassTop;
    public final int glassBottom;
    public final int glassRim;
    public final int glassShade;
    public final int tileFill;
    public final int cardFill;
    public final int cardRim;
    public final int placeholder;
    public final int scrim;
    public final int bgTop;
    public final int bgBottom;
    public final int focusRing;
    public final int shadow;

    private Palette(boolean dark, int ink, int dim, int faint, int tabInk, int tabIdle, int accent,
                    int amber, int glassTop, int glassBottom, int glassRim, int glassShade,
                    int tileFill, int cardFill, int cardRim, int placeholder, int scrim,
                    int bgTop, int bgBottom, int focusRing, int shadow) {
        this.dark = dark;
        this.ink = ink;
        this.dim = dim;
        this.faint = faint;
        this.tabInk = tabInk;
        this.tabIdle = tabIdle;
        this.accent = accent;
        this.amber = amber;
        this.glassTop = glassTop;
        this.glassBottom = glassBottom;
        this.glassRim = glassRim;
        this.glassShade = glassShade;
        this.tileFill = tileFill;
        this.cardFill = cardFill;
        this.cardRim = cardRim;
        this.placeholder = placeholder;
        this.scrim = scrim;
        this.bgTop = bgTop;
        this.bgBottom = bgBottom;
        this.focusRing = focusRing;
        this.shadow = shadow;
    }

    public static Palette light() {
        return new Palette(false,
                0xFF1D1D1F, 0xFF6E6E73, 0xFF8E8E93,
                0xFF1D1D1F, 0xFF3F3F46, 0xFF0A84FF, 0xFFC77800,
                0xCCFFFFFF, 0xA8FFFFFF, 0xF2FFFFFF, 0x12000000,
                0xF7FFFFFF, 0xB3FFFFFF, 0xE6FFFFFF, 0x33FFFFFF,
                0x99000000, 0xFFE9EBEE, 0xFFD2D5D9,
                0xFFFFFFFF, 0x33000000);
    }

    public static Palette dark() {
        return new Palette(true,
                0xFFF3F4F6, 0xFFA0A4AA, 0xFF7E8288,
                0xFFF3F4F6, 0xFFC5C9CE, 0xFF5AA9FF, 0xFFE8A33D,
                0x26FFFFFF, 0x14FFFFFF, 0x33FFFFFF, 0x22000000,
                0xE61A1D22, 0x40FFFFFF, 0x24FFFFFF, 0x1AFFFFFF,
                0xB3000000, 0xFF0C0F14, 0xFF080A0E,
                0xFFFFFFFF, 0x66000000);
    }

    public static boolean isDark(Context c, SharedPreferences prefs) {
        String mode = prefs.getString("theme", "light");
        if ("light".equals(mode)) return false;
        if ("dark".equals(mode)) return true;
        int[] sun = sunMinutes(c);
        Calendar cal = Calendar.getInstance();
        int now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        if (sun[0] < 0 || sun[1] < 0) return false;
        return now < sun[0] || now >= sun[1];
    }

    public static int[] sunMinutes(Context c) {
        Calendar cal = Calendar.getInstance();
        long[] sun = SunCalc.sunriseSunset(cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
                WeatherService.LAT, WeatherService.LON, 330);
        return new int[]{(int) sun[0], (int) sun[1]};
    }

    public static Calendar parseLocal(String iso) {
        try {
            Calendar c = Calendar.getInstance();
            int y = Integer.parseInt(iso.substring(0, 4));
            int m = Integer.parseInt(iso.substring(5, 7));
            int d = Integer.parseInt(iso.substring(8, 10));
            int h = Integer.parseInt(iso.substring(11, 13));
            int mi = iso.length() >= 16 ? Integer.parseInt(iso.substring(14, 16)) : 0;
            c.set(y, m - 1, d, h, mi, 0);
            c.set(Calendar.MILLISECOND, 0);
            return c;
        } catch (Exception e) {
            return Calendar.getInstance();
        }
    }

    /** NOAA sunrise/sunset. Returns local minutes-of-day, or -1 when the sun never crosses. */
    public static final class SunCalc {
        private SunCalc() {
        }

        public static long[] sunriseSunset(int year, int month, int day, double lat, double lon,
                                           int tzMinutes) {
            Calendar c = Calendar.getInstance();
            c.set(year, month - 1, day, 12, 0, 0);
            int dayOfYear = c.get(Calendar.DAY_OF_YEAR);
            double lngHour = lon / 15.0;
            double sunrise = calc(dayOfYear, lngHour, lat, tzMinutes, true);
            double sunset = calc(dayOfYear, lngHour, lat, tzMinutes, false);
            long[] out = new long[]{
                    sunrise < 0 ? -1 : Math.round(sunrise * 60),
                    sunset < 0 ? -1 : Math.round(sunset * 60)
            };
            if (out[0] >= 0) out[0] = ((out[0] % 1440) + 1440) % 1440;
            if (out[1] >= 0) out[1] = ((out[1] % 1440) + 1440) % 1440;
            return out;
        }

        private static double calc(int n, double lngHour, double lat, int tzMinutes,
                                   boolean sunrise) {
            double t = n + ((sunrise ? 6 : 18) - lngHour) / 24.0;
            double m = 0.9856 * t - 3.289;
            double l = m + 1.916 * Math.sin(rad(m)) + 0.020 * Math.sin(rad(2 * m)) + 282.634;
            l = norm(l, 360);
            double ra = deg(Math.atan(0.91764 * Math.tan(rad(l))));
            ra = norm(ra, 360);
            long lq = (long) Math.floor(l / 90) * 90;
            long raq = (long) Math.floor(ra / 90) * 90;
            ra = (ra + (lq - raq)) / 15.0;
            double sinDec = 0.39782 * Math.sin(rad(l));
            double cosDec = Math.cos(Math.asin(sinDec));
            double cosH = (Math.cos(rad(90.833)) - sinDec * Math.sin(rad(lat)))
                    / (cosDec * Math.cos(rad(lat)));
            if (cosH > 1 || cosH < -1) return -1;
            double h = sunrise ? 360 - deg(Math.acos(cosH)) : deg(Math.acos(cosH));
            h /= 15.0;
            double tt = h + ra - 0.06571 * t - 6.622;
            double ut = norm(tt - lngHour, 24);
            return norm(ut + tzMinutes / 60.0, 24);
        }

        private static double rad(double d) {
            return Math.toRadians(d);
        }

        private static double deg(double r) {
            return Math.toDegrees(r);
        }

        private static double norm(double v, double max) {
            double x = v % max;
            if (x < 0) x += max;
            return x;
        }
    }

    public static String formatMinutes(int minutes) {
        if (minutes < 0) return "";
        int h = minutes / 60;
        int m = minutes % 60;
        String ampm = h < 12 ? "AM" : "PM";
        int h12 = h % 12;
        if (h12 == 0) h12 = 12;
        return String.format(Locale.US, "%d:%02d %s", h12, m, ampm);
    }
}
