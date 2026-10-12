#!/usr/bin/env python3
"""Build-time source patch for LabelSheet 1.7, applied after the stable 1.6 patch.
Bail out instead of silently modifying an unexpected version.
"""
from pathlib import Path

src = Path("lsbuild/app/src/main/java/com/byso/labelsheet")
canvas = src / "LetterCanvasView.java"
main = src / "MainActivity.java"
gradle = Path("lsbuild/app/build.gradle")

def once(text, before, after, label):
    if text.count(before) != 1:
        raise AssertionError(f"{label}: expected 1 anchor, saw {text.count(before)}")
    return text.replace(before, after, 1)

c = canvas.read_text()
new_methods = r'''
    // Multi-picture edits are a single action. The caller takes one undo snapshot.
    // Locked pictures are never modified. Each item keeps its original position.
    public int countEditableSelected() {
        int total = 0;
        java.util.Set<Integer> targets = selectedSet.isEmpty()
                ? java.util.Collections.singleton(selected) : new LinkedHashSet<>(selectedSet);
        for (int i : targets) {
            if (i >= 0 && i < items.size() && !items.get(i).locked) total++;
        }
        return total;
    }

    public int applyBulkBorder(float points) {
        int changed = 0;
        java.util.Set<Integer> targets = selectedSet.isEmpty()
                ? java.util.Collections.singleton(selected) : new LinkedHashSet<>(selectedSet);
        for (int i : targets) {
            if (i >= 0 && i < items.size() && !items.get(i).locked) {
                items.get(i).borderPt = clamp(points, 0f, 24f);
                changed++;
            }
        }
        if (changed > 0) {
            Diagnostics.log("BULK_BORDER", "count=" + changed + ", pt=" + points);
            invalidate();
            if (listener != null) listener.onSheetChanged();
        }
        return changed;
    }

    public int applyBulkRotation90() {
        int changed = 0;
        java.util.Set<Integer> targets = selectedSet.isEmpty()
                ? java.util.Collections.singleton(selected) : new LinkedHashSet<>(selectedSet);
        for (int i : targets) {
            if (i < 0 || i >= items.size()) continue;
            SheetItem item = items.get(i);
            if (item.locked) continue;
            item.rotation = (item.rotation + 90f) % 360f;
            float formerW = item.w;
            item.w = clamp(item.h * (portrait ? (11f / 8.5f) : (8.5f / 11f)), 0.05f, 0.95f);
            item.h = clamp(formerW * (portrait ? (8.5f / 11f) : (11f / 8.5f)), 0.035f, 0.95f);
            item.x = clamp(item.x, 0f, 1f - item.w);
            item.y = clamp(item.y, 0f, 1f - item.h);
            changed++;
        }
        if (changed > 0) {
            Diagnostics.log("BULK_ROTATE", "count=" + changed);
            invalidate();
            if (listener != null) listener.onSheetChanged();
        }
        return changed;
    }

    public int applyBulkSizeInches(float widthIn, float heightIn) {
        if (!Float.isFinite(widthIn) || !Float.isFinite(heightIn)
                || widthIn <= 0 || heightIn <= 0) return 0;
        float pageW = portrait ? 8.5f : 11f, pageH = portrait ? 11f : 8.5f;
        if (widthIn > pageW || heightIn > pageH) return 0;
        float newW = clamp(widthIn / pageW, 0.04f, 0.95f);
        float newH = clamp(heightIn / pageH, 0.03f, 0.95f);
        int changed = 0;
        java.util.Set<Integer> targets = selectedSet.isEmpty()
                ? java.util.Collections.singleton(selected) : new LinkedHashSet<>(selectedSet);
        for (int i : targets) {
            if (i < 0 || i >= items.size()) continue;
            SheetItem item = items.get(i);
            if (item.locked) continue;
            float cx = item.x + item.w / 2f, cy = item.y + item.h / 2f;
            item.w = newW; item.h = newH;
            item.x = clamp(cx - newW / 2f, 0f, 1f - newW);
            item.y = clamp(cy - newH / 2f, 0f, 1f - newH);
            changed++;
        }
        if (changed > 0) {
            Diagnostics.log("BULK_SIZE", "count=" + changed);
            invalidate();
            if (listener != null) listener.onSheetChanged();
        }
        return changed;
    }

    public int applyBulkFormatJson(String json) {
        if (json == null) return 0;
        try {
            org.json.JSONObject data = new org.json.JSONObject(json);
            float w = clamp((float) data.getDouble("w"), 0.04f, 0.95f);
            float h = clamp((float) data.getDouble("h"), 0.03f, 0.95f);
            float rot = (float) data.getDouble("rotation");
            float border = clamp((float) data.optDouble("borderPt", 0), 0f, 24f);
            float cl = clamp((float) data.optDouble("cropLeft", 0), 0f, 0.45f);
            float ct = clamp((float) data.optDouble("cropTop", 0), 0f, 0.45f);
            float cr = clamp((float) data.optDouble("cropRight", 0), 0f, 0.45f);
            float cb = clamp((float) data.optDouble("cropBottom", 0), 0f, 0.45f);
            if (!Float.isFinite(rot)) return 0;
            cr = Math.min(cr, 0.85f - cl);
            cb = Math.min(cb, 0.85f - ct);
            int changed = 0;
            java.util.Set<Integer> targets = selectedSet.isEmpty()
                    ? java.util.Collections.singleton(selected) : new LinkedHashSet<>(selectedSet);
            for (int i : targets) {
                if (i < 0 || i >= items.size()) continue;
                SheetItem item = items.get(i);
                if (item.locked) continue;
                float cx = item.x + item.w / 2f, cy = item.y + item.h / 2f;
                item.w = w; item.h = h;
                item.x = clamp(cx - w / 2f, 0f, 1f - w);
                item.y = clamp(cy - h / 2f, 0f, 1f - h);
                item.rotation = rot;
                item.borderPt = border;
                item.cropLeft = cl; item.cropTop = ct;
                item.cropRight = cr; item.cropBottom = cb;
                changed++;
            }
            if (changed > 0) {
                Diagnostics.log("BULK_FORMAT", "count=" + changed);
                invalidate();
                if (listener != null) listener.onSheetChanged();
            }
            return changed;
        } catch (Exception e) {
            Diagnostics.error("BULK_FORMAT_FAILED", e);
            return 0;
        }
    }

'''
c = once(c,
'''    public boolean setSelectedSizeInches(float widthIn, float heightIn) {''',
new_methods + '''    public boolean setSelectedSizeInches(float widthIn, float heightIn) {''',
"bulk canvas API")
canvas.write_text(c)

