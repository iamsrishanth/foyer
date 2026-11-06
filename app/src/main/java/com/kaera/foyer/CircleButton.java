package com.kaera.foyer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewOutlineProvider;

/** One of the three compact circular controls at the top right. */
public class CircleButton extends View {

    private Palette palette = Palette.light();
    private float k = 1f;
    private int icon;
    private boolean focused;
    private boolean alert;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    public CircleButton(Context c) {
        super(c);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setClickable(true);
        setElevation(3f * getResources().getDisplayMetrics().density);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                float cx = v.getWidth() / 2f;
                float cy = v.getHeight() / 2f;
                float rad = Math.min(cx, cy);
                o.setRoundRect((int) (cx - rad), (int) (cy - rad),
                        (int) (cx + rad), (int) (cy + rad), rad);
            }
        });
        newPaint();
    }

    private void newPaint() {
        rim.setStyle(Paint.Style.STROKE);
        ring.setStyle(Paint.Style.STROKE);
    }

    public void setScale(float k) {
        this.k = k;
        invalidate();
    }

    public void setPalette(Palette p) {
        palette = p;
        invalidate();
    }

    public void setIcon(int icon, String description) {
        this.icon = icon;
        setContentDescription(description);
        invalidate();
    }

    public void setAlert(boolean alert, String description) {
        if (this.alert == alert && description == null) return;
        this.alert = alert;
        if (description != null) setContentDescription(description);
        invalidate();
    }

    public void setFocusVisual(boolean focused) {
        this.focused = focused;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        float rad = 17f * k;
        if (rad <= 0) return;

        r.set(cx - rad, cy - rad, cx + rad, cy + rad);
        fill.setColor(palette.dark ? 0x2EFFFFFF : 0xF2FFFFFF);
        c.drawCircle(cx, cy, rad, fill);
        rim.setStrokeWidth(1.1f * k);
        rim.setColor(palette.dark ? 0x33FFFFFF : 0xFFFFFFFF);
        c.drawCircle(cx, cy, rad, rim);

        if (focused) {
            ring.setStrokeWidth(2f * k);
            ring.setColor(palette.dark ? 0x88FFFFFF : 0x331D1D1F);
            c.drawCircle(cx, cy, rad - 1.2f * k, ring);
        }

        int ink = alert ? palette.amber : palette.ink;
        android.graphics.Bitmap bmp = Icons.get(icon, Math.round(17f * k), ink);
        c.drawBitmap(bmp, cx - bmp.getWidth() / 2f, cy - bmp.getHeight() / 2f, null);

        if (alert) {
            float dx = cx + rad * 0.62f;
            float dy = cy - rad * 0.62f;
            dot.setColor(0xFFFFFFFF);
            c.drawCircle(dx, dy, 4.4f * k, dot);
            dot.setColor(palette.amber);
            c.drawCircle(dx, dy, 3.1f * k, dot);
        }
    }
}
