package com.byso.labelsheet;

import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;

/** Builds the 3D surfaces: raised buttons with a lip and highlight, pressed-in wells, glass and glossy bars. */
final class Depth {
    private Depth() {}

    private static GradientDrawable shape(int[] colors, float r) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors);
        g.setCornerRadius(r);
        return g;
    }

    /**
     * Raised button: darker lip along the bottom, a top-to-bottom body gradient and a soft shine on the top half.
     * Pair with View.setElevation for the drop shadow.
     */
    static Drawable raised(int top, int bottom, int lip, float r, float lipPx, boolean ripple) {
        GradientDrawable lipLayer = shape(new int[]{lip, lip}, r);
        GradientDrawable body = shape(new int[]{top, bottom}, r);
        GradientDrawable shine = shape(new int[]{0x66FFFFFF, 0x00FFFFFF}, r);
        GradientDrawable edge = new GradientDrawable();
        edge.setCornerRadius(r);
        edge.setColor(0x00000000);
        edge.setStroke(Math.max(1, Math.round(lipPx / 3f)), 0x22000000);
        LayerDrawable ld = new LayerDrawable(new Drawable[]{lipLayer, body, shine, edge});
        int lp = Math.round(lipPx);
        ld.setLayerInset(1, 0, 0, 0, lp);
        ld.setLayerInset(2, Math.round(r / 3f), 1, Math.round(r / 3f), 0);
        ld.setLayerInset(3, 0, 0, 0, 0);
        if (!ripple) return ld;
        return new RippleDrawable(ColorStateList.valueOf(Theme.RIPPLE), ld, null);
    }

    /** Pressed-in well (for a tool that is switched on): darker at the top, as if lit from above. */
    static Drawable pressed(int top, int bottom, float r) {
        GradientDrawable body = shape(new int[]{top, bottom}, r);
        GradientDrawable inner = shape(new int[]{0x40000000, 0x00000000, 0x00000000}, r);
        GradientDrawable rim = new GradientDrawable();
        rim.setCornerRadius(r);
        rim.setColor(0x00000000);
        rim.setStroke(1, 0x26000000);
        LayerDrawable ld = new LayerDrawable(new Drawable[]{body, inner, rim});
        return new RippleDrawable(ColorStateList.valueOf(Theme.RIPPLE), ld, null);
    }

    /** Translucent glass button for the coloured top bar. */
    static Drawable glass(int on, float r) {
        int a = (on & 0x00FFFFFF);
        GradientDrawable body = shape(new int[]{a | 0x30000000, a | 0x0A000000}, r);
        GradientDrawable rim = new GradientDrawable();
        rim.setCornerRadius(r);
        rim.setColor(0x00000000);
        rim.setStroke(1, a | 0x33000000);
        LayerDrawable ld = new LayerDrawable(new Drawable[]{body, rim});
        return new RippleDrawable(ColorStateList.valueOf(a | 0x33000000), ld, null);
    }

    /** Glossy bar: lighter at the top, the bar colour in the middle, slightly darker at the bottom. */
    static Drawable glossyBar(int bar) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Theme.lighter(bar, 0.16f), bar, Theme.darker(bar, 0.14f)});
        GradientDrawable shine = new GradientDrawable();
        shine.setColor(0x00000000);
        shine.setStroke(1, 0x33FFFFFF);
        LayerDrawable ld = new LayerDrawable(new Drawable[]{g, shine});
        ld.setLayerInset(1, -2, 0, -2, -2); // just the top highlight line
        return ld;
    }

    /** Panel behind tools: very soft vertical or horizontal gradient. */
    static Drawable panel(boolean vertical) {
        return new GradientDrawable(vertical ? GradientDrawable.Orientation.LEFT_RIGHT : GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Theme.DARK ? Theme.lighter(Theme.SURFACE, 0.04f) : Theme.lighter(Theme.SURFACE, 0.6f), Theme.SURFACE});
    }
}
