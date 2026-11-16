package com.byso.labelsheet;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

/**
 * App colours. A scheme is four base colours (accent, bars, area around the page, text);
 * every other shade is derived from them so any combination stays readable.
 * The printed page itself is always white.
 */
final class Theme {
    private Theme() {}

    static final class Scheme {
        final String id, name;
        final int accent, bars, workspace, text;
        Scheme(String id, String name, int accent, int bars, int workspace, int text) {
            this.id = id; this.name = name; this.accent = accent; this.bars = bars; this.workspace = workspace; this.text = text;
        }
        Scheme with(int which, int color) {
            return new Scheme("custom", "Custom",
                    which == 0 ? color : accent, which == 1 ? color : bars,
                    which == 2 ? color : workspace, which == 3 ? color : text);
        }
    }

    static final Scheme[] PRESETS = {
            new Scheme("blue",      "LabelSheet Blue",  0xFF2563EB, 0xFFFFFFFF, 0xFFE3E9F1, 0xFF1E2A3B),
            new Scheme("teal",      "Ocean Teal",       0xFF0F8B8D, 0xFFFFFFFF, 0xFFDDEBEC, 0xFF15302F),
            new Scheme("forest",    "Forest Green",     0xFF2E7D32, 0xFFFFFFFF, 0xFFE2EBDF, 0xFF1D2B1E),
            new Scheme("sunset",    "Sunset Orange",    0xFFD9480F, 0xFFFFFDF9, 0xFFF1E6DC, 0xFF3A2618),
            new Scheme("purple",    "Royal Purple",     0xFF6D28D9, 0xFFFFFFFF, 0xFFE8E3F3, 0xFF261B3A),
            new Scheme("rose",      "Rose",             0xFFD6336C, 0xFFFFFFFF, 0xFFF2E3E8, 0xFF3A1A26),
            new Scheme("nord",      "Nord",             0xFF5E81AC, 0xFFECEFF4, 0xFFD8DEE9, 0xFF2E3440),
            new Scheme("solarized", "Solarized Light",  0xFF268BD2, 0xFFFDF6E3, 0xFFEEE8D5, 0xFF073642),
            new Scheme("midnight",  "Midnight (dark)",  0xFF60A5FA, 0xFF1E2533, 0xFF111722, 0xFFE6EAF0),
            new Scheme("dracula",   "Dracula (dark)",   0xFFBD93F9, 0xFF282A36, 0xFF1E1F29, 0xFFF8F8F2),
    };

    // Live colours used across the app (derived from the active scheme).
    static int PRIMARY, ON_PRIMARY, PRIMARY_SOFT, BACKGROUND, WORKSPACE, SURFACE, OUTLINE, TEXT, TEXT_SOFT,
            DANGER, SNACK, SNACK_ACTION, RIPPLE, BOARD, BOARD_EDGE, CAPTION;
    static boolean DARK;
    static Scheme current = PRESETS[0];

    static { apply(PRESETS[0]); }

    static void apply(Scheme s) {
        current = s;
        DARK = luminance(s.bars) < 0.35;
        PRIMARY = s.accent;
        ON_PRIMARY = contrast(Color.WHITE, s.accent) >= contrast(0xFF111318, s.accent) ? Color.WHITE : 0xFF111318;
        SURFACE = s.bars;
        WORKSPACE = s.workspace;
        TEXT = s.text;
        PRIMARY_SOFT = blend(s.bars, s.accent, DARK ? 0.30f : 0.16f);
        BACKGROUND = blend(s.bars, s.workspace, 0.35f);
        OUTLINE = blend(s.bars, s.text, DARK ? 0.18f : 0.12f);
        TEXT_SOFT = blend(s.text, s.bars, 0.38f);
        DANGER = DARK ? 0xFFFF7070 : 0xFFD12F2F;
        SNACK = DARK ? 0xFF3A4254 : 0xFF263244;
        SNACK_ACTION = blend(s.accent, Color.WHITE, 0.45f);
        RIPPLE = (s.text & 0x00FFFFFF) | 0x22000000;
        BOARD = blend(s.workspace, s.bars, 0.45f);
        BOARD_EDGE = blend(s.workspace, s.text, 0.22f);
        CAPTION = blend(s.workspace, s.text, 0.50f);
    }

    static Scheme find(String id) {
        for (Scheme s : PRESETS) if (s.id.equals(id)) return s;
        return null;
    }

    static Scheme customOrNull(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.contains("custom_accent")) return null;
        return new Scheme("custom", "Custom", p.getInt("custom_accent", PRESETS[0].accent), p.getInt("custom_bars", PRESETS[0].bars),
                p.getInt("custom_workspace", PRESETS[0].workspace), p.getInt("custom_text", PRESETS[0].text));
    }

    static void load(Context c) {
        String id = prefs(c).getString("scheme", "blue");
        Scheme s = "custom".equals(id) ? customOrNull(c) : find(id);
        apply(s != null ? s : PRESETS[0]);
    }

    static void save(Context c, Scheme s) {
        SharedPreferences.Editor e = prefs(c).edit().putString("scheme", s.id);
        if ("custom".equals(s.id)) {
            e.putInt("custom_accent", s.accent).putInt("custom_bars", s.bars)
             .putInt("custom_workspace", s.workspace).putInt("custom_text", s.text);
        }
        e.apply();
    }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("appearance", Context.MODE_PRIVATE); }

    static int blend(int a, int b, float t) {
        int r = Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t);
        int g = Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t);
        int bl = Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t);
        return Color.rgb(r, g, bl);
    }

    static double luminance(int c) {
        double r = ch(Color.red(c)), g = ch(Color.green(c)), b = ch(Color.blue(c));
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double ch(int v) { double s = v / 255.0; return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4); }

    static double contrast(int a, int b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }
}
