package com.byso.labelsheet;

import android.content.Context;
import android.content.SharedPreferences;

final class SheetStore {
    private static final String PREF = "labelsheet_state";
    private static final String KEY_SHEET = "sheet_json";

    static void save(Context c, String json) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY_SHEET, json).apply();
    }

    static String load(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        return p.getString(KEY_SHEET, null);
    }

    static void clear(Context c) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY_SHEET).apply();
    }
}
