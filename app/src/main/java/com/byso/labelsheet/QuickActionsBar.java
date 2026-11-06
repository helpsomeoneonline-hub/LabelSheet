package com.byso.labelsheet;

import android.content.ClipData;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Editable favorites tray right beside the page. All preferences are local. */
final class QuickActionsBar extends LinearLayout {
    private final MainActivity activity;
    private final LinkedHashMap<String, Button> sources;
    private final Runnable print;
    private final Runnable borderAdjust;
    private final LinearLayout chips;
    private final Button edit;
    private boolean arranging = false;

    QuickActionsBar(MainActivity activity, LinkedHashMap<String, Button> sources,
                    Runnable print, Runnable borderAdjust) {
        super(activity);
        this.activity = activity;
        this.sources = sources;
        this.print = print;
        this.borderAdjust = borderAdjust;
        setGravity(Gravity.CENTER_VERTICAL);
        setOrientation(HORIZONTAL);
        setBackgroundColor(0xFFE5EDFB);
        setPadding(dp(5), dp(3), dp(5), dp(3));

        edit = button("Edit", false);
        edit.setOnClickListener(v -> setArranging(!arranging));
        addView(edit, new LayoutParams(dp(58), dp(44)));

        HorizontalScrollView scroll = new HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(false);
        chips = new LinearLayout(activity);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        chips.setOrientation(HORIZONTAL);
        chips.setOnDragListener(dropOn(null));
        scroll.addView(chips);
        addView(scroll, new LayoutParams(0, dp(47), 1f));

        for (Map.Entry<String, Button> e : sources.entrySet()) {
            final String name = e.getKey();
            Button original = e.getValue();
            original.setOnLongClickListener(v -> {
                if (arranging) { startDrag(original, name); return true; }
                if ("Border".equals(name)) { borderAdjust.run(); return true; }
                return false;
            });
        }
        refresh();
    }

    void setArranging(boolean value) {
        arranging = value;
        edit.setText(value ? "Done" : "Edit");
        Toast.makeText(activity,
                value ? "Long-press a tool and drag it here; drag chips to reorder."
                      : "Quick tools saved.", Toast.LENGTH_LONG).show();
    }

    void refresh() {
        chips.removeAllViews();
        for (String action : preferredActions()) {
            Button chip = button("+ Add".equals(action) ? "＋ Add" : action,
                    "+ Add".equals(action) || "Print".equals(action));
            chip.setOnClickListener(v -> {
                UsageInsights.record(activity, action);
                if ("Print".equals(action)) print.run();
                else {
                    Button source = sources.get(action);
                    if (source != null && source.isEnabled()) source.performClick();
                    else Toast.makeText(activity, "Select a label to use " + action, Toast.LENGTH_SHORT).show();
                }
            });
            chip.setOnLongClickListener(v -> {
                if (!arranging) return false;
                startDrag(chip, action);
                return true;
            });
            chip.setOnDragListener(dropOn(action));
            LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, dp(42));
            lp.setMargins(dp(3), 0, dp(3), 0);
            chips.addView(chip, lp);
        }
    }

    private List<String> preferredActions() {
        SharedPreferences p = activity.getSharedPreferences("editor", Context.MODE_PRIVATE);
        LinkedHashSet<String> actions = new LinkedHashSet<>();
        for (String n : p.getString("quick_actions", "+ Add,Crop,Border,Print").split(",")) {
            if ("Print".equals(n) || sources.containsKey(n)) actions.add(n);
        }
        if (!p.getBoolean("quick_manual", false) && p.getBoolean("smart_tools", true)
                && UsageInsights.total(activity) >= 20) {
            for (String n : UsageInsights.ranking(activity)) {
                if (("Print".equals(n) || sources.containsKey(n)) && actions.size() < 6) actions.add(n);
            }
        }
        if (actions.isEmpty()) actions.add("+ Add");
        return new ArrayList<>(actions);
    }

    private void pin(String name, String before) {
        if (!"Print".equals(name) && !sources.containsKey(name)) return;
        List<String> items = preferredActions();
        items.remove(name);
        int index = before == null ? items.size() : items.indexOf(before);
        if (index < 0) index = items.size();
        items.add(index, name);
        while (items.size() > 6) items.remove(items.size()-1);
        activity.getSharedPreferences("editor", Context.MODE_PRIVATE).edit()
                .putString("quick_actions", android.text.TextUtils.join(",", items))
                .putBoolean("quick_manual", true).apply();
        Diagnostics.log("SHORTCUT_MOVED", "tool=" + name);
        refresh();
    }

    private OnDragListener dropOn(String before) {
        return (v, e) -> {
            switch (e.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED: return arranging && e.getClipDescription() != null;
                case DragEvent.ACTION_DROP:
                    if (!arranging || e.getClipData() == null) return false;
                    CharSequence label = e.getClipData().getItemAt(0).getText();
                    if (label == null) return false;
                    pin(label.toString(), before);
                    return true;
                default: return true;
            }
        };
    }

    private void startDrag(View v, String name) {
        ClipData data = ClipData.newPlainText("labelsheet-tool", name);
        if (Build.VERSION.SDK_INT >= 24) v.startDragAndDrop(data, new View.DragShadowBuilder(v), null, 0);
        else v.startDrag(data, new View.DragShadowBuilder(v), null, 0);
    }

    private Button button(String label, boolean primary) {
        Button b = new Button(activity);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setMinHeight(0); b.setMinimumHeight(0);
        b.setPadding(dp(10), 0, dp(10), 0);
        b.setTextColor(primary ? Color.WHITE : 0xFF243653);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(primary ? 0xFF2563EB : Color.WHITE);
        bg.setStroke(dp(1), primary ? 0xFF2563EB : 0xFFCBD7E8);
        bg.setCornerRadius(dp(12));
        b.setBackground(bg);
        return b;
    }

    private int dp(int amount) {
        return Math.round(amount * getResources().getDisplayMetrics().density);
    }
}
