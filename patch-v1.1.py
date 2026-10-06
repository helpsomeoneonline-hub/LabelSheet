from pathlib import Path

root = Path("lsbuild")
main = root / "app/src/main/java/com/byso/labelsheet/MainActivity.java"
canvas = root / "app/src/main/java/com/byso/labelsheet/LetterCanvasView.java"
item = root / "app/src/main/java/com/byso/labelsheet/SheetItem.java"
manifest = root / "app/src/main/AndroidManifest.xml"
gradle = root / "app/build.gradle"

# Version
g = gradle.read_text()
g = g.replace("versionCode 1", "versionCode 2")
g = g.replace("versionName '1.0.0'", "versionName '1.1.0'")
gradle.write_text(g)

# Item state: border + crop
item.write_text(r'''package com.byso.labelsheet;

import android.graphics.Bitmap;

import org.json.JSONException;
import org.json.JSONObject;

final class SheetItem {
    String uri;
    float x;
    float y;
    float w;
    float h;
    float rotation;
    float borderPt;
    float cropLeft;
    float cropTop;
    float cropRight;
    float cropBottom;
    transient Bitmap bitmap;

    SheetItem(String uri, float x, float y, float w, float h) {
        this.uri = uri;
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.rotation = 0f;
        this.borderPt = 0f;
        this.cropLeft = 0f;
        this.cropTop = 0f;
        this.cropRight = 0f;
        this.cropBottom = 0f;
    }

    SheetItem copy() {
        SheetItem c = new SheetItem(uri, x, y, w, h);
        c.rotation = rotation;
        c.borderPt = borderPt;
        c.cropLeft = cropLeft;
        c.cropTop = cropTop;
        c.cropRight = cropRight;
        c.cropBottom = cropBottom;
        c.bitmap = bitmap;
        return c;
    }

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("uri", uri);
        o.put("x", x);
        o.put("y", y);
        o.put("w", w);
        o.put("h", h);
        o.put("rotation", rotation);
        o.put("borderPt", borderPt);
        o.put("cropLeft", cropLeft);
        o.put("cropTop", cropTop);
        o.put("cropRight", cropRight);
        o.put("cropBottom", cropBottom);
        return o;
    }

    static SheetItem fromJson(JSONObject o) throws JSONException {
        SheetItem item = new SheetItem(
                o.getString("uri"),
                (float) o.getDouble("x"),
                (float) o.getDouble("y"),
                (float) o.getDouble("w"),
                (float) o.getDouble("h"));
        item.rotation = (float) o.optDouble("rotation", 0.0);
        item.borderPt = (float) o.optDouble("borderPt", 0.0);
        item.cropLeft = (float) o.optDouble("cropLeft", 0.0);
        item.cropTop = (float) o.optDouble("cropTop", 0.0);
        item.cropRight = (float) o.optDouble("cropRight", 0.0);
        item.cropBottom = (float) o.optDouble("cropBottom", 0.0);
        return item;
    }
}
''')

# Canvas editing + border rendering
s = canvas.read_text()
s = s.replace(
"    public void autoArrange() {",
'''    public float getSelectedBorderPt() {
        return hasSelection() ? items.get(selected).borderPt : 0f;
    }

    public boolean setSelectedBorderPt(float points) {
        if (!hasSelection()) return false;
        SheetItem item = items.get(selected);
        item.borderPt = clamp(points, 0f, 24f);
        Diagnostics.log("BORDER_CHANGED", "points=" + item.borderPt);
        invalidate();
        if (listener != null) listener.onSheetChanged();
        return true;
    }

    public float[] getSelectedCrop() {
        if (!hasSelection()) return new float[]{0f, 0f, 0f, 0f};
        SheetItem item = items.get(selected);
        return new float[]{item.cropLeft, item.cropTop, item.cropRight, item.cropBottom};
    }

    public boolean setSelectedCrop(float left, float top, float right, float bottom) {
        if (!hasSelection()) return false;
        SheetItem item = items.get(selected);
        item.cropLeft = clamp(left, 0f, 0.45f);
        item.cropTop = clamp(top, 0f, 0.45f);
        item.cropRight = clamp(right, 0f, 0.45f);
        item.cropBottom = clamp(bottom, 0f, 0.45f);
        if (item.cropLeft + item.cropRight > 0.85f) item.cropRight = 0.85f - item.cropLeft;
        if (item.cropTop + item.cropBottom > 0.85f) item.cropBottom = 0.85f - item.cropTop;
        Diagnostics.log("CROP_CHANGED", String.format(java.util.Locale.US,
                "l=%.2f,t=%.2f,r=%.2f,b=%.2f", item.cropLeft, item.cropTop, item.cropRight, item.cropBottom));
        invalidate();
        if (listener != null) listener.onSheetChanged();
        return true;
    }

    public void autoArrange() {''')

