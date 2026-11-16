package com.byso.labelsheet;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Raised 3D tool button (icon over label) for the bottom bar (phones) and side rail (tablets / landscape). */
final class ToolTile extends LinearLayout {
    static final int STYLE_NORMAL = 0;
    static final int STYLE_PRIMARY = 1;
    static final int STYLE_DANGER = 2;

    private final ImageView icon;
    private final TextView label;
    private int style = STYLE_NORMAL;
    private boolean active = false;

    ToolTile(Context c, int iconRes, String text) {
        super(c);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);
        setClickable(true);
        setFocusable(true);
        setContentDescription(text);
        setPadding(dp(4), dp(4), dp(4), dp(7));
        setMinimumHeight(dp(56));
        setMinimumWidth(dp(56));

        icon = new ImageView(c);
        icon.setImageResource(iconRes);
        icon.setScaleType(ImageView.ScaleType.CENTER);
        addView(icon, new LayoutParams(dp(30), dp(28)));

        label = new TextView(c);
        label.setText(text);
        label.setTextSize(11.5f);
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(1);
        addView(label, lp);
        apply();
    }

    ToolTile style(int s) { style = s; apply(); return this; }

    ToolTile active(boolean a) { active = a; apply(); return this; }

    @Override public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        setAlpha(enabled ? 1f : 0.4f);
        apply();
    }

    /** Sinks the button slightly while a finger is on it, like a real key. */
    @Override public boolean onTouchEvent(MotionEvent e) {
        if (isEnabled()) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) { setTranslationY(dp(2)); setElevation(dp(1)); }
            else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) { setTranslationY(0); apply(); }
        }
        return super.onTouchEvent(e);
    }

    private void apply() {
        float r = dp(16);
        int fg;
        if (style == STYLE_PRIMARY) {
            fg = Theme.ON_PRIMARY;
            setBackground(Depth.raised(Theme.lighter(Theme.PRIMARY, 0.30f), Theme.PRIMARY, Theme.darker(Theme.PRIMARY, 0.30f), r, dp(3), true));
            setElevation(isEnabled() ? dp(5) : 0);
        } else if (active) {
            fg = Theme.HILITE;
            setBackground(Depth.pressed(Theme.PRESSED_TOP, Theme.PRESSED_BOTTOM, r));
            setElevation(0);
        } else {
            fg = style == STYLE_DANGER ? Theme.DANGER : Theme.TEXT;
            setBackground(Depth.raised(Theme.TILE_TOP, Theme.TILE_BOTTOM, Theme.TILE_LIP, r, dp(3), true));
            setElevation(isEnabled() ? dp(3) : 0);
        }
        icon.setImageTintList(ColorStateList.valueOf(fg));
        label.setTextColor(fg);
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
