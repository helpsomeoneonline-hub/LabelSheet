package com.byso.labelsheet;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Private, on-device tool-frequency diagnostics. Never records labels, images, or printer addresses. */
final class UsageInsights {
    private static final String PREFS = "labelsheet_usage_v1";
    private static final String PREFIX = "count:";
    private UsageInsights() {}

    static void record(Context context, String name) {
        if (name == null || name.trim().isEmpty()) return;
        // Only stable tool names are stored; no user text or document contents.
        String action = name.trim();
        if (action.length() > 45) action = action.substring(0, 45);
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int count = prefs.getInt(PREFIX + action, 0);
        if (count < 1000000000) count++;
        prefs.edit().putInt(PREFIX + action, count)
                .putLong("last:" + action, System.currentTimeMillis()).apply();
        Diagnostics.log("TOOL_TAPPED", "action=" + action + ", count=" + count);
    }

    static int count(Context context, String name) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(PREFIX + name, 0);
    }

    static int total(Context context) {
        int total = 0;
        for (Map.Entry<String, ?> item : context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getAll().entrySet()) {
            if (item.getKey().startsWith(PREFIX) && item.getValue() instanceof Integer)
                total += (Integer)item.getValue();
        }
        return total;
    }

    static List<String> ranking(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        List<String> actions = new ArrayList<>();
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(PREFIX)) actions.add(key.substring(PREFIX.length()));
        }
        Collections.sort(actions, (a, b) -> {
            int d = Integer.compare(count(context, b), count(context, a));
            return d != 0 ? d : a.compareToIgnoreCase(b);
        });
        return actions;
    }

    static String report(Context context) {
        StringBuilder out = new StringBuilder();
        out.append("On-device usage report\nTotal recorded taps: ").append(total(context))
                .append("\n\nMost used actions:\n");
        List<String> actions = ranking(context);
        if (actions.isEmpty()) out.append("Start using tools to collect counts.\n");
        for (String action : actions) out.append("• ").append(action)
                .append(": ").append(count(context, action)).append("\n");
        out.append("\nNo label text, photos, or printer IPs are collected by this feature. ")
                .append("Counts remain on this device unless you copy or export them.");
        return out.toString();
    }

    static void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
        Diagnostics.log("USAGE_COUNTS_RESET", "user requested");
    }
}