old_draw = '''        Matrix m = new Matrix();
        RectF src = new RectF(0, 0, item.bitmap.getWidth(), item.bitmap.getHeight());
        m.setRectToRect(src, dst, Matrix.ScaleToFit.CENTER);
        canvas.drawBitmap(item.bitmap, m, paint);
        if (selectedState) {'''
new_draw = '''        Matrix m = new Matrix();
        float bw = item.bitmap.getWidth();
        float bh = item.bitmap.getHeight();
        RectF src = new RectF(
                bw * item.cropLeft,
                bh * item.cropTop,
                bw * (1f - item.cropRight),
                bh * (1f - item.cropBottom));
        m.setRectToRect(src, dst, Matrix.ScaleToFit.FILL);
        canvas.save();
        canvas.clipRect(dst);
        canvas.drawBitmap(item.bitmap, m, paint);
        canvas.restore();

        if (item.borderPt > 0.01f) {
            float pageWidthIn = portrait ? 8.5f : 11f;
            float pxPerPoint = page.width() / (pageWidthIn * 72f);
            Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
            border.setStyle(Paint.Style.STROKE);
            border.setColor(Color.BLACK);
            border.setStrokeWidth(Math.max(1f, item.borderPt * pxPerPoint));
            float inset = border.getStrokeWidth() / 2f;
            RectF borderRect = new RectF(dst.left + inset, dst.top + inset, dst.right - inset, dst.bottom - inset);
            canvas.drawRect(borderRect, border);
        }
        if (selectedState) {'''
assert old_draw in s
s = s.replace(old_draw, new_draw)
canvas.write_text(s)

# Main UI + crop/border + multi picker + printer wake
s = main.read_text()
s = s.replace("import android.content.Intent;", "import android.content.Intent;\nimport android.content.SharedPreferences;")
s = s.replace("import android.net.Uri;", "import android.net.Uri;\nimport android.os.Build;")
s = s.replace("import android.provider.Settings;", "import android.provider.Settings;\nimport android.provider.MediaStore;")
s = s.replace("import android.widget.HorizontalScrollView;", "import android.widget.HorizontalScrollView;\nimport android.widget.EditText;\nimport android.widget.SeekBar;")
s = s.replace("import java.io.InputStream;", "import java.io.InputStream;\nimport java.net.InetSocketAddress;\nimport java.net.Socket;")
s = s.replace(
"private Button replaceButton, duplicateButton, deleteButton, rotateButton, undoButton, redoButton;",
"private Button replaceButton, duplicateButton, deleteButton, rotateButton, undoButton, redoButton, cropButton, borderButton;")

s = s.replace(
'''        Button print = makeButton("Print", true);
        print.setOnClickListener(v -> printSheet());
        titleBar.addView(print);''',
'''        Button wake = makeButton("Wake", false);
        wake.setOnClickListener(v -> wakePrinter());
        wake.setOnLongClickListener(v -> { showPrinterAddressDialog(false); return true; });
        titleBar.addView(wake);

        Button print = makeButton("Print", true);
        print.setOnClickListener(v -> printSheet());
        titleBar.addView(print);''')

