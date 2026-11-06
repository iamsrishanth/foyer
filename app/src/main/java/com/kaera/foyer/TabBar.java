package com.kaera.foyer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.view.View;

/**
 * The library tabs (Shows, Movies, Anime, Dramas by default), with the reference's white rounded
 * highlight on the active one.
 */
public class TabBar extends View {

    private String[] labels = {"Shows", "Movies", "Anime", "Dramas"};

    private Palette palette = Palette.light();
    private float k = 1f;
    private int active;
    private int focused = -1;

    private final TextPaint on = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint off = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private float[] left = new float[0];
    private float[] width = new float[0];

    public TabBar(Context c) {
        super(c);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setClickable(true);
        on.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        off.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        ring.setStyle(Paint.Style.STROKE);
        metrics();
    }

    public void setScale(float k) {
        this.k = k;
        metrics();
        invalidate();
    }

    public void setPalette(Palette p) {
        palette = p;
        invalidate();
    }

    /** Replaces the tab labels (the Jellyfin library names). Keeps the active index in range. */
    public void setLabels(String[] names) {
        if (names == null || names.length == 0) return;
        labels = names.clone();
        if (active >= labels.length) active = labels.length - 1;
        if (focused >= labels.length) focused = -1;
        metrics();
        updateDescription();
        invalidate();
    }

    public int count() {
        return labels.length;
    }

    public String label(int index) {
        return index >= 0 && index < labels.length ? labels[index] : "";
    }

    public void setActive(int index) {
        if (labels.length == 0) return;
        active = Math.max(0, Math.min(labels.length - 1, index));
        updateDescription();
        invalidate();
    }

    public void setFocused(boolean focusedBar) {
        if (!focusedBar) setFocusIndex(-1);
    }

    public void setFocusIndex(int index) {
        focused = index >= labels.length ? -1 : index;
        updateDescription();
        invalidate();
    }

    public int active() {
        return active;
    }

    private void metrics() {
        if (k <= 0) return;
        if (left.length != labels.length) {
            left = new float[labels.length];
            width = new float[labels.length];
        }
        on.setTextSize(15f * k);
        off.setTextSize(15f * k);
        on.setColor(palette.tabInk);
        off.setColor(palette.tabIdle);
        float x = 0f;
        for (int i = 0; i < labels.length; i++) {
            float tw = on.measureText(labels[i]);
            width[i] = tw + 24f * k;
            left[i] = x;
            x += width[i] + 8f * k;
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        if (k <= 0 || labels.length == 0) return;
        float h = getHeight();
        float rad = 17f * k;

        r.set(left[active], 0, left[active] + width[active], h);
        pill.setColor(palette.dark ? 0xFF2A2E35 : 0xFFFFFFFF);
        pill.setShadowLayer(7f * k, 0f, 3f * k, palette.dark ? 0x66000000 : 0x26000000);
        c.drawRoundRect(r, rad, rad, pill);
        pill.clearShadowLayer();
        if (palette.dark) {
            ring.setStrokeWidth(1f * k);
            ring.setColor(0x33FFFFFF);
            c.drawRoundRect(r, rad, rad, ring);
        }

        if (focused >= 0) {
            r.set(left[focused] + 1.2f * k, 1.2f * k,
                    left[focused] + width[focused] - 1.2f * k, h - 1.2f * k);
            ring.setStrokeWidth(1.8f * k);
            ring.setColor(palette.dark ? 0x55FFFFFF : 0x331D1D1F);
            c.drawRoundRect(r, rad - 1.2f * k, rad - 1.2f * k, ring);
        }

        float baseline = 24.4f * k;
        for (int i = 0; i < labels.length; i++) {
            TextPaint p = i == active ? on : off;
            p.setColor(i == active ? palette.tabInk : palette.tabIdle);
            float tw = p.measureText(labels[i]);
            float tx = left[i] + (width[i] - tw) / 2f;
            c.drawText(labels[i], tx, baseline, p);
        }
    }

    /** Hit test in the bar's own (pixel) coordinates; returns -1 when outside a tab. */
    public int hitTest(float x) {
        for (int i = 0; i < labels.length; i++) {
            if (x >= left[i] && x <= left[i] + width[i]) return i;
        }
        return -1;
    }

    private void updateDescription() {
        String activeName = label(active);
        String focusName = focused >= 0 && focused != active ? label(focused) : "";
        setContentDescription("Library tabs. " + activeName + " selected"
                + (focusName.isEmpty() ? "" : ", " + focusName + " focused"));
    }
}
