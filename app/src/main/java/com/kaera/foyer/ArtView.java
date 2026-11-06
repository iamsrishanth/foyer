package com.kaera.foyer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.view.View;
import android.view.ViewOutlineProvider;

/** One media slot: landscape backdrop for the wide card, portrait poster for the other two. */
public class ArtView extends View {

    private final MediaLibrary library;
    private MediaLibrary.Item item;
    private final boolean wide;
    private Palette palette = Palette.light();
    private float k = 1f;
    private Bitmap art;
    private boolean focused;
    private boolean artFailed;
    private String placeholder = "";
    private String wantedId;

    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF rect = new RectF();
    private final Rect src = new Rect();
    private final RectF dst = new RectF();

    public ArtView(Context c, MediaLibrary lib, boolean wide) {
        super(c);
        this.library = lib;
        this.wide = wide;
        setFocusable(true);
        setFocusableInTouchMode(true);
        setClickable(true);
        setElevation(4f * getResources().getDisplayMetrics().density);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                float r = 14f * (v.getHeight() / 220f);
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
            }
        });
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        rim.setStyle(Paint.Style.STROKE);
    }

    public void setScale(float k) {
        this.k = k;
        label.setTextSize(11f * k);
        invalidate();
    }

    public void setPalette(Palette p) {
        palette = p;
        invalidate();
    }

    public void setPlaceholder(String text) {
        placeholder = text == null ? "" : text;
        invalidate();
    }

    public void setItem(MediaLibrary.Item it) {
        if (item != null && it != null && item.id.equals(it.id)) {
            item = it;
            return;
        }
        item = it;
        art = null;
        artFailed = false;
        wantedId = it == null ? null : it.id;
        setContentDescription(it == null ? placeholder
                : it.name + (it.type == null || it.type.isEmpty() ? "" : ", " + it.type));
        if (it != null) requestArt();
        invalidate();
    }

    public MediaLibrary.Item item() {
        return item;
    }

    /** A refresh may have downloaded art after the first request; retry only when it is still absent. */
    public void retryIfMissing() {
        if (item != null && art == null) {
            artFailed = false;
            requestArt();
        }
    }

    private void requestArt() {
        final MediaLibrary.Item it = item;
        if (it == null || library == null) return;
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            w = Math.round((wide ? 520 : 160) * k);
            h = Math.round(220 * k);
        }
        final String id = it.id;
        library.image(it, wide, w, h, new MediaLibrary.ImageListener() {
            @Override
            public void onImage(Bitmap bitmap) {
                if (item == null || !item.id.equals(id)) return;
                art = bitmap;
                artFailed = bitmap == null;
                invalidate();
            }
        });
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (item != null && art == null) requestArt();
    }

    public void setFocusVisual(boolean on) {
        if (focused == on) {
            invalidate();
            return;
        }
        focused = on;
        float grow = wide ? 1.032f : 1.045f;
        animate().scaleX(on ? grow : 1f).scaleY(on ? grow : 1f)
                .translationY(on ? -5f * k : 0f)
                .setDuration(on ? 180L : 140L)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
        setElevation((on ? 14f : 4f) * getResources().getDisplayMetrics().density);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;
        float rad = 14f * (h / 220f);

        rect.set(0, 0, w, h);
        clip.reset();
        clip.addRoundRect(rect, rad, rad, Path.Direction.CW);

        c.save();
        c.clipPath(clip);

        if (art != null && !art.isRecycled()) {
            cover(art, w, h);
            c.drawBitmap(art, src, dst, bmpPaint);
            shade.setShader(null);
        } else {
            fill.setShader(new LinearGradient(0, 0, 0, h,
                    palette.dark ? 0xFF23262C : 0xFFE7E9EC,
                    palette.dark ? 0xFF181B20 : 0xFFDADDDF, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, fill);
            fill.setShader(null);
            int icon = item == null ? Icons.TV : Icons.CLAP;
            Bitmap b = Icons.get(icon, Math.round(22f * k), palette.faint);
            c.drawBitmap(b, w / 2f - b.getWidth() / 2f, h / 2f - b.getHeight() / 2f - 8f * k, null);
            String text = item == null ? placeholder
                    : (artFailed ? "No artwork available" : "Loading artwork…");
            if (text != null && !text.isEmpty()) {
                label.setColor(palette.dim);
                float tw = label.measureText(text);
                c.drawText(text, w / 2f - tw / 2f, h / 2f + 24f * k, label);
            }
        }
        c.restore();

        rim.setStrokeWidth(1f * k);
        rim.setColor(focused ? (palette.dark ? 0xFFFFFFFF : 0xFFFFFFFF)
                : (palette.dark ? 0x33FFFFFF : 0x55FFFFFF));
        c.drawRoundRect(rect, rad, rad, rim);

        if (focused) {
            float inset = 2f * k;
            rect.inset(inset, inset);
            rim.setStrokeWidth(3f * k);
            rim.setColor(0xFFFFFFFF);
            c.drawRoundRect(rect, rad - inset, rad - inset, rim);
        }
    }

    private void cover(Bitmap b, float w, float h) {
        float scale = Math.max(w / b.getWidth(), h / b.getHeight());
        float dw = b.getWidth() * scale;
        float dh = b.getHeight() * scale;
        float left = (w - dw) / 2f;
        float top = (h - dh) / 2f;
        src.set(0, 0, b.getWidth(), b.getHeight());
        dst.set(left, top, left + dw, top + dh);
    }
}