m = main.read_text()
m = once(m, 'private Button arrangeButton, pageButton, moreButton, copyFormatButton, pasteFormatButton, lockButton, multiButton, layoutButton, zoomPercentButton;',
'private Button arrangeButton, pageButton, moreButton, copyFormatButton, pasteFormatButton, lockButton, multiButton, layoutButton, zoomPercentButton, bulkButton;',
"bulk field")

m = once(m, '''        moreButton = makeCompactButton("More", false);
        moreButton.setOnClickListener(this::showMoreMenu);''',
'''        moreButton = makeCompactButton("More", false);
        moreButton.setOnClickListener(this::showMoreMenu);
        bulkButton = makeCompactButton("Edit All", true);
        bulkButton.setOnClickListener(v -> showBulkTools());''', "bulk button")

m = once(m, '''addToRow(row3, copyFormatButton, pasteFormatButton, lockButton, multiButton, layoutButton);''',
'''addToRow(row3, copyFormatButton, pasteFormatButton, lockButton, multiButton, layoutButton, bulkButton);''',
"portrait bulk button")

m = once(m,
'''        left.addView(makeZoomRow(), rowParams(34));
        root.addView(left, new LinearLayout.LayoutParams(dp(224), LinearLayout.LayoutParams.MATCH_PARENT));''',
'''        left.addView(makeZoomRow(), rowParams(48));
        left.addView(bulkButton, rowParams(48));
        // Short landscape phones need a scrollable tool rail. Keep the canvas on the right.
        android.widget.ScrollView rail = new android.widget.ScrollView(this);
        rail.setFillViewport(false);
        rail.setVerticalScrollBarEnabled(true);
        rail.addView(left);
        root.addView(rail, new LinearLayout.LayoutParams(dp(224), LinearLayout.LayoutParams.MATCH_PARENT));''',
"scroll rail")

