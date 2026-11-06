package com.kaera.foyer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.List;

/** The glass shelf's horizontal row of app tiles; keeps every installed app reachable. */
public class DockRow extends ViewGroup {

    public interface Listener {
        void onDockClicked(int index);
    }

    private final List<DockTile> tiles = new ArrayList<DockTile>();
    private List<String> ids = new ArrayList<String>();
    private Palette palette = Palette.light();
    private float k = 1f;
    private float scroll;
    private float target;
    private int focusIndex = -1;
    private boolean focusVisible;
    private Listener listener;

    public DockRow(Context c) {
        super(c);
        setClipChildren(true);
        setClipToPadding(true);
        setWillNotDraw(true);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setScale(float k) {
        this.k = k;
        for (DockTile t : tiles) t.setScale(k);
        requestLayout();
    }

    public void setPalette(Palette p) {
        palette = p;
        for (DockTile t : tiles) t.setPalette(p);
        invalidate();
    }

    public void setApps(List<AppEntry> apps, int keepIndex) {
        List<String> next = new ArrayList<String>(apps.size());
        for (AppEntry a : apps) next.add(a.id());
        if (next.equals(ids) && tiles.size() == apps.size()) {
            return;
        }
        ids = next;
        removeAllViews();
        tiles.clear();
        for (int i = 0; i < apps.size(); i++) {
            DockTile t = new DockTile(getContext());
            t.setScale(k);
            t.setPalette(palette);
            t.setApp(apps.get(i), i);
            t.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    int idx = tiles.indexOf(v);
                    if (idx >= 0 && listener != null) listener.onDockClicked(idx);
                }
            });
            addView(t, new ViewGroup.LayoutParams(
                    Math.max(1, Math.round(112 * k)), Math.max(1, Math.round(66 * k))));
            tiles.add(t);
        }
        scroll = 0f;
        target = 0f;
        if (focusIndex >= tiles.size()) focusIndex = tiles.size() - 1;
        if (focusIndex >= 0) setFocusIndex(focusIndex, focusVisible);
        requestLayout();
    }

    public int count() {
        return tiles.size();
    }

    public AppEntry appAt(int i) {
        return i >= 0 && i < tiles.size() ? tiles.get(i).entry : null;
    }

    public void setFocusIndex(int index, boolean visible) {
        if (index < 0 || index >= tiles.size()) {
            focusVisible = false;
            for (DockTile t : tiles) t.setFocusVisual(false);
            invalidate();
            return;
        }
        if (focusIndex != index) {
            focusIndex = index;
        }
        focusVisible = visible;
        for (int i = 0; i < tiles.size(); i++) {
            DockTile t = tiles.get(i);
            t.setFocusVisual(visible && i == index);
            if (visible && i == index) t.requestFocus();
        }
        ensureVisible(index);
        invalidate();
    }

    private void ensureVisible(int index) {
        if (index < 0 || index >= tiles.size()) return;
        float left = 20f + index * 128f;
        float right = left + 112f;
        float viewLeft = 20f;
        // Leave room for the focused tile's 1.12x ring so it is never clipped at the glass edge.
        float viewRight = 890f;
        float next = target;
        if (left - next < viewLeft) next = left - viewLeft;
        if (right - next > viewRight) next = right - viewRight;
        float contentRight = 20f + tiles.size() * 128f - 16f;
        float max = Math.max(0f, contentRight - viewRight);
        if (next < 0f) next = 0f;
        if (next > max) next = max;
        target = next;
    }

    /** Called from the screen's animation frame loop. Returns true while still settling. */
    public boolean step(float dt) {
        if (Math.abs(target - scroll) < 0.05f) {
            if (scroll != target) {
                scroll = target;
                applyScroll();
            }
            return false;
        }
        float f = Math.min(1f, dt * 16f);
        scroll += (target - scroll) * f;
        if (Math.abs(target - scroll) < 0.05f) scroll = target;
        applyScroll();
        return true;
    }

    /** True when a focus change has left the row between two scroll positions. */
    public boolean isSettling() {
        return Math.abs(target - scroll) >= 0.05f;
    }

    private void applyScroll() {
        for (DockTile t : tiles) t.setTranslationX(-scroll * k);
    }

    @Override
    protected void dispatchDraw(Canvas c) {
        c.save();
        // Keep the 20 design-pixel glass padding clear; a partly scrolled tile stops at the edge.
        c.clipRect(0, 0, getWidth() - Math.round(20f * k), getHeight());
        super.dispatchDraw(c);
        c.restore();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(w, h);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            child.measure(MeasureSpec.makeMeasureSpec(Math.round(112 * k), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.round(66 * k), MeasureSpec.EXACTLY));
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int cw = Math.max(1, Math.round(112f * k));
        int ch = Math.max(1, Math.round(66f * k));
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            int cx = Math.round((20f + i * 128f) * k);
            int cy = Math.round(14f * k);
            child.layout(cx, cy, cx + cw, cy + ch);
            child.setTranslationX(-scroll * k);
        }
    }

    // ------------------------------------------------------------------ tile

    public static class DockTile extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final RectF rect = new RectF();
        private final RectF dst = new RectF();
        private Palette palette = Palette.light();
        private float k = 1f;
        AppEntry entry;
        private Bitmap banner;
        private Bitmap icon;
        private CharSequence shortLabel = "";
        private boolean focused;
        private boolean showBanner;

        public DockTile(Context c) {
            super(c);
            setFocusable(true);
            setFocusableInTouchMode(true);
            setClickable(true);
            setElevation(2f * getResources().getDisplayMetrics().density);
            text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            rim.setStyle(Paint.Style.STROKE);
            setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View v, android.graphics.Outline o) {
                    float r = v.getHeight() * 12f / 66f;
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
                }
            });
        }

        public void setScale(float k) {
            this.k = k;
            text.setTextSize(10f * k);
            updateLabel();
            requestLayout();
            invalidate();
        }

        public void setPalette(Palette p) {
            palette = p;
            invalidate();
        }

        public void setApp(AppEntry app, int index) {
            entry = app;
            setContentDescription(app == null ? "" : app.label);
            if (app == null) return;
            android.content.pm.PackageManager pm = getContext().getPackageManager();
            try {
                Drawable d = pm.getApplicationBanner(app.pkg);
                if (d != null) {
                    banner = toBitmap(d, 224, 132);
                    showBanner = banner != null;
                }
            } catch (Throwable ignored) {
            }
            try {
                Drawable d = pm.getApplicationIcon(app.pkg);
                icon = toBitmap(d, 72, 72);
            } catch (Throwable ignored) {
            }
            if (!showBanner && icon == null) {
                showBanner = false;
            }
            updateLabel();
            invalidate();
        }

        private void updateLabel() {
            if (entry == null) {
                shortLabel = "";
                return;
            }
            float max = Math.max(1f, 102f * k);
            shortLabel = TextUtils.ellipsize(entry.label, text, max, TextUtils.TruncateAt.END);
        }

        public void setFocusVisual(boolean on) {
            if (focused == on) return;
            focused = on;
            animate().scaleX(on ? 1.12f : 1f).scaleY(on ? 1.12f : 1f)
                    .translationY(on ? -4f * k : 0f)
                    .setDuration(on ? 180L : 140L)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
            setElevation((on ? 12f : 2f) * getResources().getDisplayMetrics().density);
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            invalidateOutline();
            updateLabel();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;
            float rad = 12f * k;
            rect.set(0, 0, w, h);
            clip.reset();
            clip.addRoundRect(rect, rad, rad, Path.Direction.CW);

            c.save();
            c.clipPath(clip);
            if (showBanner && banner != null && !banner.isRecycled()) {
                fill.setColor(palette.dark ? 0xFF2A2E35 : 0xFFFFFFFF);
                c.drawRect(0, 0, w, h, fill);
                float scale = Math.max(w / banner.getWidth(), h / banner.getHeight());
                float dw = banner.getWidth() * scale;
                float dh = banner.getHeight() * scale;
                dst.set((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f);
                c.drawBitmap(banner, null, dst, bmpPaint);
            } else {
                fill.setColor(palette.dark ? 0xFF2A2E35 : 0xFFFFFFFF);
                c.drawRect(0, 0, w, h, fill);
                if (icon != null && !icon.isRecycled()) {
                    float size = 36f * k;
                    float left = (w - size) / 2f;
                    c.drawBitmap(icon, null, new RectF(left, 8f * k, left + size, 8f * k + size),
                            bmpPaint);
                } else if (entry != null) {
                    fill.setColor(palette.ink);
                    Paint letter = new Paint(Paint.ANTI_ALIAS_FLAG);
                    letter.setColor(palette.ink);
                    letter.setTextSize(24f * k);
                    letter.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                    letter.setTextAlign(Paint.Align.CENTER);
                    c.drawText(entry.label.substring(0, 1), w / 2f, 38f * k, letter);
                }
                text.setColor(palette.ink);
                text.setTextAlign(Paint.Align.CENTER);
                c.drawText(shortLabel, 0, shortLabel.length(), w / 2f, 57f * k, text);
            }
            c.restore();

            if (showBanner && banner != null) {
                rim.setStrokeWidth(1f * k);
                rim.setColor(palette.dark ? 0x33FFFFFF : 0x33FFFFFF);
                c.drawRoundRect(rect, rad, rad, rim);
            }

            if (focused) {
                RectF r = new RectF(rect);
                r.inset(1.5f * k, 1.5f * k);
                rim.setStrokeWidth(3f * k);
                rim.setColor(0xFFFFFFFF);
                c.drawRoundRect(r, rad - 1.5f * k, rad - 1.5f * k, rim);
            }
        }

        private static Bitmap toBitmap(Drawable d, int w, int h) {
            try {
                Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(b);
                d.setBounds(0, 0, w, h);
                d.draw(c);
                return b;
            } catch (Throwable t) {
                return null;
            }
        }
    }
}