start = s.index("        HorizontalScrollView scroll = new HorizontalScrollView(this);")
end = s.index("        TextView hint = new TextView(this);", start)
toolbar = '''        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setPadding(dp(8), dp(3), dp(8), dp(2));

        Button add = makeCompactButton("+ Add", true);
        add.setOnClickListener(v -> chooseImage(false));
        row1.addView(add);

        cropButton = makeCompactButton("Crop", false);
        cropButton.setOnClickListener(v -> showCropDialog());
        row1.addView(cropButton);

        borderButton = makeCompactButton("Border", false);
        borderButton.setOnClickListener(v -> showBorderDialog());
        row1.addView(borderButton);

        rotateButton = makeCompactButton("Rotate", false);
        rotateButton.setOnClickListener(v -> {
            pushUndo();
            if (canvasView.rotateSelected()) redo.clear();
            updateControls();
        });
        row1.addView(rotateButton);

        duplicateButton = makeCompactButton("Copy", false);
        duplicateButton.setOnClickListener(v -> {
            pushUndo();
            if (canvasView.duplicateSelected()) redo.clear();
            updateControls();
        });
        row1.addView(duplicateButton);

        deleteButton = makeCompactButton("Delete", false);
        deleteButton.setOnClickListener(v -> {
            pushUndo();
            if (canvasView.deleteSelected()) redo.clear();
            updateControls();
        });
        row1.addView(deleteButton);
        root.addView(row1, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(dp(8), dp(2), dp(8), dp(5));

        replaceButton = makeCompactButton("Replace", false);
        replaceButton.setOnClickListener(v -> chooseImage(true));
        row2.addView(replaceButton);

        undoButton = makeCompactButton("Undo", false);
        undoButton.setOnClickListener(v -> undo());
        row2.addView(undoButton);

        redoButton = makeCompactButton("Redo", false);
        redoButton.setOnClickListener(v -> redo());
        row2.addView(redoButton);

        Button arrange = makeCompactButton("Arrange", false);
        arrange.setOnClickListener(v -> {
            if (canvasView.getItemCount() > 0) {
                pushUndo();
                canvasView.autoArrange();
                redo.clear();
                updateControls();
            }
        });
        row2.addView(arrange);

        Button page = makeCompactButton("Page ↻", false);
        page.setOnClickListener(v -> {
            pushUndo();
            canvasView.toggleOrientation();
            redo.clear();
            updateStatus();
        });
        row2.addView(page);

        Button more = makeCompactButton("More", false);
        more.setOnClickListener(this::showMoreMenu);
        row2.addView(more);
        root.addView(row2, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));

'''
s = s[:start] + toolbar + s[end:]
s = s.replace(
'hint.setText("Tap a label to select • drag to move • pinch with two fingers to resize");',
'hint.setText("Select one or many photos • tap a label • drag to move • pinch to resize");')
s = s.replace('        menu.getMenu().add("Auto Arrange Labels");\n', '')
s = s.replace(
'''            if (t.startsWith("Auto Arrange")) {
                if (canvasView.getItemCount() > 0) {
                    pushUndo();
                    canvasView.autoArrange();
                    redo.clear();
                    updateControls();
                }
            } else if (t.startsWith("Export Letter")) exportPdf();''',
'''            if (t.startsWith("Export Letter")) exportPdf();''')

old_choose = '''    private void chooseImage(boolean replace) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        if (!replace) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, replace ? REQ_REPLACE : REQ_ADD);
    }'''
new_choose = '''    private void chooseImage(boolean replace) {
        Intent i;
        if (!replace && Build.VERSION.SDK_INT >= 33) {
            i = new Intent(MediaStore.ACTION_PICK_IMAGES);
            i.setType("image/*");
            i.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, Math.min(50, MediaStore.getPickImagesMaxLimit()));
        } else {
            i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            if (!replace) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        }
        startActivityForResult(i, replace ? REQ_REPLACE : REQ_ADD);
    }'''
assert old_choose in s
s = s.replace(old_choose, new_choose)
s = s.replace('.setTitle("LabelSheet 1.0.0")', '.setTitle("LabelSheet 1.1.0")')
s = s.replace(
"The dashed inset is a 0.25-inch print-safe guide. It is not printed.",
"The dashed inset is a 0.25-inch print-safe guide. It is not printed. Crop, Border and Rotate are available directly above the page.")
s = s.replace(
"Tip: if a printer driver offers scaling, keep it at 100% / Actual Size when exact label dimensions matter.",
"Wake Printer: tap Wake after saving your printer IP/hostname. Long-press Wake to change it. This can wake many network printers from normal sleep, but deep sleep support depends on the printer.\\n\\nTip: if a printer driver offers scaling, keep it at 100% / Actual Size when exact label dimensions matter.")
s = s.replace(
'''        setEnabledStyled(rotateButton, selected);
        setEnabledStyled(undoButton, !undo.isEmpty());''',
'''        setEnabledStyled(rotateButton, selected);
        setEnabledStyled(cropButton, selected);
        setEnabledStyled(borderButton, selected);
        setEnabledStyled(undoButton, !undo.isEmpty());''')

