package com.byso.labelsheet;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Icon-over-label tool button used by the bottom bar (phones) and the side rail (tablets / landscape). */
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
        setPadding(dp(4), dp(6), dp(4), dp(6));
        setMinimumHeight(dp(56));
        setMinimumWidth(dp(56));

        icon = new ImageView(c);
        icon.setImageResource(iconRes);
        icon.setScaleType(ImageView.ScaleType.CENTER);
        LayoutParams ip = new LayoutParams(dp(40), dp(30));
        addView(icon, ip);

        label = new TextView(c);
        label.setText(text);
        label.setTextSize(11.5f);
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(2);
        addView(label, lp);
        apply();
    }

    ToolTile style(int s) { style = s; apply(); return this; }

    ToolTile active(boolean a) { active = a; apply(); return this; }

    @Override public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        setAlpha(enabled ? 1f : 0.38f);
    }

    private void apply() {
        int fg;
        GradientDrawable wellBg = new GradientDrawable();
        wellBg.setCornerRadius(dp(15));
        if (style == STYLE_PRIMARY) {
            fg = Theme.PRIMARY;
            wellBg.setColor(Theme.PRIMARY);
            icon.setImageTintList(ColorStateList.valueOf(Theme.ON_PRIMARY));
        } else if (active) {
            fg = Theme.PRIMARY;
            wellBg.setColor(Theme.PRIMARY_SOFT);
            icon.setImageTintList(ColorStateList.valueOf(Theme.PRIMARY));
        } else {
            fg = style == STYLE_DANGER ? Theme.DANGER : Theme.TEXT;
            wellBg.setColor(0x00000000);
            icon.setImageTintList(ColorStateList.valueOf(fg));
        }
        icon.setBackground(wellBg);
        label.setTextColor(style == STYLE_PRIMARY ? Theme.PRIMARY : fg);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(dp(14));
        setBackground(new RippleDrawable(ColorStateList.valueOf(Theme.RIPPLE), null, mask));
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
