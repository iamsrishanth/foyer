package com.kaera.foyer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.view.View;

/** The wide weather panel in the lower drawer: now, today's range, hours, sunrise and sunset. */
public class WeatherView extends View {

    private Palette palette = Palette.light();
    private WeatherService.Data data;
    private String problem = "";
    private float k = 1f;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint divider = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint big = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint cond = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint small = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint tiny = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint hour = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint hourTemp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    public WeatherView(Context c) {
        super(c);
        setWillNotDraw(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        big.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        cond.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        small.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        tiny.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        hour.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        hourTemp.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        rim.setStyle(Paint.Style.STROKE);
        divider.setStyle(Paint.Style.STROKE);
        textSizes();
    }

    public void setScale(float k) {
        this.k = k;
        textSizes();
        invalidate();
    }

    private void textSizes() {
        title.setTextSize(11f * k);
        big.setTextSize(38f * k);
        cond.setTextSize(12f * k);
        small.setTextSize(10f * k);
        tiny.setTextSize(9f * k);
        hour.setTextSize(10f * k);
        hourTemp.setTextSize(12.5f * k);
    }

    public void setPalette(Palette p) {
        palette = p;
        invalidate();
    }

    public void setData(WeatherService.Data d, String problem) {
        data = d;
        this.problem = problem == null ? "" : problem;
        updateDescription();
        invalidate();
    }

    private void updateDescription() {
        if (data == null || !data.available) {
            setContentDescription("Weather unavailable. " + problem);
            return;
        }
        setContentDescription("Weather: " + data.temp + " degrees, " + data.condition
                + ". High " + data.high + ", low " + data.low
                + ". Sunrise " + data.sunrise + ", sunset " + data.sunset + ".");
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;
        float rad = 14f * (w / 880f);

        rect.set(0, 0, w, h);
        // Soft, cheap depth: three offset rounded rects instead of a per-frame blur.
        for (int i = 3; i >= 1; i--) {
            shadow.setColor(palette.dark ? 0x12000000 : 0x0D000000);
            RectF s = new RectF(rect);
            s.inset(-i * 1.5f * k, -i * 1.5f * k);
            s.offset(0, 4f * k);
            c.drawRoundRect(s, rad + i * 1.5f * k, rad + i * 1.5f * k, shadow);
        }

        fill.setShader(new LinearGradient(0, 0, 0, h,
                palette.dark ? 0xCC20242A : 0xC9FFFFFF,
                palette.dark ? 0xE0181B20 : 0xA8FFFFFF, Shader.TileMode.CLAMP));
        c.drawRoundRect(rect, rad, rad, fill);
        fill.setShader(null);

        rim.setStrokeWidth(1.1f * k);
        rim.setColor(palette.dark ? 0x33FFFFFF : 0xE6FFFFFF);
        c.drawRoundRect(rect, rad, rad, rim);

        title.setColor(palette.dim);
        c.drawText("Weather", 18f * k, 23f * k, title);

        if (data == null || !data.available) {
            drawUnavailable(c, w);
            return;
        }

        int iconId = Icons.weatherIcon(data.code, data.day);
        Bitmap icon = Icons.get(iconId, Math.round(27f * k), palette.tabInk);
        c.drawBitmap(icon, 16f * k, 40f * k, null);

        big.setColor(palette.ink);
        c.drawText(Math.round(data.temp) + "°", 52f * k, 80f * k, big);

        cond.setColor(palette.dim);
        c.drawText(data.condition, 18f * k, 101f * k, cond);

        small.setColor(palette.faint);
        c.drawText("H:" + Math.round(data.high) + "°   L:" + Math.round(data.low) + "°",
                18f * k, 118f * k, small);

        boolean stale = data.stale() || !problem.isEmpty();
        tiny.setColor(stale ? palette.amber : palette.faint);
        String updated = problem.isEmpty() ? data.updated : (data.updated + " · offline");
        c.drawText(updated, 18f * k, 133f * k, tiny);

        float split = 218f * k;
        divider.setStrokeWidth(1f * k);
        divider.setColor(palette.dark ? 0x22FFFFFF : 0x1A000000);
        c.drawLine(split, 16f * k, split, h - 16f * k, divider);

        float rightBlock = 158f * k;
        float hourlyLeft = split + 18f * k;
        float hourlyRight = w - rightBlock - 16f * k;
        int count = Math.max(1, Math.min(7, data.hours.size()));
        float colW = (hourlyRight - hourlyLeft) / count;
        for (int i = 0; i < count; i++) {
            WeatherService.Hour hr = data.hours.get(i);
            float cx = hourlyLeft + colW * i + colW / 2f;
            hour.setColor(palette.dim);
            hour.setTextAlign(Paint.Align.CENTER);
            c.drawText(hr.label, cx, 30f * k, hour);
            Bitmap hi = Icons.get(Icons.weatherIcon(hr.code, hr.day), Math.round(20f * k),
                    palette.tabInk);
            c.drawBitmap(hi, cx - hi.getWidth() / 2f, 38f * k, null);
            hourTemp.setColor(palette.ink);
            hourTemp.setTextAlign(Paint.Align.CENTER);
            c.drawText(Math.round(hr.temp) + "°", cx, 78f * k, hourTemp);
        }

        float sunX = w - rightBlock;
        Bitmap rise = Icons.get(Icons.SUNRISE, Math.round(15f * k), palette.dim);
        c.drawBitmap(rise, sunX, 40f * k, null);
        small.setColor(palette.dim);
        c.drawText("Sunrise", sunX + 21f * k, 44f * k, small);
        cond.setColor(palette.tabInk);
        c.drawText(data.sunrise, sunX + 21f * k, 60f * k, cond);

        Bitmap set = Icons.get(Icons.SUNSET, Math.round(15f * k), palette.dim);
        c.drawBitmap(set, sunX, 78f * k, null);
        small.setColor(palette.dim);
        c.drawText("Sunset", sunX + 21f * k, 82f * k, small);
        cond.setColor(palette.tabInk);
        c.drawText(data.sunset, sunX + 21f * k, 98f * k, cond);
    }

    private void drawUnavailable(Canvas c, float w) {
        Bitmap cloud = Icons.get(Icons.CLOUD, Math.round(26f * k), palette.faint);
        c.drawBitmap(cloud, 18f * k, 46f * k, null);
        cond.setColor(palette.dim);
        c.drawText("Weather unavailable", 54f * k, 62f * k, cond);
        small.setColor(palette.faint);
        String sub = problem.isEmpty() ? "Waiting for the first forecast" : problem;
        c.drawText(sub, 18f * k, 90f * k, small);
        tiny.setColor(palette.faint);
        c.drawText("Open-Meteo · retries automatically", 18f * k, 108f * k, tiny);
    }
}
