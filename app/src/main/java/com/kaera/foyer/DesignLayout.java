package com.kaera.foyer;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/**
 * Positions children in the reference's 960 x 540 design space, scaled to whatever the display is.
 * All spacing, artwork sizes and dock geometry come from that one frame.
 */
public class DesignLayout extends ViewGroup {

    public static final float DESIGN_W = 960f;
    public static final float DESIGN_H = 540f;

    protected float scale = 1f;
    private float forcedScale = 0f;

    public static final class LP extends ViewGroup.LayoutParams {
        public final float x, y, w, h;

        public LP(float x, float y, float w, float h) {
            super(WRAP_CONTENT, WRAP_CONTENT);
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    public DesignLayout(Context c) {
        super(c);
        setWillNotDraw(true);
    }

    public float scale() {
        return scale;
    }

    /** Nested rows inherit the full-screen design scale instead of deriving one from their box. */
    public void setForcedScale(float s) {
        if (Math.abs(s - forcedScale) < 0.001f) return;
        forcedScale = s;
        requestLayout();
    }

    public void add(View v, float x, float y, float w, float h) {
        addView(v, new LP(x, y, w, h));
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = MeasureSpec.getSize(heightSpec);
        scale = forcedScale > 0f ? forcedScale : Math.min(w / DESIGN_W, h / DESIGN_H);
        setMeasuredDimension(w, h);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            LayoutParams lp = child.getLayoutParams();
            if (!(lp instanceof LP)) {
                measureChild(child, widthSpec, heightSpec);
                continue;
            }
            LP p = (LP) lp;
            int cw = Math.max(1, Math.round(p.w * scale));
            int ch = Math.max(1, Math.round(p.h * scale));
            child.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY));
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l;
        int h = b - t;
        float ox = forcedScale > 0f ? 0f : (w - DESIGN_W * scale) / 2f;
        float oy = forcedScale > 0f ? 0f : (h - DESIGN_H * scale) / 2f;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            LayoutParams lp = child.getLayoutParams();
            if (!(lp instanceof LP)) continue;
            LP p = (LP) lp;
            int cx = Math.round(ox + p.x * scale);
            int cy = Math.round(oy + p.y * scale);
            int cw = Math.max(1, Math.round(p.w * scale));
            int ch = Math.max(1, Math.round(p.h * scale));
            child.layout(cx, cy, cx + cw, cy + ch);
        }
    }

    /** Re-run layout when a child's design box changes (rare; tabs/dock use fixed boxes). */
    public void relayout() {
        requestLayout();
    }
}
