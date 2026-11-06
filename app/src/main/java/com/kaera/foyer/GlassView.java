package com.kaera.foyer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * The bright-edged translucent dock. The glass is rendered once into a small bitmap (with its
 * shadow) and reused while it moves, so the drawer animation never blurs a full-size layer.
 */
public class GlassView extends View {

    private static final float PAD = 26f;

    private Palette palette = Palette.light();
    private Bitmap cache;
    private float cacheK;
    private float pad;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    public GlassView(Context c) {
        super(c);
        setWillNotDraw(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void setPalette(Palette p) {
        if (palette.dark != p.dark) cache = null;
        palette = p;
        invalidate();
    }

    public void setDesignScale(float k) {
        if (Math.abs(k - cacheK) < 0.01f) return;
        cacheK = k;
        cache = null;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        cache = null;
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (cache == null || cache.isRecycled()) build(w, h);
        if (cache != null) c.drawBitmap(cache, -pad, -pad, null);
    }

    private void build(int w, int h) {
        pad = PAD * (w / 920f);
        try {
            cache = Bitmap.createBitmap(w + Math.round(pad * 2), h + Math.round(pad * 2),
                    Bitmap.Config.ARGB_8888);
        } catch (Throwable t) {
            return;
        }
        Canvas c = new Canvas(cache);
        c.translate(pad, pad);
        RectF inner = new RectF(0, 0, w, h);
        float rad = 26f * (w / 920f);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShadowLayer(15f * (w / 920f), 0f, 7f * (w / 920f),
                palette.dark ? 0x77000000 : 0x3A000000);
        p.setColor(palette.dark ? 0xF21A1D22 : 0xF2FFFFFF);
        c.drawRoundRect(inner, rad, rad, p);
        p.clearShadowLayer();

        LinearGradient glass = new LinearGradient(inner.left, inner.top, inner.left, inner.bottom,
                palette.dark ? 0xF220242B : 0xF7FFFFFF,
                palette.dark ? 0xDB16191E : 0xD2FFFFFF, Shader.TileMode.CLAMP);
        p.setShader(glass);
        c.drawRoundRect(inner, rad, rad, p);
        p.setShader(null);

        Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1f, 1.7f * (w / 920f)));
        rim.setColor(palette.dark ? 0x55FFFFFF : 0xFFFFFFFF);
        c.drawRoundRect(inner, rad, rad, rim);

        rim.setStrokeWidth(Math.max(1f, 1.1f * (w / 920f)));
        rim.setColor(palette.dark ? 0x2AFFFFFF : 0xDFFFFFFF);
        RectF top = new RectF(inner);
        top.inset(1.6f, 1.6f);
        c.drawRoundRect(top, rad - 1.6f, rad - 1.6f, rim);
    }
}
