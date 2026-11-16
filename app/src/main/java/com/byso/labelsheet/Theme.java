package com.byso.labelsheet;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

/**
 * App colours. A scheme is four base colours (accent, top bar, area around the page, text);
 * every other shade is derived from them so any combination stays readable.
 * The printed page itself is always white.
 */
final class Theme {
    private Theme() {}

    static final class Scheme {
        final String id, name;
        final boolean bold;
        final int accent, bars, workspace, text;
        Scheme(String id, String name, boolean bold, int accent, int bars, int workspace, int text) {
            this.id = id; this.name = name; this.bold = bold;
            this.accent = accent; this.bars = bars; this.workspace = workspace; this.text = text;
        }
        Scheme with(int which, int color) {
            return new Scheme("custom", "Custom", false,
                    which == 0 ? color : accent, which == 1 ? color : bars,
                    which == 2 ? color : workspace, which == 3 ? color : text);
        }
    }

    static final Scheme[] PRESETS = {
            // Bold: strong coloured top bar + contrasting action colour.
            new Scheme("b_blue",    "Electric Blue",      true, 0xFFFACC15, 0xFF1D4ED8, 0xFFC9D6F6, 0xFF1E2A3B),
            new Scheme("b_crimson", "Crimson",            true, 0xFFFFFFFF, 0xFFB91C1C, 0xFFF0CFCF, 0xFF2A1414),
            new Scheme("b_emerald", "Emerald",            true, 0xFFFDE047, 0xFF047857, 0xFFC4E6D8, 0xFF12261E),
            new Scheme("b_tangerine","Tangerine",         true, 0xFF1F2937, 0xFFEA580C, 0xFFF6D7C3, 0xFF2B1A0E),
            new Scheme("b_violet",  "Ultra Violet",       true, 0xFFF472B6, 0xFF5B21B6, 0xFFD6CCF2, 0xFF1F1636),
            new Scheme("b_magenta", "Magenta",            true, 0xFFFDE68A, 0xFFBE185D, 0xFFF0CBDB, 0xFF2E1020),
            new Scheme("b_teal",    "Deep Teal",          true, 0xFFFB923C, 0xFF0F766E, 0xFFC2E3E0, 0xFF102A28),
            new Scheme("b_gold",    "Black & Gold",       true, 0xFFF59E0B, 0xFF111827, 0xFF2A3140, 0xFFE8ECF4),
            new Scheme("b_neon",    "Neon Night",         true, 0xFF22D3EE, 0xFF0B1020, 0xFF161D33, 0xFFE6EDF7),
            new Scheme("b_tt",      "Trinidad & Tobago",  true, 0xFF111111, 0xFFCE1126, 0xFFF2D3D7, 0xFF1A0A0C),
            // Soft
            new Scheme("blue",      "LabelSheet Blue",    false, 0xFF2563EB, 0xFFFFFFFF, 0xFFE3E9F1, 0xFF1E2A3B),
            new Scheme("teal",      "Ocean Teal",         false, 0xFF0F8B8D, 0xFFFFFFFF, 0xFFDDEBEC, 0xFF15302F),
            new Scheme("forest",    "Forest Green",       false, 0xFF2E7D32, 0xFFFFFFFF, 0xFFE2EBDF, 0xFF1D2B1E),
            new Scheme("sunset",    "Sunset Orange",      false, 0xFFD9480F, 0xFFFFFDF9, 0xFFF1E6DC, 0xFF3A2618),
            new Scheme("purple",    "Royal Purple",       false, 0xFF6D28D9, 0xFFFFFFFF, 0xFFE8E3F3, 0xFF261B3A),
            new Scheme("rose",      "Rose",               false, 0xFFD6336C, 0xFFFFFFFF, 0xFFF2E3E8, 0xFF3A1A26),
            new Scheme("nord",      "Nord",               false, 0xFF5E81AC, 0xFFECEFF4, 0xFFD8DEE9, 0xFF2E3440),
            new Scheme("solarized", "Solarized Light",    false, 0xFF268BD2, 0xFFFDF6E3, 0xFFEEE8D5, 0xFF073642),
            new Scheme("midnight",  "Midnight (dark)",    false, 0xFF60A5FA, 0xFF1E2533, 0xFF111722, 0xFFE6EAF0),
            new Scheme("dracula",   "Dracula (dark)",     false, 0xFFBD93F9, 0xFF282A36, 0xFF1E1F29, 0xFFF8F8F2),
    };