anchor = "    private void applyButtonBackground(Button b, int color, boolean enabled) {"
helpers = r'''    private Button makeCompactButton(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(10);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(0);
        b.setMinWidth(0);
        b.setPadding(dp(2), 0, dp(2), 0);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        p.setMargins(dp(2), 0, dp(2), 0);
        b.setLayoutParams(p);
        applyButtonBackground(b, primary ? 0xFF19B5A5 : 0xFF343A44, true);
        return b;
    }

    private void showBorderDialog() {
        if (!canvasView.hasSelection()) return;
        float current = canvasView.getSelectedBorderPt();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(8), dp(22), 0);
        TextView value = new TextView(this);
        value.setTextColor(Color.DKGRAY);
        value.setTextSize(15);
        value.setText(String.format(java.util.Locale.US, "Thickness: %.0f pt", current));
        SeekBar seek = new SeekBar(this);
        seek.setMax(24);
        seek.setProgress(Math.round(current));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                value.setText(progress == 0 ? "Border: Off" : "Thickness: " + progress + " pt");
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        box.addView(value);
        box.addView(seek);
        new AlertDialog.Builder(this)
                .setTitle("Picture border")
                .setMessage("Black outline. Set thickness to 0 to remove it.")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Apply", (d, w) -> {
                    pushUndo();
                    canvasView.setSelectedBorderPt(seek.getProgress());
                    redo.clear();
                    updateControls();
                })
                .show();
    }

    private void showCropDialog() {
        if (!canvasView.hasSelection()) return;
        float[] original = canvasView.getSelectedCrop();
        final int[] v = new int[]{Math.round(original[0] * 100f), Math.round(original[1] * 100f),
                Math.round(original[2] * 100f), Math.round(original[3] * 100f)};
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(6), dp(22), 0);
        addCropSlider(box, "Left", 0, v);
        addCropSlider(box, "Top", 1, v);
        addCropSlider(box, "Right", 2, v);
        addCropSlider(box, "Bottom", 3, v);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Crop selected picture")
                .setMessage("Move the four sliders to trim the edges. Maximum trim per edge is 45%.")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Reset", null)
                .setPositiveButton("Apply", null)
                .create();
        dialog.setOnShowListener(x -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(vw -> {
                if (v[0] + v[2] > 85 || v[1] + v[3] > 85) {
                    showToast("Crop is too tight. Leave at least 15% of the picture.");
                    return;
                }
                pushUndo();
                canvasView.setSelectedCrop(v[0] / 100f, v[1] / 100f, v[2] / 100f, v[3] / 100f);
                redo.clear();
                dialog.dismiss();
                updateControls();
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(vw -> {
                pushUndo();
                canvasView.setSelectedCrop(0f, 0f, 0f, 0f);
                redo.clear();
                dialog.dismiss();
                updateControls();
            });
        });
        dialog.show();
    }

    private void addCropSlider(LinearLayout box, String name, int index, int[] values) {
        TextView label = new TextView(this);
        label.setTextColor(Color.DKGRAY);
        label.setTextSize(13);
        label.setText(name + ": " + values[index] + "%");
        SeekBar seek = new SeekBar(this);
        seek.setMax(45);
        seek.setProgress(values[index]);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                values[index] = progress;
                label.setText(name + ": " + progress + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        box.addView(label);
        box.addView(seek);
    }

    private void wakePrinter() {
        SharedPreferences prefs = getSharedPreferences("printer", MODE_PRIVATE);
        String host = prefs.getString("host", "").trim();
        if (host.isEmpty()) {
            showPrinterAddressDialog(true);
            return;
        }
        showToast("Trying to wake " + host + "…");
        Diagnostics.log("PRINTER_WAKE_STARTED", "host=" + host);
        final String target = normalizeHost(host);
        new Thread(() -> {
            int respondingPort = -1;
            int[] ports = new int[]{9100, 631, 80, 443};
            for (int round = 0; round < 2 && respondingPort < 0; round++) {
                for (int port : ports) {
                    try (Socket socket = new Socket()) {
                        socket.connect(new InetSocketAddress(target, port), 1200);
                        if (socket.isConnected()) { respondingPort = port; break; }
                    } catch (Exception ignored) { }
                }
                if (respondingPort < 0) {
                    try { Thread.sleep(800); } catch (InterruptedException ignored) { }
                }
            }
            final int port = respondingPort;
            runOnUiThread(() -> {
                if (port > 0) {
                    Diagnostics.log("PRINTER_WAKE_SUCCESS", "host=" + target + ", port=" + port);
                    showToast("Printer responded. Tap Print.");
                } else {
                    Diagnostics.log("PRINTER_WAKE_NO_RESPONSE", "host=" + target);
                    showToast("Wake signal sent, but the printer did not respond. It may be in deep sleep or use a different address.");
                }
            });
        }).start();
    }

    private void showPrinterAddressDialog(boolean wakeAfterSave) {
        SharedPreferences prefs = getSharedPreferences("printer", MODE_PRIVATE);
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Example: 192.168.1.50");
        input.setText(prefs.getString("host", ""));
        input.setSelectAllOnFocus(true);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(dp(22), dp(4), dp(22), 0);
        wrap.addView(input, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("Printer network address")
                .setMessage("Enter the printer's local IP address or hostname. The app will remember it. Long-press Wake anytime to change it.")
                .setView(wrap)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, w) -> {
                    String host = input.getText().toString().trim();
                    if (!host.isEmpty()) {
                        prefs.edit().putString("host", host).apply();
                        Diagnostics.log("PRINTER_ADDRESS_SAVED", "host=" + normalizeHost(host));
                        if (wakeAfterSave) wakePrinter();
                    }
                })
                .show();
    }

    private String normalizeHost(String host) {
        String h = host.trim();
        h = h.replaceFirst("^https?://", "");
        int slash = h.indexOf('/');
        if (slash >= 0) h = h.substring(0, slash);
        int colon = h.indexOf(':');
        if (colon > 0 && h.indexOf(':', colon + 1) < 0) h = h.substring(0, colon);
        return h.trim();
    }

'''
assert anchor in s
s = s.replace(anchor, helpers + anchor)

