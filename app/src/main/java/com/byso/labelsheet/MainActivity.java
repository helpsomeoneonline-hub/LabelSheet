package com.byso.labelsheet;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintManager;
import android.provider.Settings;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.WindowInsets;
import android.view.View;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.CheckBox;
import android.widget.HorizontalScrollView;
import android.widget.EditText;
import android.widget.SeekBar;
import android.text.InputType;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.Deque;

public class MainActivity extends Activity implements LetterCanvasView.Listener {
    private static final int REQ_ADD = 1001;
    private static final int REQ_REPLACE = 1002;
    private static final int REQ_EXPORT_PDF = 1003;
    private static final int REQ_EXPORT_LOG = 1004;
    private static final int REQ_CROP = 1005;
    private static final int PRINTER_KEEPALIVE_JOB_ID = 12015;
    private static final String DEFAULT_PRINTER_NAME = "Canon TS6500 series";
    private static final String DEFAULT_PRINTER_HOST = "192.168.58.100";
    private static final int DEFAULT_KEEPALIVE_MINUTES = 180;

    private LetterCanvasView canvasView;
    private TextView statusText;
    private Button addButton, replaceButton, duplicateButton, deleteButton, rotateButton, undoButton, redoButton, cropButton, borderButton;
    private Button arrangeButton, pageButton, moreButton, copyFormatButton, pasteFormatButton, lockButton, multiButton, layoutButton, zoomPercentButton, bulkButton;
    private String copiedFormatJson = null;
    private final Deque<String> undo = new ArrayDeque<>();
    private final Deque<String> redo = new ArrayDeque<>();
    private boolean restoringHistory = false;
    private volatile boolean printPreparing = false;
    private QuickActionsBar quickBar;
    private FrameLayout canvasHost;
    private View cropOverlay;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Diagnostics.init(this);
        initializePrinterDefaults();
        setContentView(buildUi());
        restorePrinterKeepAliveSchedule();