    // Live colours used across the app (derived from the active scheme).
    static int PRIMARY, ON_PRIMARY, PRIMARY_SOFT, BACKGROUND, WORKSPACE, SURFACE, OUTLINE, TEXT, TEXT_SOFT,
            DANGER, SNACK, SNACK_ACTION, RIPPLE, BOARD, BOARD_EDGE, CAPTION,
            BAR, ON_BAR, SELECT, HILITE, TILE_TOP, TILE_BOTTOM, TILE_LIP, PRESSED_TOP, PRESSED_BOTTOM;
    static boolean DARK, BAR_DARK;
    static Scheme current = PRESETS[10];

    static { apply(PRESETS[10]); }

    static void apply(Scheme s) {
        current = s;
        PRIMARY = s.accent;
        ON_PRIMARY = bestOn(s.accent);
        BAR = s.bars;
        ON_BAR = bestOn(s.bars);
        BAR_DARK = ON_BAR == Color.WHITE;
        WORKSPACE = s.workspace;
        TEXT = s.text;
        // Panels (tool rail / bottom bar) use the bar colour when text reads well on it,
        // otherwise a light (or dark) tint of the bar colour.
        if (contrast(s.text, s.bars) >= 4.5) SURFACE = s.bars;
        else SURFACE = luminance(s.text) < 0.3 ? blend(Color.WHITE, s.bars, 0.07f) : blend(0xFF0E1220, s.bars, 0.10f);
        DARK = luminance(SURFACE) < 0.35;
        // Selection/highlight colour must be visible on the white page and on panels.
        // SELECT: outlines/handles on the white page. HILITE: active tools and headings on the panels.
        SELECT = contrast(s.accent, Color.WHITE) >= 2.0 ? s.accent
                : (contrast(s.bars, Color.WHITE) >= 2.2 ? s.bars : 0xFF2563EB);
        HILITE = contrast(s.accent, SURFACE) >= 2.2 ? s.accent
                : (contrast(s.bars, SURFACE) >= 2.2 ? s.bars : TEXT);
        PRIMARY_SOFT = blend(SURFACE, HILITE, DARK ? 0.30f : 0.16f);
        BACKGROUND = blend(SURFACE, s.workspace, 0.35f);
        OUTLINE = blend(SURFACE, s.text, DARK ? 0.18f : 0.12f);
        TEXT_SOFT = blend(s.text, SURFACE, 0.38f);
        DANGER = DARK ? 0xFFFF7070 : 0xFFC81E1E;
        SNACK = DARK ? 0xFF3A4254 : 0xFF263244;
        SNACK_ACTION = contrast(s.accent, SNACK) > 3 ? s.accent : blend(HILITE, Color.WHITE, 0.45f);
        RIPPLE = (s.text & 0x00FFFFFF) | 0x22000000;
        BOARD = DARK ? blend(s.workspace, Color.BLACK, 0.25f) : blend(s.workspace, Color.WHITE, 0.35f);
        BOARD_EDGE = blend(s.workspace, s.text, 0.22f);
        CAPTION = DARK ? blend(s.workspace, HILITE, 0.85f) : blend(s.workspace, SELECT, 0.80f);
        // 3D tool buttons: light top, tinted bottom, darker "lip" underneath.
        TILE_TOP = DARK ? blend(SURFACE, Color.WHITE, 0.10f) : Color.WHITE;
        TILE_BOTTOM = DARK ? SURFACE : blend(Color.WHITE, s.bars == Color.WHITE ? s.workspace : s.bars, 0.10f);
        TILE_LIP = DARK ? blend(SURFACE, Color.BLACK, 0.45f) : blend(TILE_BOTTOM, s.text, 0.14f);
        PRESSED_TOP = DARK ? blend(SURFACE, Color.BLACK, 0.40f) : blend(TILE_BOTTOM, s.text, 0.12f);
        PRESSED_BOTTOM = DARK ? blend(SURFACE, HILITE, 0.18f) : blend(TILE_BOTTOM, HILITE, 0.14f);
    }

    static int bestOn(int bg) { return contrast(Color.WHITE, bg) >= contrast(0xFF111318, bg) ? Color.WHITE : 0xFF111318; }

    static int lighter(int c, float t) { return blend(c, Color.WHITE, t); }
    static int darker(int c, float t) { return blend(c, Color.BLACK, t); }

    static Scheme find(String id) {
        for (Scheme s : PRESETS) if (s.id.equals(id)) return s;
        return null;
    }

    static Scheme customOrNull(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.contains("custom_accent")) return null;
        Scheme d = find("blue");
        return new Scheme("custom", "Custom", false, p.getInt("custom_accent", d.accent), p.getInt("custom_bars", d.bars),
                p.getInt("custom_workspace", d.workspace), p.getInt("custom_text", d.text));
    }

    static void load(Context c) {
        String id = prefs(c).getString("scheme", "blue");
        Scheme s = "custom".equals(id) ? customOrNull(c) : find(id);
        apply(s != null ? s : find("blue"));
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
