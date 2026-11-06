package com.kaera.foyer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewOutlineProvider;

/** A drawer input or shortcut: icon above, small label below, glass card. */
public class MiniTile extends View {

    private Palette palette = Palette.light();
    private float k = 1f;
    private final int icon;
    private final String label;
    private final String action;
    private boolean focused;
    private boolean usable = true;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private CharSequence shortLabel = "";

    public MiniTile(Context c, int icon, String label, boolean usable, String action) {
        super(c);
        this.icon = icon;
        this.label = label;
        this.usable = usable;
        this.action = action;
        setFocusable(usable);
        setFocusableInTouchMode(usable);
        setClickable(usable);
        setContentDescription(label + (usable ? "" : ", unavailable"));
        setEnabled(usable);
        setAlpha(usable ? 1f : 0.5f);
        setElevation((usable ? 2f : 0f) * getResources().getDisplayMetrics().density);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        rim.setStyle(Paint.Style.STROKE);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(),
                        v.getHeight() * 10f / 60f);
            }
        });
        updateLabel();
    }

    public String action() {
        return action;
    }

    public boolean usable() {
        return usable;
    }

    public void setScale(float k) {
        this.k = k;
        text.setTextSize(10f * k);
        updateLabel();
        invalidate();
    }

    public void setPalette(Palette p) {
        palette = p;
        invalidate();
    }

    public void setFocusVisual(boolean on) {
        if (focused == on) return;
        focused = on;
        animate().scaleX(on ? 1.05f : 1f).scaleY(on ? 1.05f : 1f)
                .setDuration(on ? 150L : 120L)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
        invalidate();
    }

    public void setUsable(boolean usable) {
        this.usable = usable;
        setFocusable(usable);
        setClickable(usable);
        setAlpha(usable ? 1f : 0.5f);
        invalidate();
    }

    private void updateLabel() {
        float max = Math.max(1f, getDesignWidth() - 12f * k);
        shortLabel = TextUtils.ellipsize(label, text, max, TextUtils.TruncateAt.END);
    }

    private float getDesignWidth() {
        return 94f * k;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        float max = Math.max(1f, w - 12f * k);
        shortLabel = TextUtils.ellipsize(label, text, max, TextUtils.TruncateAt.END);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;
        float rad = 10f * k;
        rect.set(0, 0, w, h);
        fill.setColor(palette.dark
                ? (focused ? 0x66FFFFFF : 0x2AFFFFFF)
                : (focused ? 0xFFFFFFFF : 0x66FFFFFF));
        c.drawRoundRect(rect, rad, rad, fill);

        rim.setStrokeWidth(1.2f * k);
        rim.setColor(palette.dark ? 0x33FFFFFF : (focused ? 0xFFFFFFFF : 0x8CFFFFFF));
        c.drawRoundRect(rect, rad, rad, rim);

        int ink = usable ? (palette.dark ? palette.ink : palette.tabInk) : palette.faint;
        Bitmap b = Icons.get(icon, Math.round(20f * k), ink);
        c.drawBitmap(b, w / 2f - b.getWidth() / 2f, 12f * k, null);

        text.setColor(usable ? (palette.dark ? palette.ink : 0xFF2A2A2E) : palette.faint);
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText(shortLabel, 0, shortLabel.length(), w / 2f, 48f * k, text);

        if (focused) {
            RectF r2 = new RectF(rect);
            r2.inset(1.4f * k, 1.4f * k);
            rim.setStrokeWidth(2f * k);
            rim.setColor(palette.dark ? 0xFFFFFFFF : 0xFF1D1D1F);
            c.drawRoundRect(r2, rad - 1.4f * k, rad - 1.4f * k, rim);
        }
    }
}
