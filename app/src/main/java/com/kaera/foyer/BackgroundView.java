package com.kaera.foyer;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * The pale, softly tinted background. It reuses a tiny blurred backdrop (never a full-resolution
 * blur) and washes it almost all the way out so the reference's light look survives.
 */
public class BackgroundView extends View {

    private Palette palette = Palette.light();
    private Bitmap ambient;
    private Bitmap previous;
    private Bitmap wallpaper;
    private boolean photo;
    private float mix = 1f;
    private ValueAnimator mixAnim;

    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint wash = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private Shader baseShader;
    private Shader washShader;
    private Shader glowShader;
    private Shader vignetteShader;
    private float radius;

    public BackgroundView(Context c) {
        super(c);
        setWillNotDraw(false);
    }

    public void setPalette(Palette p) {
        palette = p;
        rebuild();
        invalidate();
    }

    public void setWallpaper(Bitmap bmp, boolean usePhoto) {
        wallpaper = bmp;
        photo = usePhoto;
        invalidate();
    }

    public void setAmbient(Bitmap bmp) {
        if (bmp == ambient) return;
        previous = ambient;
        ambient = bmp;
        mix = 0f;
        if (mixAnim != null) mixAnim.cancel();
        if (bmp == null) {
            mix = 1f;
            invalidate();
            return;
        }
        mixAnim = ValueAnimator.ofFloat(0f, 1f);
        mixAnim.setDuration(700L);
        mixAnim.setInterpolator(new DecelerateInterpolator());
        mixAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                mix = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        mixAnim.start();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        radius = (float) Math.hypot(w, h) * 0.62f;
        rebuild();
    }

    private void rebuild() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        baseShader = new LinearGradient(0, 0, 0, h, palette.bgTop, palette.bgBottom,
                Shader.TileMode.CLAMP);
        washShader = new LinearGradient(0, 0, 0, h,
                palette.dark ? 0xFF10141A : 0xFFF1F3F5,
                palette.dark ? 0xFF0A0C10 : 0xFFD8DBDE,
                Shader.TileMode.CLAMP);
        glowShader = new RadialGradient(w * 0.5f, -h * 0.25f, radius,
                palette.dark ? 0x334C5C74 : 0x99FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
        vignetteShader = new RadialGradient(w * 0.5f, h * 0.46f, radius,
                palette.dark ? 0x00000000 : 0x00FFFFFF,
                palette.dark ? 0x40000000 : 0x22000000, Shader.TileMode.CLAMP);
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        if (photo && wallpaper != null && !wallpaper.isRecycled()) {
            paint.setShader(null);
            paint.setAlpha(255);
            drawCover(c, wallpaper, w, h);
            wash.setShader(null);
            wash.setColor(palette.dark ? 0xB4000000 : 0x99FFFFFF);
            c.drawRect(0, 0, w, h, wash);
        } else {
            paint.setShader(baseShader);
            paint.setAlpha(255);
            c.drawRect(0, 0, w, h, paint);
            paint.setShader(null);

            int amount = palette.dark ? 0x5A : 0x46;
            if (ambient != null && !ambient.isRecycled()) {
                if (previous != null && previous != ambient && !previous.isRecycled() && mix < 1f) {
                    paint.setAlpha((int) (amount * (1f - mix)));
                    drawCover(c, previous, w, h);
                }
                paint.setAlpha((int) (amount * mix));
                drawCover(c, ambient, w, h);
            }

            wash.setShader(washShader);
            wash.setAlpha(palette.dark ? 0xC8 : 0xDA);
            c.drawRect(0, 0, w, h, wash);
            wash.setShader(null);

            wash.setShader(glowShader);
            wash.setAlpha(255);
            c.drawRect(0, 0, w, h, wash);
            wash.setShader(null);
        }

        wash.setShader(vignetteShader);
        wash.setAlpha(255);
        c.drawRect(0, 0, w, h, wash);
        wash.setShader(null);
    }

    private void drawCover(Canvas c, Bitmap b, int w, int h) {
        float scale = Math.max((float) w / b.getWidth(), (float) h / b.getHeight());
        float dw = b.getWidth() * scale;
        float dh = b.getHeight() * scale;
        float left = (w - dw) / 2f;
        float top = (h - dh) / 2f;
        rect.set(left, top, left + dw, top + dh);
        c.drawBitmap(b, null, rect, paint);
    }
}