old_share_start = s.index("    private void handleIncomingShare(Intent intent) {")
old_share_end = s.index("    private Uri copyImageToPrivateStorage", old_share_start)
share = r'''    private void handleIncomingShare(Intent intent) {
        if (intent == null || intent.getType() == null || !intent.getType().startsWith("image/")) return;
        try {
            if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
                java.util.ArrayList<Uri> sources = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (sources == null || sources.isEmpty()) return;
                pushUndo();
                int added = 0;
                for (Uri source : sources) {
                    Uri local = copyImageToPrivateStorage(source);
                    if (local != null && canvasView.addImage(local)) added++;
                }
                if (added > 0) {
                    redo.clear();
                    Diagnostics.log("SHARED_IMAGES_IMPORTED", "count=" + added);
                    showToast(added + " pictures added to the sheet.");
                } else undo.pollFirst();
                return;
            }
            if (!Intent.ACTION_SEND.equals(intent.getAction())) return;
            Uri source = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (source == null) return;
            pushUndo();
            Uri local = copyImageToPrivateStorage(source);
            if (local != null && canvasView.addImage(local)) {
                redo.clear();
                Diagnostics.log("SHARED_IMAGE_IMPORTED", "image received from Android Share menu");
                showToast("Screenshot added to the sheet.");
            } else {
                undo.pollFirst();
                showToast("I couldn't import that shared image.");
            }
        } catch (Exception e) {
            Diagnostics.error("SHARED_IMAGE_IMPORT_FAILED", e);
            showToast("The shared image couldn't be imported. The log captured the error.");
        }
    }

'''
s = s[:old_share_start] + share + s[old_share_end:]
main.write_text(s)

# Network permissions + share multiple
m = manifest.read_text()
m = m.replace(
'    <uses-feature android:name="android.software.print" android:required="false" />',
'    <uses-feature android:name="android.software.print" android:required="false" />\n    <uses-permission android:name="android.permission.INTERNET" />\n    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />')
send = '''            <intent-filter>
                <action android:name="android.intent.action.SEND" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="image/*" />
            </intent-filter>'''
m = m.replace(send, send + '''
            <intent-filter>
                <action android:name="android.intent.action.SEND_MULTIPLE" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:mimeType="image/*" />
            </intent-filter>''')
manifest.write_text(m)

print("Patched LabelSheet to 1.1.0")