        String saved = SheetStore.load(this);
        if (saved != null) canvasView.fromJson(saved);
        updateControls();
        updateStatus();
        handleIncomingShare(getIntent());
    }

    private View buildUi() {
        boolean landscapeUi = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(landscapeUi ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF2F6FC);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
            int left = insets.getSystemWindowInsetLeft();
            int right = insets.getSystemWindowInsetRight();
            v.setPadding(left, top + dp(3), right, bottom);
            return insets;
        });
        root.requestApplyInsets();

        createActionButtons();
        canvasView = new LetterCanvasView(this);
        canvasView.setDefaultNewImageBorder(getSharedPreferences("editor", MODE_PRIVATE)
                .getBoolean("auto_border", true) ? 2f : 0f);
        canvasView.setListener(this);
        canvasHost = new FrameLayout(this);
        canvasHost.addView(canvasView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        quickBar = new QuickActionsBar(this, shortcutSources(), this::printSheet, this::showBorderDialog);
        statusText = new TextView(this);
        statusText.setTextColor(0xFF40526F);
        statusText.setBackgroundColor(0xFFFFFFFF);
        statusText.setTextSize(11);
        statusText.setPadding(dp(10), dp(7), dp(10), dp(7));

        if (landscapeUi) buildLandscapeUi(root);
        else buildPortraitUi(root);
        return root;
    }

    private void createActionButtons() {
        addButton = makeCompactButton("+ Add", true);
        addButton.setOnClickListener(v -> chooseImage(false));

        cropButton = makeCompactButton("Crop", false);
        cropButton.setOnClickListener(v -> showInlineCrop());

        borderButton = makeCompactButton("Border", false);
        borderButton.setOnClickListener(v -> applyQuickBorder());
        borderButton.setOnLongClickListener(v -> { showBorderDialog(); return true; });

        rotateButton = makeCompactButton("Rotate", false);
        rotateButton.setOnClickListener(v -> { pushUndo(); if (canvasView.rotateSelected()) redo.clear(); else undo.pollFirst(); updateControls(); });

        duplicateButton = makeCompactButton("Duplicate", false);
        duplicateButton.setOnClickListener(v -> { pushUndo(); if (canvasView.duplicateSelected()) redo.clear(); else undo.pollFirst(); updateControls(); });

        deleteButton = makeCompactButton("Delete", false);
        deleteButton.setOnClickListener(v -> { pushUndo(); if (canvasView.deleteSelected()) redo.clear(); else undo.pollFirst(); updateControls(); });

        replaceButton = makeCompactButton("Replace", false);
        replaceButton.setOnClickListener(v -> chooseImage(true));

        undoButton = makeCompactButton("Undo", false);
        undoButton.setOnClickListener(v -> undo());
        redoButton = makeCompactButton("Redo", false);
        redoButton.setOnClickListener(v -> redo());

        arrangeButton = makeCompactButton("Arrange", false);
        arrangeButton.setOnClickListener(v -> { if (canvasView.getItemCount() > 0) { pushUndo(); canvasView.autoArrange(); redo.clear(); updateControls(); } });

        pageButton = makeCompactButton("Page ↻", false);
        pageButton.setOnClickListener(v -> { pushUndo(); canvasView.toggleOrientation(); redo.clear(); canvasView.fitPage(); updateStatus(); });

        copyFormatButton = makeCompactButton("Copy Fmt", false);
        copyFormatButton.setOnClickListener(v -> copyFormat());
        pasteFormatButton = makeCompactButton("Paste Fmt", false);
        pasteFormatButton.setOnClickListener(v -> pasteFormat());

        lockButton = makeCompactButton("Lock", false);
        lockButton.setOnClickListener(v -> toggleLock());
        multiButton = makeCompactButton("Multi", false);
        multiButton.setOnClickListener(v -> toggleMultiSelect());

        layoutButton = makeCompactButton("Layout", false);
        layoutButton.setOnClickListener(v -> showLayoutTools());
        moreButton = makeCompactButton("More", false);
        moreButton.setOnClickListener(this::showMoreMenu);
        bulkButton = makeCompactButton("Edit All", true);
        bulkButton.setOnClickListener(v -> showBulkTools());
    }

    private LinearLayout titleStack(boolean compact) {
        LinearLayout stack = new LinearLayout(this);
        stack.setOrientation(LinearLayout.VERTICAL);
        stack.setPadding(compact ? dp(8) : dp(16), compact ? dp(3) : dp(8), dp(8), compact ? dp(3) : dp(5));
        TextView title = new TextView(this);
        title.setText("✦ LabelSheet"); title.setTextColor(0xFF203456); title.setTextSize(compact ? 18 : 22);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        TextView sub = new TextView(this);
        sub.setText("Label Studio • 8.5 × 11 in"); sub.setTextColor(0xFF627897); sub.setTextSize(compact ? 10 : 12);
        stack.addView(title); stack.addView(sub); return stack;
    }

    private Button makeWakeButton() {
        Button b = makeButton("Wake", false);
        b.setOnClickListener(v -> wakePrinter());
        b.setOnLongClickListener(v -> { showPrinterSettingsDialog(false); return true; });
        return b;
    }

    private Button makePrintButton() {
        Button b = makeButton("Print", true); b.setOnClickListener(v -> printSheet()); return b;
    }

    private void buildPortraitUi(LinearLayout root) {
        LinearLayout titleBar = new LinearLayout(this);
        titleBar.setOrientation(LinearLayout.HORIZONTAL); titleBar.setGravity(Gravity.CENTER_VERTICAL);
        titleBar.addView(titleStack(false), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        titleBar.addView(makeWakeButton()); titleBar.addView(makePrintButton());
        root.addView(titleBar);

        LinearLayout row1 = toolRow(); addToRow(row1, addButton, cropButton, borderButton, rotateButton, duplicateButton, deleteButton); root.addView(row1, rowParams(48));
        LinearLayout row2 = toolRow(); addToRow(row2, replaceButton, undoButton, redoButton, arrangeButton, pageButton, moreButton); root.addView(row2, rowParams(48));
        LinearLayout row3 = toolRow(); addToRow(row3, copyFormatButton, pasteFormatButton, lockButton, multiButton, layoutButton, bulkButton); root.addView(row3, rowParams(48));
        root.addView(makeZoomRow(), rowParams(40));
        root.addView(quickBar, rowParams(55));

        TextView hint = new TextView(this);
        hint.setText("Corners = proportional resize • side handles = width/height • pinch = canvas zoom");
        hint.setTextColor(0xFF5F728C); hint.setTextSize(11); hint.setGravity(Gravity.CENTER); hint.setPadding(dp(8), 0, dp(8), dp(4));
        root.addView(hint);
        root.addView(canvasHost, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(statusText);
    }

    private void buildLandscapeUi(LinearLayout root) {
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL); left.setBackgroundColor(0xFFFFFFFF); left.setPadding(dp(5), dp(2), dp(5), dp(2));
        left.addView(titleStack(true));
        LinearLayout printRow = toolRow(); addToRow(printRow, makeWakeButton(), makePrintButton()); left.addView(printRow, rowParams(48));
        addPair(left, addButton, cropButton); addPair(left, borderButton, rotateButton); addPair(left, duplicateButton, deleteButton);
        addPair(left, replaceButton, arrangeButton); addPair(left, copyFormatButton, pasteFormatButton); addPair(left, lockButton, multiButton);
        addPair(left, undoButton, redoButton); addPair(left, pageButton, layoutButton); addPair(left, moreButton, makeFitSelectionButton());
        left.addView(makeZoomRow(), rowParams(48));
        left.addView(bulkButton, rowParams(48));
        // Short landscape phones need a scrollable tool rail. Keep the canvas on the right.
        android.widget.ScrollView rail = new android.widget.ScrollView(this);
        rail.setFillViewport(false);
        rail.setVerticalScrollBarEnabled(true);
        rail.addView(left);
        root.addView(rail, new LinearLayout.LayoutParams(dp(224), LinearLayout.LayoutParams.MATCH_PARENT));

        LinearLayout right = new LinearLayout(this); right.setOrientation(LinearLayout.VERTICAL);
        right.addView(quickBar, rowParams(55));
        right.addView(canvasHost, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        right.addView(statusText);
        root.addView(right, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
    }

    private LinearLayout toolRow() {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setPadding(dp(4), dp(1), dp(4), dp(1)); return row;
    }

    private LinearLayout.LayoutParams rowParams(int h) { return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(h)); }

    private void addToRow(LinearLayout row, Button... buttons) { for (Button b : buttons) row.addView(b); }

    private void addPair(LinearLayout parent, Button a, Button b) { LinearLayout row = toolRow(); addToRow(row, a, b); parent.addView(row, rowParams(48)); }


    private java.util.LinkedHashMap<String, Button> shortcutSources() {
        java.util.LinkedHashMap<String, Button> s = new java.util.LinkedHashMap<>();
        s.put("+ Add", addButton); s.put("Crop", cropButton); s.put("Border", borderButton);
        s.put("Rotate", rotateButton); s.put("Duplicate", duplicateButton);
        s.put("Delete", deleteButton); s.put("Replace", replaceButton);
        s.put("Undo", undoButton); s.put("Redo", redoButton);
        s.put("Arrange", arrangeButton); s.put("Page ↻", pageButton);
        s.put("Copy Fmt", copyFormatButton); s.put("Paste Fmt", pasteFormatButton);
        s.put("Lock", lockButton); s.put("Multi", multiButton);
        s.put("Layout", layoutButton); s.put("Edit All", bulkButton);
        return s;
    }

    private Button makeFitSelectionButton() {
        Button b = makeCompactButton("Fit Sel", false); b.setOnClickListener(v -> { if (!canvasView.fitSelection()) showToast("Select a picture first."); updateStatus(); }); return b;
    }

    private LinearLayout makeZoomRow() {
        LinearLayout row = toolRow();
        Button minus = makeCompactButton("−", false); minus.setOnClickListener(v -> { canvasView.zoomBy(0.82f); updateStatus(); });
        Button fit = makeCompactButton("Fit", false); fit.setOnClickListener(v -> { canvasView.fitPage(); updateStatus(); });
        zoomPercentButton = makeCompactButton("100%", false); zoomPercentButton.setOnClickListener(v -> { canvasView.fitPage(); updateStatus(); });
        zoomPercentButton.setOnLongClickListener(v -> { if (!canvasView.fitSelection()) showToast("Select a picture first."); updateStatus(); return true; });
        Button plus = makeCompactButton("+", false); plus.setOnClickListener(v -> { canvasView.zoomBy(1.22f); updateStatus(); });
        addToRow(row, minus, fit, zoomPercentButton, plus); return row;
    }

    private void applyQuickBorder() {
        if (!canvasView.hasSelection()) { showToast("Select a picture first."); return; }
        pushUndo();
        if (canvasView.setSelectedBorderPt(2f)) {
            redo.clear();
            Diagnostics.log("BORDER_QUICK_APPLIED", "2pt");
            showToast("2 pt border applied. Long-press Border to adjust thickness.");
        } else {
            undo.pollFirst();
            showToast("That picture is locked. Unlock it first.");
        }
        updateControls();
    }

    private void copyFormat() {
        copiedFormatJson = canvasView.getSelectedFormatJson();
        if (copiedFormatJson == null) showToast("Select a picture first.");
        else { Diagnostics.log("FORMAT_COPIED", canvasView.selectedSizeText()); showToast("Format copied. Select another picture and tap Paste Fmt."); }
        updateControls();
    }

    private void pasteFormat() {
        if (copiedFormatJson == null) { showToast("Copy a picture's format first."); return; }
        pushUndo();
        if (canvasView.applyFormatJson(copiedFormatJson)) { redo.clear(); showToast("Format pasted."); }
        else { undo.pollFirst(); showToast("Select an unlocked picture first."); }
        updateControls();
    }

    private void toggleLock() {
        if (!canvasView.hasSelection()) { showToast("Select a picture first."); return; }
        pushUndo();
        if (canvasView.toggleLockSelected()) redo.clear(); else undo.pollFirst();
        updateControls();
    }

    private void toggleMultiSelect() {
        boolean next = !canvasView.isMultiSelectMode();
        canvasView.setMultiSelectMode(next);
        showToast(next ? "Multi-select ON. Tap pictures to add/remove them." : "Multi-select OFF.");
        updateControls();
    }


    private interface BulkOperation { int apply(); }

    private void performBulkEdit(BulkOperation operation, String name) {
        int available = canvasView.countEditableSelected();
        if (available < 1) {
            showToast("Select one or more unlocked pictures first.");
            return;
        }
        pushUndo();
        int changed = operation.apply();
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

    private void showLayoutTools() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(8), dp(4), dp(8), dp(4));
        addDialogToolRow(box,
                toolAction("Left", () -> performLayout(() -> canvasView.alignSelected("left"))),
                toolAction("Center", () -> performLayout(() -> canvasView.alignSelected("center"))),
                toolAction("Right", () -> performLayout(() -> canvasView.alignSelected("right"))));
        addDialogToolRow(box,
                toolAction("Top", () -> performLayout(() -> canvasView.alignSelected("top"))),
                toolAction("Middle", () -> performLayout(() -> canvasView.alignSelected("middle"))),
                toolAction("Bottom", () -> performLayout(() -> canvasView.alignSelected("bottom"))));
        addDialogToolRow(box,
                toolAction("Space H", () -> performLayout(() -> canvasView.distributeSelected(true))),
                toolAction("Space V", () -> performLayout(() -> canvasView.distributeSelected(false))),
                toolAction("Exact Size", this::showExactSizeDialog));
        addDialogToolRow(box,
                toolAction("Nudge", this::showNudgeDialog),
                toolAction("Forward", () -> performLayout(() -> canvasView.bringSelectedForward())),
                toolAction("Backward", () -> performLayout(() -> canvasView.sendSelectedBackward())));
        addDialogToolRow(box,
                toolAction("Duplicate ×2", () -> performLayout(() -> canvasView.duplicateSelectedCopies(2))),
                toolAction("Duplicate ×4", () -> performLayout(() -> canvasView.duplicateSelectedCopies(4))),
                toolAction("Fill Sheet", () -> performLayout(() -> canvasView.fillSheetWithSelected())));
        addDialogToolRow(box,
                toolAction("Save Template", this::saveTemplateDialog),
                toolAction("Load Template", this::loadTemplateDialog),
                toolAction("Fit Selection", () -> { if (!canvasView.fitSelection()) showToast("Select a picture first."); updateStatus(); }));
        new AlertDialog.Builder(this)
                .setTitle("Layout tools")
                .setMessage("For alignment/distribution, turn on Multi and select 2 or more pictures.")
                .setView(box)
                .setPositiveButton("Close", null)
                .show();
    }

    private interface BoolAction { boolean run(); }

    private void performLayout(BoolAction action) {
        pushUndo();
        if (action.run()) { redo.clear(); updateControls(); }
        else { undo.pollFirst(); showToast("That action needs a suitable unlocked selection."); }
    }

    private Button toolAction(String text, Runnable action) {
        Button b = new Button(this);
        b.setText(text); b.setTextSize(11); b.setAllCaps(false); b.setTextColor(Color.WHITE);
        b.setMinHeight(0); b.setMinWidth(0); b.setPadding(dp(3), 0, dp(3), 0);
        applyButtonBackground(b, 0xFF343A44, true);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private void addDialogToolRow(LinearLayout parent, Button... buttons) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        for (Button b : buttons) { LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(38), 1f); lp.setMargins(dp(2), dp(2), dp(2), dp(2)); row.addView(b, lp); }
        parent.addView(row);
    }

    private void showExactSizeDialog() {
        if (!canvasView.hasSelection()) { showToast("Select a picture first."); return; }
        float[] size = canvasView.getSelectedSizeInches();
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(20), dp(4), dp(20), 0);
        EditText w = new EditText(this); w.setHint("Width in inches"); w.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL); w.setText(String.format(java.util.Locale.US, "%.2f", size[0]));
        EditText h = new EditText(this); h.setHint("Height in inches"); h.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL); h.setText(String.format(java.util.Locale.US, "%.2f", size[1]));
        box.addView(w); box.addView(h);
        new AlertDialog.Builder(this).setTitle("Exact printed size").setView(box).setNegativeButton("Cancel", null)
                .setPositiveButton("Apply", (d, which) -> {
                    try { float wi=Float.parseFloat(w.getText().toString()); float hi=Float.parseFloat(h.getText().toString()); pushUndo(); if(canvasView.applyBulkSizeInches(wi,hi) > 0) redo.clear(); else { undo.pollFirst(); showToast("Enter a size that fits the sheet."); } updateControls(); }
                    catch(Exception e){ showToast("Enter valid width and height."); }
                }).show();
    }

    private void showNudgeDialog() {
        if (!canvasView.hasSelection()) { showToast("Select a picture first."); return; }
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(20), dp(6), dp(20), dp(6));
        TextView note = new TextView(this); note.setText("Fine move: 0.02 inch per tap"); note.setTextColor(Color.DKGRAY); note.setGravity(Gravity.CENTER); box.addView(note);
        Button up=toolAction("↑",()->nudge(0f,-0.02f)); LinearLayout r1=new LinearLayout(this); r1.setGravity(Gravity.CENTER); r1.addView(up,new LinearLayout.LayoutParams(dp(80),dp(42))); box.addView(r1);
        LinearLayout r2=new LinearLayout(this); Button left=toolAction("←",()->nudge(-0.02f,0f)); Button right=toolAction("→",()->nudge(0.02f,0f)); r2.addView(left,new LinearLayout.LayoutParams(0,dp(42),1f)); r2.addView(right,new LinearLayout.LayoutParams(0,dp(42),1f)); box.addView(r2);
        Button down=toolAction("↓",()->nudge(0f,0.02f)); LinearLayout r3=new LinearLayout(this); r3.setGravity(Gravity.CENTER); r3.addView(down,new LinearLayout.LayoutParams(dp(80),dp(42))); box.addView(r3);
        new AlertDialog.Builder(this).setTitle("Nudge selection").setView(box).setPositiveButton("Done",null).show();
    }

    private void nudge(float dx, float dy) { pushUndo(); if(canvasView.nudgeSelected(dx,dy)) redo.clear(); else undo.pollFirst(); updateControls(); }

    private void saveTemplateDialog() {
        if (canvasView.getItemCount() == 0) { showToast("There is no layout to save."); return; }
        EditText input = new EditText(this); input.setHint("Template name"); input.setSingleLine(true); input.setText("My layout"); input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this).setTitle("Save layout template").setView(input).setNegativeButton("Cancel",null)
                .setPositiveButton("Save",(d,w)->{ String name=input.getText().toString().trim(); if(name.isEmpty())name="My layout"; getSharedPreferences("templates",MODE_PRIVATE).edit().putString(name,canvasView.toJson()).apply(); Diagnostics.log("TEMPLATE_SAVED",name); showToast("Template saved: "+name); }).show();
    }

    private void loadTemplateDialog() {
        java.util.Map<String,?> all = getSharedPreferences("templates",MODE_PRIVATE).getAll();
        if (all.isEmpty()) { showToast("No saved templates yet."); return; }
        String[] names = all.keySet().toArray(new String[0]); java.util.Arrays.sort(names);
        new AlertDialog.Builder(this).setTitle("Load template").setItems(names,(d,which)->{ Object val=all.get(names[which]); if(val instanceof String){ pushUndo(); canvasView.fromJson((String)val); redo.clear(); persist(); Diagnostics.log("TEMPLATE_LOADED",names[which]); updateControls(); } }).setNegativeButton("Cancel",null).show();
    }

    private void showMoreMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add("Editor preferences");
        menu.getMenu().add("My tool usage");
        menu.getMenu().add("Customize quick tools");
        menu.getMenu().add("Test Printer Connection");
        menu.getMenu().add("Printer Keep-Awake Settings");
        menu.getMenu().add("Export Letter PDF");
        menu.getMenu().add("Copy Diagnostic Log");
        menu.getMenu().add("Export Diagnostic Log");
        menu.getMenu().add("Clear Sheet");
        menu.getMenu().add("About / Troubleshooting");
        menu.setOnMenuItemClickListener(item -> {
            String t = String.valueOf(item.getTitle());
            UsageInsights.record(this, t);
            if (t.startsWith("Editor preferences")) showEditorPreferences();
            else if (t.startsWith("My tool usage")) showUsageInsights();
            else if (t.startsWith("Customize quick")) quickBar.setArranging(true);
            else if (t.startsWith("Test Printer")) wakePrinter();
            else if (t.startsWith("Printer Keep-Awake")) showPrinterSettingsDialog(false);
            else if (t.startsWith("Export Letter")) exportPdf();
            else if (t.startsWith("Copy Diagnostic")) copyLog();
            else if (t.startsWith("Export Diagnostic")) exportLog();
            else if (t.startsWith("Clear Sheet")) confirmClear();
            else showAbout();
            return true;
        });
        menu.show();
    }


    private void showEditorPreferences() {
        SharedPreferences prefs = getSharedPreferences("editor", MODE_PRIVATE);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), dp(8));
        CheckBox border = new CheckBox(this);
        border.setText("Add a 2 pt black border to every new picture");
        border.setChecked(prefs.getBoolean("auto_border", true));
        form.addView(border);
        CheckBox adaptive = new CheckBox(this);
        adaptive.setText("Suggest my most-used tools after 20 taps");
        adaptive.setChecked(prefs.getBoolean("smart_tools", true));
        form.addView(adaptive);
        new AlertDialog.Builder(this).setTitle("Editor preferences").setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, w) -> {
                    prefs.edit().putBoolean("auto_border", border.isChecked())
                            .putBoolean("smart_tools", adaptive.isChecked()).apply();
                    canvasView.setDefaultNewImageBorder(border.isChecked() ? 2f : 0f);
                    quickBar.refresh();
                    Diagnostics.log("EDITOR_PREFS_CHANGED", "auto_border=" + border.isChecked());
                }).show();
    }

    private void showUsageInsights() {
        new AlertDialog.Builder(this).setTitle("My tool usage")
                .setMessage(UsageInsights.report(this))
                .setNegativeButton("Close", null)
                .setNeutralButton("Reset counts", (d, w) ->
                        new AlertDialog.Builder(this).setTitle("Reset usage counts?")
                                .setMessage("This does not change any saved labels.")
                                .setNegativeButton("Cancel", null)
                                .setPositiveButton("Reset", (q, k) -> {
                                    UsageInsights.clear(this);
                                    quickBar.refresh();
                                }).show())
                .setPositiveButton("Copy", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("LabelSheet usage", UsageInsights.report(this)));
                    showToast("Usage report copied.");
                }).show();
    }

    private void chooseImage(boolean replace) {
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
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        try {
            if (requestCode == REQ_ADD) {
                pushUndo();
                int added = 0;
                if (data.getClipData() != null) {
                    for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                        Uri source = data.getClipData().getItemAt(i).getUri();
                        Uri local = copyImageToPrivateStorage(source);
                        if (local != null && canvasView.addImage(local)) added++;
                    }
                } else if (uri != null) {
                    Uri local = copyImageToPrivateStorage(uri);
                    if (local != null && canvasView.addImage(local)) added++;
                }
                if (added == 0) {
                    undo.pollFirst();
                    showToast("I couldn't open that image. Try another screenshot.");
                } else {
                    redo.clear();
                    if (added > 1) showToast(added + " labels added.");
                }
            } else if (requestCode == REQ_REPLACE && uri != null) {
                pushUndo();
                Uri local = copyImageToPrivateStorage(uri);
                if (local == null || !canvasView.replaceSelected(local)) {
                    undo.pollFirst();
                    showToast("I couldn't replace that label image.");
                } else redo.clear();
            } else if (requestCode == REQ_CROP) {
                float left = data.getFloatExtra("cropLeft", 0f);
                float top = data.getFloatExtra("cropTop", 0f);
                float right = data.getFloatExtra("cropRight", 0f);
                float bottom = data.getFloatExtra("cropBottom", 0f);
                pushUndo();
                if (canvasView.setSelectedCrop(left, top, right, bottom)) {
                    redo.clear();
                    Diagnostics.log("VISUAL_CROP_APPLIED", String.format(java.util.Locale.US,
                            "l=%.3f,t=%.3f,r=%.3f,b=%.3f", left, top, right, bottom));
                } else {
                    undo.pollFirst();
                }
            } else if (requestCode == REQ_EXPORT_PDF && uri != null) {
                writePdf(uri);
            } else if (requestCode == REQ_EXPORT_LOG && uri != null) {
                writeText(uri, Diagnostics.read(), "LOG_EXPORTED");
            }
        } catch (Exception e) {
            Diagnostics.error("ACTIVITY_RESULT_FAILED", e);
            showToast("Something went wrong. The diagnostic log captured it.");
        }
        updateControls();
        updateStatus();
    }

    private void pushUndo() {
        if (restoringHistory) return;
        String state = canvasView.toJson();
        if (undo.isEmpty() || !state.equals(undo.peekFirst())) undo.addFirst(state);
        while (undo.size() > 40) undo.removeLast();
    }

    private void undo() {
        if (undo.isEmpty()) return;
        String current = canvasView.toJson();
        String previous = undo.removeFirst();
        redo.addFirst(current);
        restoringHistory = true;
        canvasView.fromJson(previous);
        restoringHistory = false;
        persist();
        Diagnostics.log("UNDO", "ok");
        updateControls();
        updateStatus();
    }

    private void redo() {
        if (redo.isEmpty()) return;
        String current = canvasView.toJson();
        String next = redo.removeFirst();
        undo.addFirst(current);
        restoringHistory = true;
        canvasView.fromJson(next);
        restoringHistory = false;
        persist();
        Diagnostics.log("REDO", "ok");
        updateControls();
        updateStatus();
    }

    private void printSheet() {
        if (canvasView.getItemCount() == 0) {
            showToast("Add at least one label first.");
            return;
        }
        if (printPreparing) {
            showToast("Printer is already being prepared…");
            return;
        }
        printPreparing = true;
        SharedPreferences prefs = getSharedPreferences("printer", MODE_PRIVATE);
        final String preferred = prefs.getString("host", DEFAULT_PRINTER_HOST);
        Diagnostics.log("PRINT_PREFLIGHT_STARTED", "preferred=" + PrinterWake.normalizeHost(preferred) + ", scan=192.168.58.100-110");
        showToast("Finding and waking Canon printer…");
        new Thread(() -> {
            final PrinterWake.Result result = PrinterWake.reconnectForPrint(preferred, prefs.getBoolean("aggressive_reconnect", true));
            if (result != null && !result.host.equals(PrinterWake.normalizeHost(preferred))) {
                prefs.edit().putString("host", result.host).apply();
                Diagnostics.log("PRINTER_IP_UPDATED", "old=" + PrinterWake.normalizeHost(preferred) + ", new=" + result.host);
            }
            runOnUiThread(() -> {
                if (result != null) {
                    Diagnostics.log("PRINT_SERVICE_REACHABLE", "host=" + result.host + ", port=" + result.port +
                            (result.discovered ? ", discovered=true" : "") +
                            "; physical print outcome is not yet known");
                    updateStatus();
                    showToast("Printer network service responding at " + result.host + ". Opening Android print…");
                    // Give Android's print service a moment to rediscover a printer whose DHCP address changed.
                    canvasView.postDelayed(() -> {
                        printPreparing = false;
                        openSystemPrintDialog(result.host);
                    }, 1200);
                } else {
                    Diagnostics.log("PRINT_PRINTER_NOT_FOUND", "searched=192.168.58.100-110; falling back to Android print service");
                    showToast("Printer still not responding. Check Canon Auto Power On, or choose it in Android Print.");
                    canvasView.postDelayed(() -> {
                        printPreparing = false;
                        openSystemPrintDialog(null);
                    }, 500);
                }
            });
        }).start();
    }

    private void openSystemPrintDialog(String discoveredHost) {
        try {
            PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
            if (pm == null) {
                Diagnostics.log("PRINT_UNAVAILABLE", "PrintManager service missing");
                showToast("Android printing isn't available on this device.");
                return;
            }
            PrintAttributes.MediaSize letter = PrintAttributes.MediaSize.NA_LETTER;
            if (!canvasView.isPortrait()) letter = letter.asLandscape();
            PrintAttributes attrs = new PrintAttributes.Builder()
                    .setMediaSize(letter)
                    .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                    .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                    .build();
            Diagnostics.log("PRINT_STARTED", "labels=" + canvasView.getItemCount() + ", orientation=" +
                    (canvasView.isPortrait() ? "portrait" : "landscape") +
                    (discoveredHost == null ? "" : ", printer=" + discoveredHost));
            pm.print("LabelSheet - Letter", new SheetPrintAdapter(), attrs);
        } catch (Exception e) {
            Diagnostics.error("PRINT_START_FAILED", e);
            showToast("Print couldn't start. Export the diagnostic log if it keeps happening.");
        }
    }

    private final class SheetPrintAdapter extends PrintDocumentAdapter {
        @Override public void onLayout(PrintAttributes oldAttributes, PrintAttributes newAttributes,
                                       CancellationSignal cancellationSignal, LayoutResultCallback callback, Bundle extras) {
            if (cancellationSignal.isCanceled()) {
                callback.onLayoutCancelled();
                return;
            }
            PrintDocumentInfo info = new PrintDocumentInfo.Builder("LabelSheet-Letter.pdf")
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(1)
                    .build();
            callback.onLayoutFinished(info, true);
        }

        @Override public void onWrite(PageRange[] pages, ParcelFileDescriptor destination,
                                      CancellationSignal cancellationSignal, WriteResultCallback callback) {
            PdfDocument doc = null;
            try {
                if (cancellationSignal.isCanceled()) {
                    callback.onWriteCancelled();
                    return;
                }
                doc = createPdfDocument();
                try (FileOutputStream out = new FileOutputStream(destination.getFileDescriptor())) {
                    doc.writeTo(out);
                }
                Diagnostics.log("PRINT_PDF_READY", "one Letter page generated successfully");
                callback.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES});
            } catch (Exception e) {
                Diagnostics.error("PRINT_WRITE_FAILED", e);
                callback.onWriteFailed(e.getMessage());
            } finally {
                if (doc != null) doc.close();
            }
        }

        @Override public void onFinish() {
            Diagnostics.log("PRINT_HANDOFF_FINISHED", "Android print dialog/process finished");
        }
    }

    private PdfDocument createPdfDocument() {
        boolean portrait = canvasView.isPortrait();
        int width = portrait ? 612 : 792;
        int height = portrait ? 792 : 612;
        PdfDocument doc = new PdfDocument();
        PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(width, height, 1).create();
        PdfDocument.Page page = doc.startPage(info);
        canvasView.renderForOutput(page.getCanvas(), new RectF(0, 0, width, height));
        doc.finishPage(page);
        return doc;
    }

    private void exportPdf() {
        if (canvasView.getItemCount() == 0) {
            showToast("Add at least one label first.");
            return;
        }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/pdf");
        i.putExtra(Intent.EXTRA_TITLE, "LabelSheet-Letter.pdf");
        startActivityForResult(i, REQ_EXPORT_PDF);
    }

    private void writePdf(Uri uri) {
        PdfDocument doc = null;
        try {
            doc = createPdfDocument();
            try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "w");
                 FileOutputStream out = pfd == null ? null : new FileOutputStream(pfd.getFileDescriptor())) {
                if (out == null) throw new IllegalStateException("Could not open export destination");
                doc.writeTo(out);
            }
            Diagnostics.log("PDF_EXPORTED", "Letter PDF exported successfully");
            showToast("PDF exported.");
        } catch (Exception e) {
            Diagnostics.error("PDF_EXPORT_FAILED", e);
            showToast("PDF export failed. The log captured the error.");
        } finally {
            if (doc != null) doc.close();
        }
    }

    private void copyLog() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("LabelSheet diagnostic log", Diagnostics.read()));
            Diagnostics.log("LOG_COPIED", "diagnostic log copied to clipboard");
            showToast("Diagnostic log copied. You can paste it into ChatGPT.");
        } catch (Exception e) {
            Diagnostics.error("LOG_COPY_FAILED", e);
            showToast("Couldn't copy the log.");
        }
    }

    private void exportLog() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TITLE, "LabelSheet-diagnostics.txt");
        startActivityForResult(i, REQ_EXPORT_LOG);
    }

    private void writeText(Uri uri, String text, String event) {
        try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "w");
             FileOutputStream out = pfd == null ? null : new FileOutputStream(pfd.getFileDescriptor())) {
            if (out == null) throw new IllegalStateException("Could not open export destination");
            out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
            Diagnostics.log(event, "success");
            showToast("Diagnostic log exported.");
        } catch (Exception e) {
            Diagnostics.error("LOG_EXPORT_FAILED", e);
            showToast("Couldn't export the diagnostic log.");
        }
    }

    private void confirmClear() {
        if (canvasView.getItemCount() == 0) return;
        new AlertDialog.Builder(this)
                .setTitle("Clear this sheet?")
                .setMessage("All labels on the current page will be removed. You can still use Undo immediately afterward.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", (d, w) -> {
                    pushUndo();
                    canvasView.clearAll();
                    redo.clear();
                    updateControls();
                })
                .show();
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle("LabelSheet 1.6.0")
                .setMessage("Letter page: 8.5 × 11 inches.\n\nLandscape phone view puts the tool rail on the left and the canvas on the right. Pinch with two fingers to zoom the canvas; use − / Fit / + for button zoom. Drag blank canvas space to pan while zoomed.\n\nPicture editing: corner handles resize proportionally; side handles change width or height only. Tap Border for the standard 2 pt border; long-press Border for manual thickness. Copy Fmt / Paste Fmt copies size, crop, rotation and border without copying the picture or position.\n\nTurn on Multi to select several pictures, then Layout for alignment, even spacing, exact size, nudge, layers, duplicate/fill-sheet and templates. Lock prevents accidental movement or editing. Smart teal guides still snap pictures to page and picture edges/centers.\n\nFor troubleshooting: More → Copy Diagnostic Log, then paste the log into ChatGPT. The log records app actions and errors, not the pixels inside your label images.\n\nWake Printer: Canon TS6500 series is auto-discovered in 192.168.58.100–110, and background keep-awake defaults to every 3 hours.\n\nTip: if a printer driver offers scaling, keep it at 100% / Actual Size when exact label dimensions matter.")
                .setPositiveButton("OK", null)
                .show();
    }

    private Button makeButton(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setTextColor(0xFF203456);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(0);
        b.setMinWidth(0);
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40));
        p.setMargins(dp(4), 0, dp(4), 0);
        b.setLayoutParams(p);
        applyButtonBackground(b, primary ? 0xFF2563EB : 0xFFFFFFFF, true);
        attachUsageTracking(b);
        return b;
    }

    private Button makeCompactButton(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setTextColor(0xFF203456);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(0);
        b.setMinWidth(0);
        b.setPadding(dp(2), 0, dp(2), 0);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        p.setMargins(dp(2), 0, dp(2), 0);
        b.setLayoutParams(p);
        applyButtonBackground(b, primary ? 0xFF2563EB : 0xFFFFFFFF, true);
        attachUsageTracking(b);
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


    /** Live crop overlay: no new activity, screen rotation or canvas layout resize. */
    private void showInlineCrop() {
        if (!canvasView.hasSelection() || canvasView.isSelectedLocked()) {
            showToast("Select an unlocked label first."); return;
        }
        if (cropOverlay != null) return;
        final float[] original = canvasView.getSelectedCrop();
        final int[] values = new int[] {
                Math.round(original[0] * 100f), Math.round(original[1] * 100f),
                Math.round(original[2] * 100f), Math.round(original[3] * 100f)
        };
        pushUndo();

        FrameLayout scrim = new FrameLayout(this);
        scrim.setClickable(true);
        scrim.setBackgroundColor(0x44000000);
        cropOverlay = scrim;
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(10), dp(14), dp(8));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFFFAFCFF);
        background.setCornerRadii(new float[]{dp(18),dp(18),dp(18),dp(18),0,0,0,0});
        panel.setBackground(background);

        TextView title = new TextView(this);
        title.setText("Crop label  •  adjust edges below");
        title.setTextColor(0xFF1F3458);
        title.setTextSize(16);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        panel.addView(title);
        String[] names = new String[]{"Left", "Top", "Right", "Bottom"};
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            TextView label = new TextView(this);
            label.setText(names[i] + " " + values[i] + "%");
            label.setTextColor(0xFF33496F);
            label.setTextSize(12);
            line.addView(label, new LinearLayout.LayoutParams(dp(84), dp(36)));
            SeekBar seek = new SeekBar(this);
            seek.setMax(45);
            seek.setProgress(values[i]);
            seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                    values[idx] = progress;
                    if (values[0] + values[2] > 85) values[idx] = 85 - values[idx == 0 ? 2 : 0];
                    if (values[1] + values[3] > 85) values[idx] = 85 - values[idx == 1 ? 3 : 1];
                    if (values[idx] != progress) { bar.setProgress(values[idx]); return; }
                    label.setText(names[idx] + " " + values[idx] + "%");
                    canvasView.setSelectedCrop(values[0]/100f, values[1]/100f,
                            values[2]/100f, values[3]/100f);
                }
                @Override public void onStartTrackingTouch(SeekBar bar) {}
                @Override public void onStopTrackingTouch(SeekBar bar) {}
            });
            line.addView(seek, new LinearLayout.LayoutParams(0, dp(36), 1f));
            panel.addView(line);
        }
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END);
        Button cancel = makeButton("Cancel", false);
        cancel.setOnClickListener(v -> {
            canvasView.setSelectedCrop(original[0],original[1],original[2],original[3]);
            undo.pollFirst();
            canvasHost.removeView(scrim);
            cropOverlay = null;
            updateControls();
        });
        Button reset = makeButton("Reset", false);
        reset.setOnClickListener(v -> {
            canvasView.setSelectedCrop(0f,0f,0f,0f);
            canvasHost.removeView(scrim);
            cropOverlay = null;
            redo.clear();
            updateControls();
        });
        Button done = makeButton("Apply", true);
        done.setOnClickListener(v -> {
            redo.clear();
            Diagnostics.log("INLINE_CROP_APPLIED", "crop adjusted on page");
            canvasHost.removeView(scrim);
            cropOverlay = null;
            updateControls();
        });
        actions.addView(cancel); actions.addView(reset); actions.addView(done);
        panel.addView(actions);
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        scrim.addView(panel, p);
        canvasHost.addView(scrim, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        Diagnostics.log("INLINE_CROP_OPENED", "editor stays in place");
    }

    private void launchVisualCrop() {
        if (canvasView == null || !canvasView.hasSelection()) return;
        Uri selectedUri = canvasView.getSelectedUri();
        if (selectedUri == null) {
            showToast("I couldn't open that picture for cropping.");
            return;
        }
        float[] crop = canvasView.getSelectedCrop();
        Intent i = new Intent(this, CropActivity.class);
        i.putExtra("imageUri", selectedUri.toString());
        i.putExtra("cropLeft", crop[0]);
        i.putExtra("cropTop", crop[1]);
        i.putExtra("cropRight", crop[2]);
        i.putExtra("cropBottom", crop[3]);
        Diagnostics.log("VISUAL_CROP_OPENED", "selected picture");
        startActivityForResult(i, REQ_CROP);
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

    private void initializePrinterDefaults() {
        SharedPreferences prefs = getSharedPreferences("printer", MODE_PRIVATE);
        SharedPreferences.Editor e = prefs.edit();
        boolean changed = false;
        if (!prefs.contains("host")) { e.putString("host", DEFAULT_PRINTER_HOST); changed = true; }
        if (!prefs.contains("printer_name")) { e.putString("printer_name", DEFAULT_PRINTER_NAME); changed = true; }
        if (!prefs.contains("keep_alive_enabled")) { e.putBoolean("keep_alive_enabled", true); changed = true; }
        if (!prefs.getBoolean("v14_printer_defaults", false)) {
            // v1.4 changes the requested default from 15 minutes to 3 hours, including upgrades from older builds.
            e.putInt("interval_minutes", DEFAULT_KEEPALIVE_MINUTES);
            e.putBoolean("v14_printer_defaults", true);
            changed = true;
        } else if (!prefs.contains("interval_minutes")) {
            e.putInt("interval_minutes", DEFAULT_KEEPALIVE_MINUTES);
            changed = true;
        }
        if (changed) {
            e.apply();
            Diagnostics.log("PRINTER_DEFAULTS_INITIALIZED", DEFAULT_PRINTER_NAME + " @ auto-discovery 192.168.58.100-110, 3h keep-alive");
        }
    }

    private void wakePrinter() {
        SharedPreferences prefs = getSharedPreferences("printer", MODE_PRIVATE);
        String preferred = prefs.getString("host", DEFAULT_PRINTER_HOST).trim();
        showToast("Finding Canon printer on 192.168.58.100–110…");
        Diagnostics.log("PRINTER_WAKE_STARTED", "preferred=" + PrinterWake.normalizeHost(preferred) + ", scan=192.168.58.100-110");
        new Thread(() -> {
            final PrinterWake.Result result = PrinterWake.reconnectForPrint(preferred, prefs.getBoolean("aggressive_reconnect", true));
            if (result != null && !result.host.equals(PrinterWake.normalizeHost(preferred))) {
                prefs.edit().putString("host", result.host).apply();
                Diagnostics.log("PRINTER_IP_UPDATED", "old=" + PrinterWake.normalizeHost(preferred) + ", new=" + result.host);
            }
            runOnUiThread(() -> {
                if (result != null) {
                    Diagnostics.log("PRINTER_WAKE_SUCCESS", "host=" + result.host + ", port=" + result.port + (result.discovered ? ", discovered=true" : ""));
                    new AlertDialog.Builder(this)
                            .setTitle("Printer connection checked")
                            .setMessage(result.host + "\n" + PrinterWake.serviceDescription(result) +
                                    "\n\nThis does not confirm that a physical page will print. " +
                                    "Check Canon Auto Power On and Auto Power Off settings if it becomes unreachable after being idle.")
                            .setPositiveButton("Print", (d, w) -> printSheet())
                            .setNegativeButton("Close", null).show();
                    updateStatus();
                } else {
                    Diagnostics.log("PRINTER_WAKE_NO_RESPONSE", "searched=192.168.58.100-110");
                    new AlertDialog.Builder(this)
                            .setTitle("Printer did not respond")
                            .setMessage("No network print service answered. Check printer power, Wi-Fi, " +
                                    "and Canon Auto Power On. A detected IP address does not prove it can accept a job.")
                            .setPositiveButton("Retry", (d, w) -> wakePrinter())
                            .setNegativeButton("Close", null).show();
                }
            });
        }).start();
    }

    private void showPrinterSettingsDialog(boolean wakeAfterSave) {
        SharedPreferences prefs = getSharedPreferences("printer", MODE_PRIVATE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(6), dp(22), 0);

        TextView hostLabel = new TextView(this);
        hostLabel.setText("Last detected printer IP (auto-search range: 192.168.58.100–110)");
        hostLabel.setTextColor(Color.DKGRAY);
        hostLabel.setTextSize(13);
        box.addView(hostLabel);

        EditText hostInput = new EditText(this);
        hostInput.setSingleLine(true);
        hostInput.setHint("Auto-detected after Wake / background scan");
        hostInput.setText(prefs.getString("host", DEFAULT_PRINTER_HOST));
        hostInput.setSelectAllOnFocus(true);
        box.addView(hostInput, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        CheckBox keepAlive = new CheckBox(this);
        keepAlive.setText("Keep printer awake in the background");
        keepAlive.setTextColor(Color.DKGRAY);
        keepAlive.setChecked(prefs.getBoolean("keep_alive_enabled", true));
        box.addView(keepAlive);

        CheckBox aggressive = new CheckBox(this);
        aggressive.setText("Aggressive reconnect: retry network service before printing");
        aggressive.setTextColor(Color.DKGRAY);
        aggressive.setChecked(prefs.getBoolean("aggressive_reconnect", true));
        box.addView(aggressive);

        TextView intervalLabel = new TextView(this);
        intervalLabel.setText("Wake interval in minutes (default 180 = 3 hours; minimum 15)");
        intervalLabel.setTextColor(Color.DKGRAY);
        intervalLabel.setTextSize(13);
        box.addView(intervalLabel);

        EditText intervalInput = new EditText(this);
        intervalInput.setSingleLine(true);
        intervalInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        intervalInput.setText(String.valueOf(Math.max(15, prefs.getInt("interval_minutes", DEFAULT_KEEPALIVE_MINUTES))));
        box.addView(intervalInput, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView note = new TextView(this);
        note.setText("Aggressive mode makes a few bounded reconnection attempts before scanning. " +
                "Background checks are Android scheduled and may be delayed by battery saving. " +
                "Canon Auto Power On must be enabled on compatible printers to accept wake-on-print. " +
                "A reachable IP does not guarantee that a job is printed.");
        note.setTextColor(0xFF666666);
        note.setTextSize(12);
        note.setPadding(0, dp(8), 0, 0);
        box.addView(note);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Printer wake settings")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", null)
                .create();
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String host = hostInput.getText().toString().trim();
            int minutes = 15;
            try { minutes = Integer.parseInt(intervalInput.getText().toString().trim()); }
            catch (Exception ignored) { }
            if (host.isEmpty()) host = DEFAULT_PRINTER_HOST;
            if (minutes < 15) {
                minutes = 15;
                showToast("Android's minimum background interval is 15 minutes, so I set it to 15.");
            }
            if (minutes > 1440) minutes = 1440;
            prefs.edit()
                    .putString("host", host)
                    .putBoolean("keep_alive_enabled", keepAlive.isChecked())
                    .putBoolean("aggressive_reconnect", aggressive.isChecked())
                    .putInt("interval_minutes", minutes)
                    .apply();
            Diagnostics.log("PRINTER_SETTINGS_SAVED", "host=" + PrinterWake.normalizeHost(host) + ", keepAlive=" + keepAlive.isChecked() + ", interval=" + minutes);
            schedulePrinterKeepAlive(keepAlive.isChecked(), minutes);
            dialog.dismiss();
            if (wakeAfterSave) wakePrinter();
        }));
        dialog.show();
    }

    private void restorePrinterKeepAliveSchedule() {
        SharedPreferences prefs = getSharedPreferences("printer", MODE_PRIVATE);
        if (prefs.getBoolean("keep_alive_enabled", true)) {
            schedulePrinterKeepAlive(true, Math.max(15, prefs.getInt("interval_minutes", DEFAULT_KEEPALIVE_MINUTES)));
        }
    }

    private void schedulePrinterKeepAlive(boolean enabled, int minutes) {
        JobScheduler scheduler = (JobScheduler) getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) {
            Diagnostics.log("PRINTER_KEEPALIVE_SCHEDULE_FAILED", "JobScheduler unavailable");
            return;
        }
        scheduler.cancel(PRINTER_KEEPALIVE_JOB_ID);
        if (!enabled) {
            Diagnostics.log("PRINTER_KEEPALIVE_DISABLED", "scheduled job cancelled");
            showToast("Automatic printer keep-awake is off.");
            return;
        }
        long intervalMs = Math.max(15, minutes) * 60_000L;
        JobInfo job = new JobInfo.Builder(PRINTER_KEEPALIVE_JOB_ID, new ComponentName(this, PrinterKeepAliveService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(intervalMs)
                .setPersisted(true)
                .build();
        int result = scheduler.schedule(job);
        Diagnostics.log("PRINTER_KEEPALIVE_SCHEDULED", "minutes=" + Math.max(15, minutes) + ", result=" + result);
        if (result == JobScheduler.RESULT_SUCCESS) {
            int actual = Math.max(15, minutes);
            if (actual % 60 == 0) showToast("Printer keep-awake set for about every " + (actual / 60) + " hour" + ((actual / 60) == 1 ? "" : "s") + ".");
            else showToast("Printer keep-awake set for about every " + actual + " minutes.");
        }
        else showToast("Android could not schedule printer keep-awake. Check the diagnostic log.");
    }

    private void attachUsageTracking(Button b) {
        b.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_UP && view.isEnabled()
                    && event.getX() >= 0 && event.getX() < view.getWidth()
                    && event.getY() >= 0 && event.getY() < view.getHeight()) {
                UsageInsights.record(getApplicationContext(), b.getText().toString());
            }
            return false;
        });
    }

    private void applyButtonBackground(Button b, int color, boolean enabled) {
        GradientDrawable gd = new GradientDrawable();
        boolean primary = color == 0xFF2563EB || color == 0xFF19B5A5 || color == 0xFF168F84;
        gd.setColor(enabled ? (primary ? 0xFF2563EB : 0xFFFFFFFF) : 0xFFE5EAF4);
        gd.setCornerRadius(dp(11));
        gd.setStroke(dp(1), enabled ? (primary ? 0xFF2563EB : 0xFFCAD7E9) : 0xFFD2DEED);
        b.setTextColor(primary && enabled ? Color.WHITE : 0xFF203456);
        b.setBackground(gd);
        b.setAlpha(enabled ? 1f : 0.66f);
    }

    private void updateControls() {
        if (canvasView == null) return;
        boolean selected = canvasView.hasSelection();
        boolean locked = selected && canvasView.isSelectedLocked();
        setEnabledStyled(replaceButton, selected && !locked);
        setEnabledStyled(duplicateButton, selected);
        setEnabledStyled(deleteButton, selected && !locked);
        setEnabledStyled(rotateButton, selected && !locked);
        setEnabledStyled(cropButton, selected && !locked);
        setEnabledStyled(borderButton, selected && !locked);
        setEnabledStyled(copyFormatButton, selected);
        setEnabledStyled(pasteFormatButton, selected && !locked && copiedFormatJson != null);
        setEnabledStyled(lockButton, selected);
        if (lockButton != null) lockButton.setText(locked ? "Unlock" : "Lock");
        if (multiButton != null) {
            multiButton.setText(canvasView.isMultiSelectMode() ? "Multi ✓" : "Multi");
            applyButtonBackground(multiButton, canvasView.isMultiSelectMode() ? 0xFF168F84 : 0xFFFFFFFF, true);
        }
        setEnabledStyled(bulkButton, canvasView.getSelectionCount() > 0);
        setEnabledStyled(layoutButton, canvasView.getItemCount() > 0);
        setEnabledStyled(arrangeButton, canvasView.getItemCount() > 0);
        setEnabledStyled(undoButton, !undo.isEmpty());
        setEnabledStyled(redoButton, !redo.isEmpty());
        if (zoomPercentButton != null) zoomPercentButton.setText(canvasView.getZoomPercent() + "%");
        updateStatus();
    }

    private void setEnabledStyled(Button b, boolean enabled) {
        if (b == null) return;
        b.setEnabled(enabled);
        applyButtonBackground(b, 0xFFFFFFFF, enabled);
    }

    private void updateStatus() {
        if (statusText == null || canvasView == null) return;
        String orientation = canvasView.isPortrait() ? "Portrait" : "Landscape";
        SharedPreferences prefs = getSharedPreferences("printer", MODE_PRIVATE);
        boolean keepAlive = prefs.getBoolean("keep_alive_enabled", true);
        int interval = Math.max(15, prefs.getInt("interval_minutes", DEFAULT_KEEPALIVE_MINUTES));
        String host = prefs.getString("host", DEFAULT_PRINTER_HOST);
        String intervalText = (interval % 60 == 0) ? ((interval / 60) + "h") : (interval + "m");
        String printerState = DEFAULT_PRINTER_NAME + " • " + host + " • " + (keepAlive ? ("Keep Awake " + intervalText + " • Auto IP") : "Keep Awake Off");
        String selection = canvasView.getSelectionCount() > 1 ? (canvasView.getSelectionCount() + " selected") : canvasView.selectedSizeText();
        statusText.setText(selection + "   •   " + orientation + " Letter   •   Zoom " + canvasView.getZoomPercent() + "%   •   Autosaved\n" + printerState);
        if (zoomPercentButton != null) zoomPercentButton.setText(canvasView.getZoomPercent() + "%");
    }

    private void persist() {
        if (canvasView == null) return;
        SheetStore.save(this, canvasView.toJson());
    }

    @Override public void onSelectionChanged(boolean hasSelection) {
        updateControls();
    }

    @Override public void onEditStarted() {
        pushUndo();
        redo.clear();
        updateControls();
    }

    @Override public void onSheetChanged() {
        persist();
        updateControls();
    }

    @Override protected void onPause() {
        super.onPause();
        persist();
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Diagnostics.log("DEVICE_ROTATED", "orientation=" + newConfig.orientation);
        if (canvasView != null) {
            String sheet = canvasView.toJson();
            setContentView(buildUi());
            canvasView.fromJson(sheet);
            updateControls();
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingShare(intent);
    }

    private void handleIncomingShare(Intent intent) {
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

    private Uri copyImageToPrivateStorage(Uri source) {
        if (source == null) return null;
        try {
            File dir = new File(getFilesDir(), "imported-labels");
            if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Could not create label storage");
            File outFile = new File(dir, "label-" + UUID.randomUUID() + ".img");
            try (InputStream in = getContentResolver().openInputStream(source);
                 FileOutputStream out = new FileOutputStream(outFile)) {
                if (in == null) throw new IllegalStateException("Could not open selected image");
                byte[] buffer = new byte[32 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                out.flush();
            }
            Diagnostics.log("IMAGE_COPIED_LOCAL", "private copy created for reliable autosave");
            return Uri.fromFile(outFile);
        } catch (Exception e) {
            Diagnostics.error("IMAGE_LOCAL_COPY_FAILED", e);
            return null;
        }
    }

    private void showToast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
