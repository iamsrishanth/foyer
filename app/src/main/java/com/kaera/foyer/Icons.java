package com.kaera.foyer;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.util.LinkedHashMap;
import java.util.Map;

/** Small stroke icons drawn on a 24-unit grid and cached as tiny bitmaps (cheap on a 1 GB box). */
public final class Icons {

    public static final int PHONE = 1;
    public static final int GEAR = 2;
    public static final int REMOTE = 3;
    public static final int GRID = 4;
    public static final int TV = 5;
    public static final int INPUT = 6;
    public static final int CAST = 7;
    public static final int HOME = 8;
    public static final int REFRESH = 9;
    public static final int PAIR = 10;
    public static final int SLIDERS = 11;
    public static final int SUN = 12;
    public static final int MOON = 13;
    public static final int CLOUD = 14;
    public static final int PARTLY = 15;
    public static final int RAIN = 16;
    public static final int SNOW = 17;
    public static final int FOG = 18;
    public static final int STORM = 19;
    public static final int SUNRISE = 20;
    public static final int SUNSET = 21;
    public static final int CLAP = 22;

    private static final LinkedHashMap<Long, Bitmap> CACHE =
            new LinkedHashMap<Long, Bitmap>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Bitmap> eldest) {
                    return size() > 64;
                }
            };

    private Icons() {
    }

    /** A bitmap of the icon at exactly sizePx, tinted color. Never upscale the result. */
    public static Bitmap get(int name, int sizePx, int color) {
        sizePx = Math.max(4, sizePx);
        long key = ((long) name << 48) | ((long) sizePx << 24) | (color & 0xFFFFFFL)
                | (((long) (color >>> 24)) << 56);
        synchronized (CACHE) {
            Bitmap hit = CACHE.get(key);
            if (hit != null && !hit.isRecycled()) return hit;
            Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            float s = sizePx / 24f;
            c.scale(s, s);
            draw(c, name, color);
            CACHE.put(key, bmp);
            return bmp;
        }
    }

    private static Paint stroke(int color, float width) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(width);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        return p;
    }

    private static Paint fill(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setStyle(Paint.Style.FILL);
        return p;
    }

    private static void draw(Canvas c, int name, int color) {
        Paint p = stroke(color, 1.75f);
        Paint f = fill(color);
        RectF r = new RectF();
        switch (name) {
            case PHONE:
                r.set(7.2f, 2.6f, 16.8f, 21.4f);
                c.drawRoundRect(r, 2.4f, 2.4f, p);
                c.drawCircle(12, 18.4f, 0.9f, f);
                break;
            case GEAR:
                c.drawCircle(12, 12, 5.0f, p);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * 2 * i / 8;
                    float x1 = (float) (12 + Math.cos(a) * 5.0);
                    float y1 = (float) (12 + Math.sin(a) * 5.0);
                    float x2 = (float) (12 + Math.cos(a) * 7.4);
                    float y2 = (float) (12 + Math.sin(a) * 7.4);
                    c.drawLine(x1, y1, x2, y2, p);
                }
                c.drawCircle(12, 12, 2.2f, p);
                break;
            case REMOTE:
                r.set(8.2f, 2.4f, 15.8f, 21.6f);
                c.drawRoundRect(r, 3.2f, 3.2f, p);
                c.drawCircle(12, 7.2f, 1.5f, f);
                c.drawLine(10.4f, 12.4f, 13.6f, 12.4f, p);
                c.drawLine(10.4f, 15.4f, 13.6f, 15.4f, p);
                c.drawLine(10.4f, 18.4f, 13.6f, 18.4f, p);
                break;
            case GRID:
                for (int yy = 0; yy < 3; yy++) {
                    for (int xx = 0; xx < 3; xx++) {
                        r.set(3.2f + xx * 6.4f, 3.2f + yy * 6.4f,
                                7.6f + xx * 6.4f, 7.6f + yy * 6.4f);
                        c.drawRoundRect(r, 1.1f, 1.1f, f);
                    }
                }
                break;
            case TV:
                r.set(2.4f, 4.2f, 21.6f, 17.2f);
                c.drawRoundRect(r, 2.2f, 2.2f, p);
                c.drawLine(12, 17.2f, 12, 20.4f, p);
                c.drawLine(8, 20.6f, 16, 20.6f, p);
                break;
            case INPUT:
                r.set(3.0f, 6.0f, 21.0f, 18.2f);
                c.drawRoundRect(r, 2.2f, 2.2f, p);
                c.drawLine(6.2f, 12.1f, 13.6f, 12.1f, p);
                c.drawLine(10.8f, 9.3f, 13.8f, 12.1f, p);
                c.drawLine(10.8f, 14.9f, 13.8f, 12.1f, p);
                c.drawCircle(17.4f, 12.1f, 1.1f, f);
                break;
            case CAST:
                r.set(2.6f, 4.2f, 21.4f, 18.6f);
                c.drawRoundRect(r, 2.2f, 2.2f, p);
                c.drawCircle(5.6f, 17.8f, 0.9f, f);
                r.set(2.6f, 11.4f, 15.8f, 24.0f);
                c.drawArc(r, 180, 90, false, p);
                r.set(2.6f, 8.0f, 21.0f, 24.0f);
                c.drawArc(r, 180, 90, false, p);
                break;
            case HOME:
                c.drawLine(3.2f, 11.2f, 12, 3.6f, p);
                c.drawLine(12, 3.6f, 20.8f, 11.2f, p);
                c.drawLine(5.6f, 9.4f, 5.6f, 20.6f, p);
                c.drawLine(18.4f, 9.4f, 18.4f, 20.6f, p);
                c.drawLine(5.6f, 20.6f, 18.4f, 20.6f, p);
                c.drawLine(10.2f, 20.6f, 10.2f, 14.6f, p);
                c.drawLine(13.8f, 20.6f, 13.8f, 14.6f, p);
                break;
            case REFRESH:
                r.set(4.0f, 4.0f, 20.0f, 20.0f);
                c.drawArc(r, 40, 285, false, p);
                c.drawLine(16.4f, 3.6f, 17.2f, 7.6f, p);
                c.drawLine(17.2f, 7.6f, 13.2f, 7.0f, p);
                break;
            case PAIR:
                r.set(3.0f, 3.2f, 13.4f, 20.8f);
                c.drawRoundRect(r, 2.4f, 2.4f, p);
                c.drawCircle(8.2f, 17.2f, 0.9f, f);
                r.set(12.6f, 6.0f, 20.2f, 13.6f);
                c.drawArc(r, -55, 110, false, p);
                r.set(12.0f, 3.2f, 22.0f, 16.4f);
                c.drawArc(r, -55, 110, false, p);
                break;
            case SLIDERS:
                c.drawLine(3.4f, 7.2f, 20.6f, 7.2f, p);
                c.drawCircle(8.2f, 7.2f, 1.8f, f);
                c.drawLine(3.4f, 12.0f, 20.6f, 12.0f, p);
                c.drawCircle(15.0f, 12.0f, 1.8f, f);
                c.drawLine(3.4f, 16.8f, 20.6f, 16.8f, p);
                c.drawCircle(10.4f, 16.8f, 1.8f, f);
                break;
            case SUN:
                c.drawCircle(12, 12, 4.4f, f);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * 2 * i / 8;
                    float x1 = (float) (12 + Math.cos(a) * 6.8);
                    float y1 = (float) (12 + Math.sin(a) * 6.8);
                    float x2 = (float) (12 + Math.cos(a) * 9.0);
                    float y2 = (float) (12 + Math.sin(a) * 9.0);
                    c.drawLine(x1, y1, x2, y2, p);
                }
                break;
            case MOON:
                c.drawCircle(12.4f, 12f, 7.4f, f);
                {
                    Paint cut = new Paint(Paint.ANTI_ALIAS_FLAG);
                    cut.setColor(0xFFFFFFFF);
                    cut.setXfermode(new android.graphics.PorterDuffXfermode(
                            android.graphics.PorterDuff.Mode.CLEAR));
                    c.drawCircle(16.8f, 9.4f, 6.2f, cut);
                }
                break;
            case CLOUD:
                cloud(c, f);
                break;
            case PARTLY:
                c.drawCircle(9.0f, 9.2f, 3.4f, f);
                for (int i = 0; i < 5; i++) {
                    double a = Math.PI * 2 * i / 6 + Math.PI;
                    float x1 = (float) (9 + Math.cos(a) * 5.0);
                    float y1 = (float) (9.2f + Math.sin(a) * 5.0);
                    float x2 = (float) (9 + Math.cos(a) * 6.8);
                    float y2 = (float) (9.2f + Math.sin(a) * 6.8);
                    c.drawLine(x1, y1, x2, y2, p);
                }
                cloud(c, f);
                break;
            case RAIN:
                cloud(c, f);
                for (int i = 0; i < 3; i++) {
                    c.drawLine(7.4f + i * 4.4f, 17.0f, 6.2f + i * 4.4f, 21.0f, p);
                }
                break;
            case SNOW:
                cloud(c, f);
                for (int i = 0; i < 3; i++) {
                    float x = 7.4f + i * 4.4f;
                    c.drawLine(x - 1.4f, 18.4f, x + 1.4f, 21.0f, p);
                    c.drawLine(x + 1.4f, 18.4f, x - 1.4f, 21.0f, p);
                }
                break;
            case FOG:
                cloud(c, f);
                c.drawLine(4.6f, 18.4f, 19.4f, 18.4f, p);
                c.drawLine(7.0f, 21.0f, 17.0f, 21.0f, p);
                break;
            case STORM:
                cloud(c, f);
                c.drawLine(12.4f, 15.4f, 9.4f, 19.4f, p);
                c.drawLine(9.4f, 19.4f, 12.6f, 19.4f, p);
                c.drawLine(12.6f, 19.4f, 10.2f, 22.6f, p);
                break;
            case SUNRISE:
                c.drawLine(3.4f, 18.6f, 20.6f, 18.6f, p);
                c.drawArc(new RectF(6.0f, 8.0f, 18.0f, 20.0f), 180, 180, false, p);
                c.drawLine(12, 3.6f, 12, 6.4f, p);
                c.drawLine(5.6f, 6.4f, 7.6f, 8.4f, p);
                c.drawLine(18.4f, 6.4f, 16.4f, 8.4f, p);
                break;
            case SUNSET:
                c.drawLine(3.4f, 18.6f, 20.6f, 18.6f, p);
                c.drawArc(new RectF(6.0f, 8.0f, 18.0f, 20.0f), 180, 180, false, p);
                c.drawLine(12, 3.6f, 12, 6.4f, p);
                c.drawLine(12, 7.0f, 9.4f, 9.6f, p);
                c.drawLine(12, 7.0f, 14.6f, 9.6f, p);
                break;
            case CLAP:
                r.set(3.0f, 8.4f, 21.0f, 19.4f);
                c.drawRoundRect(r, 1.8f, 1.8f, p);
                c.drawLine(3.0f, 12.4f, 21.0f, 12.4f, p);
                c.drawLine(6.0f, 4.6f, 4.4f, 8.4f, p);
                c.drawLine(10.2f, 4.6f, 8.6f, 8.4f, p);
                c.drawLine(14.4f, 4.6f, 12.8f, 8.4f, p);
                c.drawLine(18.6f, 4.6f, 17.0f, 8.4f, p);
                c.drawLine(4.0f, 4.6f, 20.0f, 4.6f, p);
                break;
            default:
                c.drawCircle(12, 12, 7, p);
                break;
        }
    }

    private static void cloud(Canvas c, Paint f) {
        c.drawCircle(9.2f, 13.4f, 4.2f, f);
        c.drawCircle(14.4f, 12.2f, 5.0f, f);
        c.drawRoundRect(new RectF(6.0f, 13.0f, 19.4f, 17.8f), 2.4f, 2.4f, f);
    }

    /** Pick the icon for a WMO weather code. */
    public static int weatherIcon(int code, boolean day) {
        if (code == 0) return day ? SUN : MOON;
        if (code == 1 || code == 2) return day ? PARTLY : MOON;
        if (code == 3) return CLOUD;
        if (code == 45 || code == 48) return FOG;
        if (code >= 51 && code <= 57) return RAIN;
        if (code >= 61 && code <= 67) return RAIN;
        if (code >= 71 && code <= 77) return SNOW;
        if (code >= 80 && code <= 82) return RAIN;
        if (code >= 85 && code <= 86) return SNOW;
        if (code >= 95) return STORM;
        return day ? PARTLY : MOON;
    }
}