m = once(m, '''parent.addView(row, rowParams(33));''',
'''parent.addView(row, rowParams(48));''',"landscape tap targets")
m = once(m, '''left.addView(printRow, rowParams(34));''',
'''left.addView(printRow, rowParams(48));''',"landscape print targets")
m = once(m, '''        b.setTextSize(10);
        b.setAllCaps(false);''',
'''        b.setTextSize(12);
        b.setAllCaps(false);''', "font")
m = once(m,'''row1, rowParams(42));''','''row1, rowParams(48));''',"portrait row1")
m = once(m,'''row2, rowParams(42));''','''row2, rowParams(48));''',"portrait row2")
m = once(m,'''row3, rowParams(42));''','''row3, rowParams(48));''',"portrait row3")

bulk_ui = r'''
    private void performBulkEdit(java.util.function.IntSupplier operation, String name) {
        int available = canvasView.countEditableSelected();
        if (available < 1) {
            showToast("Select one or more unlocked pictures first.");
            return;
        }
        pushUndo();
        int changed = operation.getAsInt();
        if (changed > 0) {
            redo.clear();
            showToast(name + " applied to " + changed + " picture(s). Undo reverses all.");
        } else {
            undo.pollFirst();
            showToast("No pictures changed.");
        }
        updateControls();
    }

    private void showBulkTools() {
        int count = canvasView.getSelectionCount();
        if (count == 0) {
            showToast("Tap Multi, then select pictures to edit.");
            return;
        }
        final String[] actions = new String[] {
                "Apply 2 pt black border",
                "Remove borders",
                "Rotate 90 degrees",
                "Set the same printed size",
                "Paste copied format"
        };
        new AlertDialog.Builder(this)
                .setTitle("Edit " + count + " selected picture(s)")
                .setMessage("Changes apply only to the selected unlocked pictures. Their positions stay separate. Undo reverses the whole change.")
                .setItems(actions, (d, index) -> {
                    switch (index) {
                        case 0: performBulkEdit(() -> canvasView.applyBulkBorder(2f), "Border"); break;
                        case 1: performBulkEdit(() -> canvasView.applyBulkBorder(0f), "Remove border"); break;
                        case 2: performBulkEdit(() -> canvasView.applyBulkRotation90(), "Rotation"); break;
                        case 3: showExactSizeDialog(); break;
                        case 4:
                            if (copiedFormatJson == null) showToast("Copy a picture's format first.");
                            else performBulkEdit(() -> canvasView.applyBulkFormatJson(copiedFormatJson), "Format");
                            break;
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

'''
m=once(m, '''    private void showLayoutTools() {''', bulk_ui + '''    private void showLayoutTools() {''',"bulk dialog method")
m=once(m,'''if(canvasView.setSelectedSizeInches(wi,hi)) redo.clear(); else undo.pollFirst();''',
'''if(canvasView.applyBulkSizeInches(wi,hi) > 0) redo.clear(); else { undo.pollFirst(); showToast("Enter a size that fits the sheet."); }''',
"bulk size")
m=once(m,'''        setEnabledStyled(layoutButton, canvasView.getItemCount() > 0);''',
'''        setEnabledStyled(bulkButton, canvasView.getSelectionCount() > 0);
        setEnabledStyled(layoutButton, canvasView.getItemCount() > 0);''',"bulk controls")
main.write_text(m)

g=gradle.read_text()
g=once(g,"versionCode 7", "versionCode 8","versionCode")
g=once(g,"versionName '1.6.0'", "versionName '1.7.0'","versionName")
gradle.write_text(g)
print("LabelSheet 1.7: bulk format/border/rotate/size, editable scroll rail, larger tool targets")
