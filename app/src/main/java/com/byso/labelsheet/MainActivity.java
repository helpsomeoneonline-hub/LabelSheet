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
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.Menu;
import android.view.SubMenu;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/**
 * LabelSheet 1.9 — canvas-first editor.
 * Phones (portrait): top bar + page + one row of tools along the bottom.
 * Tablets and landscape: top bar + tool rail on the left + page.
 * Tools change with what is selected, so only relevant buttons are shown.
 */
public class MainActivity extends Activity implements LetterCanvasView.Listener {
    private static final int REQ_ADD = 1001;
    private static final int REQ_REPLACE = 1002;
    private static final int REQ_EXPORT_PDF = 1003;
    private static final int REQ_EXPORT_LOG = 1004;
    private static final int PRINTER_KEEPALIVE_JOB_ID = 12015;
    private static final String DEFAULT_PRINTER_NAME = "Canon TS6500 series";
    private static final String DEFAULT_PRINTER_HOST = "192.168.58.100";
    private static final int DEFAULT_KEEPALIVE_MINUTES = 180;

    private LetterCanvasView canvasView;
    private FrameLayout canvasHost;
    private LinearLayout tools;
    private boolean railLayout;
    private TextView subtitle;
    private ImageButton undoButton, redoButton;
    private TextView zoomLabel;
    private View undoBar;
    private String toolsKey = "";
    private View lastTile;
    private String copiedFormatJson = null;
    private final Deque<String> undo = new ArrayDeque<>();
    private final Deque<String> redo = new ArrayDeque<>();
    private boolean restoringHistory = false;
    private volatile boolean printPreparing = false;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Diagnostics.init(this);
        Theme.load(this);
        initializePrinterDefaults();
        setContentView(buildUi());
        restorePrinterKeepAliveSchedule();

        String saved = SheetStore.load(this);
        if (saved != null) canvasView.fromJson(saved);
        updateControls();
        handleIncomingShare(getIntent());
    }

    // ───────────────────────────── Layout ─────────────────────────────

    private View buildUi() {
        Configuration cfg = getResources().getConfiguration();
        railLayout = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE || cfg.smallestScreenWidthDp >= 600;
        toolsKey = "";
        applyWindowColors();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.SURFACE);
        // Strip behind the status bar (clock/battery) in the top-bar colour, sized to the real inset.
        View statusStrip = new View(this);
        statusStrip.setBackgroundColor(Theme.lighter(Theme.BAR, 0.16f));
        root.addView(statusStrip, new LinearLayout.LayoutParams(-1, 0));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), 0,
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            LinearLayout.LayoutParams sp = (LinearLayout.LayoutParams) statusStrip.getLayoutParams();
            if (sp.height != insets.getSystemWindowInsetTop()) { sp.height = insets.getSystemWindowInsetTop(); statusStrip.setLayoutParams(sp); }
            return insets;
        });
        root.requestApplyInsets();

        canvasView = new LetterCanvasView(this);
        canvasView.setBackgroundColor(Theme.WORKSPACE);
        canvasView.setDefaultNewImageBorder(getSharedPreferences("editor", MODE_PRIVATE)
                .getBoolean("auto_border", true) ? 2f : 0f);
        canvasView.setListener(this);
        canvasHost = new FrameLayout(this);
        canvasHost.addView(canvasView, new FrameLayout.LayoutParams(-1, -1));
        canvasHost.addView(buildZoomPill(), zoomPillParams());

        View top = buildTopBar();
        top.setElevation(dp(6)); // glossy bar casts a shadow onto the page area
        root.addView(top, new LinearLayout.LayoutParams(-1, dp(62)));

        tools = new LinearLayout(this);
        if (railLayout) {
            LinearLayout body = new LinearLayout(this);
            body.setOrientation(LinearLayout.HORIZONTAL);
            tools.setOrientation(LinearLayout.VERTICAL);
            tools.setPadding(dp(10), dp(12), dp(10), dp(12));
            ScrollView rail = new ScrollView(this);
            rail.setBackground(Depth.panel(true));
            rail.setVerticalScrollBarEnabled(false);
            rail.setElevation(dp(4));
            rail.addView(tools, new FrameLayout.LayoutParams(-1, -2));
            body.addView(rail, new LinearLayout.LayoutParams(dp(cfg.smallestScreenWidthDp >= 600 ? 108 : 96), -1));
            body.addView(canvasHost, new LinearLayout.LayoutParams(0, -1, 1f));
            root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));
        } else {
            root.addView(canvasHost, new LinearLayout.LayoutParams(-1, 0, 1f));
            tools.setOrientation(LinearLayout.HORIZONTAL);
            tools.setPadding(dp(8), dp(9), dp(8), dp(10));
            HorizontalScrollView bar = new HorizontalScrollView(this);
            bar.setBackground(Depth.panel(false));
            bar.setElevation(dp(6));
            bar.setHorizontalScrollBarEnabled(false);
            bar.setFillViewport(true);
            bar.addView(tools, new FrameLayout.LayoutParams(-2, -1));
            root.addView(bar, new LinearLayout.LayoutParams(-1, dp(86)));
        }
        return root;
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(Depth.glossyBar(Theme.BAR));
        bar.setPadding(dp(16), 0, dp(10), 0);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("LabelSheet");
        title.setTextColor(Theme.ON_BAR);
        title.setTextSize(20);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        if (Theme.BAR_DARK) title.setShadowLayer(dp(2), 0, dp(1), 0x59000000);
        subtitle = new TextView(this);
        subtitle.setTextColor((Theme.ON_BAR & 0x00FFFFFF) | 0xD9000000);
        subtitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        subtitle.setTextSize(12);
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titles.addView(title);
        titles.addView(subtitle);
        bar.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));

        undoButton = iconButton(R.drawable.ic_undo, "Undo", v -> undo());
        redoButton = iconButton(R.drawable.ic_redo, "Redo", v -> redo());
        ImageButton overflow = iconButton(R.drawable.ic_more_vert, "More options", this::showOverflowMenu);
        bar.addView(undoButton);
        bar.addView(redoButton);
        bar.addView(overflow);

        Button print = new Button(this);
        print.setText("Print");
        print.setCompoundDrawablePadding(dp(8));
        print.setAllCaps(false);
        print.setTextSize(15);
        print.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        print.setTextColor(Theme.ON_PRIMARY);
        android.graphics.drawable.Drawable pi = getDrawable(R.drawable.ic_print).mutate();
        pi.setTint(Theme.ON_PRIMARY);
        pi.setBounds(0, 0, dp(20), dp(20));
        print.setCompoundDrawables(pi, null, null, null);
        print.setPadding(dp(16), 0, dp(18), 0);
        print.setMinHeight(0); print.setMinimumHeight(0); print.setMinWidth(0); print.setMinimumWidth(0);
        print.setStateListAnimator(null);
        print.setBackground(Depth.raised(Theme.lighter(Theme.PRIMARY, 0.35f), Theme.PRIMARY,
                Theme.darker(Theme.PRIMARY, 0.30f), dp(23), dp(3), true));
        print.setElevation(dp(5));
        print.setContentDescription("Print sheet. Long-press for printer settings.");
        print.setOnClickListener(v -> { UsageInsights.record(this, "Print"); printSheet(); });
        print.setOnLongClickListener(v -> { showPrinterSettingsDialog(); return true; });
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-2, dp(46));
        pp.leftMargin = dp(10);
        bar.addView(print, pp);
        return bar;
    }

    private View buildZoomPill() {
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(Depth.raised(Theme.TILE_TOP, Theme.TILE_BOTTOM, Theme.TILE_LIP, dp(23), dp(3), false));
        pill.setElevation(dp(6));
        pill.setPadding(0, 0, 0, dp(3));
        TextView minus = zoomText("−", "Zoom out");
        minus.setOnClickListener(v -> canvasView.zoomBy(0.8f));
        zoomLabel = zoomText("100%", "Fit page and side area. Long-press to zoom to the selected picture.");
        zoomLabel.setTextSize(13);
        zoomLabel.setMinWidth(dp(58));
        zoomLabel.setOnClickListener(v -> canvasView.fitPage());
        zoomLabel.setOnLongClickListener(v -> { if (!canvasView.fitSelection()) showToast("Select a picture first."); return true; });
        TextView plus = zoomText("+", "Zoom in");
        plus.setOnClickListener(v -> canvasView.zoomBy(1.25f));
        pill.addView(minus, new LinearLayout.LayoutParams(dp(44), dp(44)));
        pill.addView(zoomLabel, new LinearLayout.LayoutParams(-2, dp(44)));
        pill.addView(plus, new LinearLayout.LayoutParams(dp(44), dp(44)));
        return pill;
    }

    private FrameLayout.LayoutParams zoomPillParams() {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        p.setMargins(0, dp(10), dp(10), 0);
        return p;
    }

    private TextView zoomText(String s, String description) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(Theme.TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextSize(20);
        t.setContentDescription(description);
        t.setBackground(ripple(22));
        t.setClickable(true);
        return t;
    }

    // ─────────────────────────── Contextual tools ───────────────────────────

    /** Rebuilds the tool row/rail only when the editing context actually changes. */
    private void refreshTools() {
        if (tools == null || canvasView == null) return;
        boolean multi = canvasView.isMultiSelectMode();
        boolean sel = canvasView.hasSelection();
        boolean locked = sel && canvasView.isSelectedLocked();
        if (canvasView.isCropMode()) {
            if ("crop".equals(toolsKey)) return;
            toolsKey = "crop";
            tools.removeAllViews();
            section("Crop");
            tile(R.drawable.ic_check, "Done", ToolTile.STYLE_PRIMARY, true, false, this::applyCrop);
            tile(R.drawable.ic_reset, "Reset", 0, true, false, () -> canvasView.resetCrop());
            tile(R.drawable.ic_close, "Cancel", 0, true, false, this::cancelCrop);
            return;
        }
        String key = multi + "|" + sel + "|" + locked + "|" + canvasView.getSelectionCount() + "|"
                + canvasView.hasBorderOnSelected() + "|" + canvasView.isSelectedOffPage() + "|"
                + canvasView.isPortrait() + "|" + (canvasView.getItemCount() > 0) + "|" + (copiedFormatJson != null);
        if (key.equals(toolsKey)) return;
        toolsKey = key;
        tools.removeAllViews();

        if (multi) {
            int n = canvasView.getSelectionCount();
            int editable = canvasView.countEditableSelected();
            section(n == 0 ? "Tap pictures" : n + " selected");
            tile(R.drawable.ic_check, "Done", ToolTile.STYLE_PRIMARY, true, false, () -> {
                canvasView.setMultiSelectMode(false);
                canvasView.clearSelection();
            });
            tile(R.drawable.ic_delete, "Delete", ToolTile.STYLE_DANGER, editable > 0, false, this::deleteSelection);
            tile(R.drawable.ic_select, "All", 0, canvasView.getItemCount() > 0, false, canvasView::selectAll);
            tile(R.drawable.ic_align, "Align", 0, n >= 2, false, this::showAlignMenu);
            tile(R.drawable.ic_space, "Space", 0, n >= 3, false, this::showSpaceMenu);
            tile(R.drawable.ic_border, "Border", 0, editable > 0, false, this::showBulkBorderMenu);
            tile(R.drawable.ic_rotate, "Rotate", 0, editable > 0, false,
                    () -> performBulkEdit(canvasView::applyBulkRotation90, "Rotation"));
            tile(R.drawable.ic_size, "Size", 0, editable > 0, false, this::showExactSizeDialog);
            tile(R.drawable.ic_park, canvasView.isSelectedOffPage() ? "On page" : "Off page", 0, editable > 0, false, this::toggleParking);
            tile(R.drawable.ic_more_horiz, "More", 0, n > 0, false, this::showMultiMoreMenu);
            return;
        }

        if (!sel) {
            section("Page");
            tile(R.drawable.ic_add, "Add", ToolTile.STYLE_PRIMARY, true, false, () -> chooseImage(false));
            boolean any = canvasView.getItemCount() > 0;
            tile(R.drawable.ic_arrange, "Arrange", 0, any, false, this::autoArrange);
            tile(R.drawable.ic_select, "Select", 0, any, false, () -> canvasView.setMultiSelectMode(true));
            boolean portrait = canvasView.isPortrait();
            tile(portrait ? R.drawable.ic_page_landscape : R.drawable.ic_page_portrait,
                    "Turn page", 0, true, false, this::toggleOrientation);
            return;
        }

        boolean free = !locked;
        section("Picture");
        tile(R.drawable.ic_add, "Add", ToolTile.STYLE_PRIMARY, true, false, () -> chooseImage(false));
        tile(R.drawable.ic_crop, "Crop", 0, free, false, this::startCrop);
        ToolTile border = tile(R.drawable.ic_border, "Border", 0, free, canvasView.hasBorderOnSelected(), this::toggleBorder);
        border.setOnLongClickListener(v -> { showBorderDialog(); return true; });
        tile(R.drawable.ic_rotate, "Rotate", 0, free, false,
                () -> simpleEdit(canvasView::rotateSelected));
        tile(R.drawable.ic_size, "Size", 0, free, false, this::showExactSizeDialog);
        tile(R.drawable.ic_copy, "Duplicate", 0, true, false,
                () -> simpleEdit(canvasView::duplicateSelected));
        tile(R.drawable.ic_park, canvasView.isSelectedOffPage() ? "On page" : "Off page", 0, free, false, this::toggleParking);
        tile(locked ? R.drawable.ic_lock : R.drawable.ic_lock_open, locked ? "Locked" : "Lock", 0, true, locked, this::toggleLock);
        tile(R.drawable.ic_more_horiz, "More", 0, true, false, this::showPictureMoreMenu);
        tile(R.drawable.ic_delete, "Delete", ToolTile.STYLE_DANGER, free, false, this::deleteSelection);
    }

    private ToolTile tile(int icon, String label, int style, boolean enabled, boolean active, Runnable action) {
        ToolTile t = new ToolTile(this, icon, label).style(style).active(active);
        t.setEnabled(enabled);
        t.setOnClickListener(v -> {
            lastTile = v;
            UsageInsights.record(getApplicationContext(), label);
            action.run();
        });
        LinearLayout.LayoutParams lp = railLayout
                ? new LinearLayout.LayoutParams(-1, dp(70))
                : new LinearLayout.LayoutParams(dp(64), -1);
        if (railLayout) lp.bottomMargin = dp(10); else lp.rightMargin = dp(8);
        tools.addView(t, lp);
        return t;
    }

    /** Small heading. Shown only on the rail, where there is room. */
    private void section(String text) {
        if (!railLayout) return;
        TextView t = new TextView(this);
        t.setText(text.toUpperCase(java.util.Locale.US));
        t.setTextColor(Theme.HILITE);
        t.setTextSize(10.5f);
        t.setLetterSpacing(0.06f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(2), 0, dp(8));
        tools.addView(t, new LinearLayout.LayoutParams(-1, -2));
    }

    // ───────────────────────────── Actions ─────────────────────────────

    private interface BoolAction { boolean run(); }
    private interface BulkOperation { int apply(); }

    /** One undo step around a single canvas edit. */
    private void simpleEdit(BoolAction action) {
        pushUndo();
        if (action.run()) redo.clear(); else { undo.pollFirst(); showToast("That picture is locked. Unlock it first."); }
        updateControls();
    }

    private void performLayout(BoolAction action) {
        pushUndo();
        if (action.run()) { redo.clear(); updateControls(); }
        else { undo.pollFirst(); showToast("That needs more unlocked pictures selected."); }
    }

    private void performBulkEdit(BulkOperation operation, String name) {
        if (canvasView.countEditableSelected() < 1) { showToast("Select one or more unlocked pictures first."); return; }
        pushUndo();
        int changed = operation.apply();
        if (changed > 0) { redo.clear(); showUndoBar(name + " applied to " + changed + (changed == 1 ? " picture" : " pictures")); }
        else { undo.pollFirst(); showToast("No pictures changed."); }
        updateControls();
    }

    private void autoArrange() {
        if (canvasView.getOnPageCount() == 0) { showToast("Nothing on the page to arrange."); return; }
        pushUndo();
        canvasView.autoArrange();
        redo.clear();
        showUndoBar("Pictures arranged");
        updateControls();
    }

    private void toggleOrientation() {
        pushUndo();
        canvasView.toggleOrientation();
        redo.clear();
        canvasView.fitPage();
        updateControls();
    }

    // On-page crop: drag the corner/edge grips on the picture itself; no dialog.
    private boolean cropUndoPushed = false;

    private void startCrop() {
        hideUndoBar();
        int before = undo.size();
        pushUndo();
        cropUndoPushed = undo.size() > before;
        if (!canvasView.startCrop()) {
            if (cropUndoPushed) undo.pollFirst();
            cropUndoPushed = false;
            showToast("Select an unlocked picture first.");
        }
    }

    private void applyCrop() { canvasView.finishCrop(true); }

    private void cancelCrop() { canvasView.finishCrop(false); }

    /** Called however crop mode ends (Done, Cancel, Back, or tapping outside the picture). */
    @Override public void onCropModeChanged(boolean cropping) {
        if (!cropping && cropUndoPushed) {
            // Keep one undo step only if the crop actually changed something.
            if (!undo.isEmpty() && undo.peekFirst().equals(canvasView.toJson())) undo.pollFirst();
            else redo.clear();
            cropUndoPushed = false;
        }
        updateControls();
    }

    private void toggleBorder() {
        boolean has = canvasView.hasBorderOnSelected();
        pushUndo();
        if (canvasView.setSelectedBorderPt(has ? 0f : 2f)) {
            redo.clear();
            Diagnostics.log("BORDER_TOGGLED", has ? "off" : "2pt");
            if (!has) showToast("2 pt border on. Long-press Border to change thickness.");
        } else { undo.pollFirst(); showToast("That picture is locked. Unlock it first."); }
        updateControls();
    }

    private void toggleParking() {
        pushUndo();
        int r = canvasView.toggleSelectedParking();
        if (r == 0) { undo.pollFirst(); showToast("Unlock the picture first."); updateControls(); return; }
        redo.clear();
        if (r == 1) {
            canvasView.fitPage();
            showUndoBar("Moved off the page — it won't print");
        } else showUndoBar("Placed on the page");
        updateControls();
    }

    private void toggleLock() {
        if (!canvasView.hasSelection()) return;
        pushUndo();
        if (canvasView.toggleLockSelected()) redo.clear(); else undo.pollFirst();
        updateControls();
    }

    private void deleteSelection() {
        int n = Math.max(1, canvasView.countEditableSelected());
        pushUndo();
        if (canvasView.deleteSelected()) {
            redo.clear();
            if (canvasView.isMultiSelectMode()) canvasView.setMultiSelectMode(false);
            showUndoBar(n == 1 ? "Picture deleted" : n + " pictures deleted");
        } else { undo.pollFirst(); showToast("Locked pictures can't be deleted."); }
        updateControls();
    }

    private void copyFormat() {
        copiedFormatJson = canvasView.getSelectedFormatJson();
        if (copiedFormatJson == null) showToast("Select a picture first.");
        else { Diagnostics.log("FORMAT_COPIED", canvasView.selectedSizeText()); showToast("Style copied. Select another picture → More → Paste style."); }
        toolsKey = "";
        updateControls();
    }

    private void pasteFormat() {
        if (copiedFormatJson == null) { showToast("Copy a picture's style first."); return; }
        if (canvasView.getSelectionCount() > 1) { performBulkEdit(() -> canvasView.applyBulkFormatJson(copiedFormatJson), "Style"); return; }
        pushUndo();
        if (canvasView.applyFormatJson(copiedFormatJson)) { redo.clear(); showToast("Style pasted."); }
        else { undo.pollFirst(); showToast("Select an unlocked picture first."); }
        updateControls();
    }

    // ───────────────────────────── Menus ─────────────────────────────

    private void showPictureMoreMenu() {
        View anchor = toolAnchor();
        PopupMenu m = new PopupMenu(this, anchor);
        Menu menu = m.getMenu();
        boolean free = !canvasView.isSelectedLocked();
        menu.add(0, 1, 0, "Replace picture…").setEnabled(free);
        menu.add(0, 2, 0, "Border thickness…").setEnabled(free);
        menu.add(0, 3, 0, "Copy style");
        menu.add(0, 4, 0, "Paste style").setEnabled(free && copiedFormatJson != null);
        menu.add(0, 5, 0, "Bring forward");
        menu.add(0, 6, 0, "Send backward");
        menu.add(0, 7, 0, "Nudge…").setEnabled(free);
        SubMenu copies = menu.addSubMenu(0, 8, 0, "Make copies");
        copies.add(0, 81, 0, "2 copies");
        copies.add(0, 82, 0, "4 copies");
        copies.add(0, 83, 0, "Fill the page with this");
        menu.add(0, 9, 0, "Zoom to picture");
        m.setOnMenuItemClickListener(item -> {
            UsageInsights.record(this, String.valueOf(item.getTitle()));
            switch (item.getItemId()) {
                case 1: chooseImage(true); break;
                case 2: showBorderDialog(); break;
                case 3: copyFormat(); break;
                case 4: pasteFormat(); break;
                case 5: performLayout(canvasView::bringSelectedForward); break;
                case 6: performLayout(canvasView::sendSelectedBackward); break;
                case 7: showNudgeDialog(); break;
                case 81: performLayout(() -> canvasView.duplicateSelectedCopies(2)); break;
                case 82: performLayout(() -> canvasView.duplicateSelectedCopies(4)); break;
                case 83: confirmFillSheet(); break;
                case 9: canvasView.fitSelection(); break;
                default: return false;
            }
            return true;
        });
        m.show();
    }

    private void showMultiMoreMenu() {
        PopupMenu m = new PopupMenu(this, toolAnchor());
        Menu menu = m.getMenu();
        menu.add(0, 1, 0, "Paste style to selected").setEnabled(copiedFormatJson != null);
        menu.add(0, 2, 0, "Lock / unlock selected");
        menu.add(0, 3, 0, "Nudge…");
        m.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: pasteFormat(); break;
                case 2: toggleLock(); break;
                case 3: showNudgeDialog(); break;
                default: return false;
            }
            return true;
        });
        m.show();
    }

    private void showAlignMenu() {
        PopupMenu m = new PopupMenu(this, toolAnchor());
        String[] labels = {"Left edges", "Centers (across)", "Right edges", "Top edges", "Middles (down)", "Bottom edges"};
        String[] modes = {"left", "center", "right", "top", "middle", "bottom"};
        for (int i = 0; i < labels.length; i++) m.getMenu().add(0, i, i, labels[i]);
        m.setOnMenuItemClickListener(item -> { performLayout(() -> canvasView.alignSelected(modes[item.getItemId()])); return true; });
        m.show();
    }

    private void showSpaceMenu() {
        PopupMenu m = new PopupMenu(this, toolAnchor());
        m.getMenu().add(0, 0, 0, "Even spacing across");
        m.getMenu().add(0, 1, 1, "Even spacing down");
        m.setOnMenuItemClickListener(item -> { performLayout(() -> canvasView.distributeSelected(item.getItemId() == 0)); return true; });
        m.show();
    }

    private void showBulkBorderMenu() {
        PopupMenu m = new PopupMenu(this, toolAnchor());
        m.getMenu().add(0, 0, 0, "2 pt black border");
        m.getMenu().add(0, 1, 1, "Remove borders");
        m.setOnMenuItemClickListener(item -> {
            float pt = item.getItemId() == 0 ? 2f : 0f;
            performBulkEdit(() -> canvasView.applyBulkBorder(pt), pt > 0 ? "Border" : "Border removal");
            return true;
        });
        m.show();
    }

    private void showOverflowMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        Menu menu = m.getMenu();
        boolean any = canvasView.getItemCount() > 0;
        menu.add(0, 1, 0, "Save page as template…").setEnabled(any);
        menu.add(0, 2, 0, "Open template…");
        menu.add(0, 3, 0, "Save page as PDF…").setEnabled(any);
        menu.add(0, 4, 0, "Check printer");
        menu.add(0, 5, 0, "Printer settings…");
        menu.add(0, 10, 0, "Color scheme…");
        menu.add(0, 6, 0, "New picture defaults…");
        SubMenu diag = menu.addSubMenu(0, 7, 0, "Troubleshooting");
        diag.add(0, 71, 0, "Copy diagnostic log");
        diag.add(0, 72, 0, "Save diagnostic log…");
        diag.add(0, 73, 0, "My tool usage");
        menu.add(0, 8, 0, "Clear page…").setEnabled(any);
        menu.add(0, 9, 0, "Help");
        m.setOnMenuItemClickListener(item -> {
            UsageInsights.record(this, String.valueOf(item.getTitle()));
            switch (item.getItemId()) {
                case 1: saveTemplateDialog(); break;
                case 2: loadTemplateDialog(); break;
                case 3: exportPdf(); break;
                case 4: wakePrinter(); break;
                case 5: showPrinterSettingsDialog(); break;
                case 6: showEditorPreferences(); break;
                case 10: showSchemePicker(); break;
                case 71: copyLog(); break;
                case 72: exportLog(); break;
                case 73: showUsageInsights(); break;
                case 8: confirmClear(); break;
                case 9: showAbout(); break;
                default: return false;
            }
            return true;
        });
        m.show();
    }

    /** Popups open next to the tile that was just tapped. */
    private View toolAnchor() {
        if (lastTile != null && lastTile.getParent() == tools) return lastTile;
        return tools.getChildCount() > 0 ? tools.getChildAt(tools.getChildCount() - 1) : canvasView;
    }

    // ───────────────────────────── Dialogs ─────────────────────────────

    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, Theme.DARK ? android.R.style.Theme_Material_Dialog_Alert
                : android.R.style.Theme_Material_Light_Dialog_Alert);
    }

    private void showExactSizeDialog() {
        if (!canvasView.hasSelection()) { showToast("Select a picture first."); return; }
        float[] size = canvasView.getSelectedSizeInches();
        final float ratio = size[1] > 0 ? size[0] / size[1] : 1f;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(4), dp(22), 0);
        EditText w = numberField("Width (inches)", size[0]);
        EditText h = numberField("Height (inches)", size[1]);
        CheckBox keep = new CheckBox(this);
        keep.setText("Keep proportions");
        keep.setChecked(true);
        final boolean[] syncing = {false};
        w.addTextChangedListener(new SimpleWatcher(() -> {
            if (!keep.isChecked() || syncing[0] || !w.hasFocus()) return;
            try { syncing[0] = true; h.setText(fmt(Float.parseFloat(w.getText().toString()) / ratio)); } catch (Exception ignored) {} finally { syncing[0] = false; }
        }));
        h.addTextChangedListener(new SimpleWatcher(() -> {
            if (!keep.isChecked() || syncing[0] || !h.hasFocus()) return;
            try { syncing[0] = true; w.setText(fmt(Float.parseFloat(h.getText().toString()) * ratio)); } catch (Exception ignored) {} finally { syncing[0] = false; }
        }));
        box.addView(label("Width (in)")); box.addView(w);
        box.addView(label("Height (in)")); box.addView(h);
        box.addView(keep);
        int count = Math.max(1, canvasView.countEditableSelected());
        dialog().setTitle(count > 1 ? "Printed size for " + count + " pictures" : "Printed size")
                .setView(box).setNegativeButton("Cancel", null)
                .setPositiveButton("Apply", (d, which) -> {
                    try {
                        float wi = Float.parseFloat(w.getText().toString());
                        float hi = Float.parseFloat(h.getText().toString());
                        pushUndo();
                        if (canvasView.applyBulkSizeInches(wi, hi) > 0) redo.clear();
                        else { undo.pollFirst(); showToast("Enter a size that fits on the page."); }
                        updateControls();
                    } catch (Exception e) { showToast("Enter a valid width and height."); }
                }).show();
    }

    private EditText numberField(String hint, float value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setText(fmt(value));
        e.setSelectAllOnFocus(true);
        return e;
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Theme.TEXT_SOFT);
        t.setTextSize(12);
        t.setPadding(dp(4), dp(10), 0, 0);
        return t;
    }

    private static String fmt(float v) { return String.format(java.util.Locale.US, "%.2f", v); }

    private static final class SimpleWatcher implements TextWatcher {
        private final Runnable r;
        SimpleWatcher(Runnable r) { this.r = r; }
        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void afterTextChanged(Editable s) { r.run(); }
    }

    private void showNudgeDialog() {
        if (!canvasView.hasSelection()) { showToast("Select a picture first."); return; }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(20), dp(6), dp(20), dp(6));
        TextView note = label("Each tap moves 0.02 inch");
        note.setGravity(Gravity.CENTER);
        box.addView(note);
        LinearLayout r1 = new LinearLayout(this); r1.setGravity(Gravity.CENTER);
        r1.addView(nudgeButton("↑", 0f, -0.02f));
        LinearLayout r2 = new LinearLayout(this); r2.setGravity(Gravity.CENTER);
        r2.addView(nudgeButton("←", -0.02f, 0f));
        View gap = new View(this); r2.addView(gap, new LinearLayout.LayoutParams(dp(56), dp(56)));
        r2.addView(nudgeButton("→", 0.02f, 0f));
        LinearLayout r3 = new LinearLayout(this); r3.setGravity(Gravity.CENTER);
        r3.addView(nudgeButton("↓", 0f, 0.02f));
        box.addView(r1); box.addView(r2); box.addView(r3);
        dialog().setTitle("Nudge").setView(box).setPositiveButton("Done", null).show();
    }

    private View nudgeButton(String arrow, float dx, float dy) {
        TextView b = new TextView(this);
        b.setText(arrow);
        b.setTextSize(22);
        b.setTextColor(Theme.TEXT);
        b.setGravity(Gravity.CENTER);
        b.setBackground(pill(Theme.BACKGROUND, Theme.OUTLINE, 14));
        b.setClickable(true);
        b.setOnClickListener(v -> { pushUndo(); if (canvasView.nudgeSelected(dx, dy)) redo.clear(); else undo.pollFirst(); updateControls(); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(56), dp(56));
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        b.setLayoutParams(lp);
        return b;
    }

    private void confirmFillSheet() {
        dialog().setTitle("Fill the page with copies?")
                .setMessage("Pictures on the page are replaced by copies of this one. Pictures off the page and locked pictures stay. You can undo this.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Fill page", (d, w) -> {
                    performLayout(canvasView::fillSheetWithSelected);
                    showUndoBar("Page filled with copies");
                }).show();
    }

    private void saveTemplateDialog() {
        if (canvasView.getItemCount() == 0) { showToast("There is no layout to save."); return; }
        EditText input = new EditText(this);
        input.setHint("Template name"); input.setSingleLine(true); input.setText("My layout"); input.setSelectAllOnFocus(true);
        FrameLayout wrap = new FrameLayout(this); wrap.setPadding(dp(22), dp(6), dp(22), 0); wrap.addView(input);
        dialog().setTitle("Save page as template").setView(wrap).setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) name = "My layout";
                    getSharedPreferences("templates", MODE_PRIVATE).edit().putString(name, canvasView.toJson()).apply();
                    Diagnostics.log("TEMPLATE_SAVED", name);
                    showToast("Template saved: " + name);
                }).show();
    }

    private void loadTemplateDialog() {
        java.util.Map<String, ?> all = getSharedPreferences("templates", MODE_PRIVATE).getAll();
        if (all.isEmpty()) { showToast("No saved templates yet. Use ⋮ → Save page as template."); return; }
        String[] names = all.keySet().toArray(new String[0]);
        java.util.Arrays.sort(names);
        dialog().setTitle("Open template").setItems(names, (d, which) -> {
            Object val = all.get(names[which]);
            if (val instanceof String) {
                pushUndo();
                canvasView.fromJson((String) val);
                redo.clear();
                persist();
                Diagnostics.log("TEMPLATE_LOADED", names[which]);
                showUndoBar("Template opened");
                updateControls();
            }
        }).setNegativeButton("Cancel", null).show();
    }

    private void showEditorPreferences() {
        SharedPreferences prefs = getSharedPreferences("editor", MODE_PRIVATE);
        CheckBox border = new CheckBox(this);
        border.setText("Give every new picture a 2 pt black border");
        border.setChecked(prefs.getBoolean("auto_border", true));
        FrameLayout wrap = new FrameLayout(this); wrap.setPadding(dp(18), dp(8), dp(18), 0); wrap.addView(border);
        dialog().setTitle("New picture defaults").setView(wrap)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, w) -> {
                    prefs.edit().putBoolean("auto_border", border.isChecked()).apply();
                    canvasView.setDefaultNewImageBorder(border.isChecked() ? 2f : 0f);
                    Diagnostics.log("EDITOR_PREFS_CHANGED", "auto_border=" + border.isChecked());
                }).show();
    }

    private void showUsageInsights() {
        dialog().setTitle("My tool usage")
                .setMessage(UsageInsights.report(this))
                .setNegativeButton("Close", null)
                .setNeutralButton("Reset counts", (d, w) ->
                        dialog().setTitle("Reset usage counts?")
                                .setMessage("This does not change any saved labels.")
                                .setNegativeButton("Cancel", null)
                                .setPositiveButton("Reset", (q, k) -> UsageInsights.clear(this)).show())
                .setPositiveButton("Copy", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("LabelSheet usage", UsageInsights.report(this)));
                    showToast("Usage report copied.");
                }).show();
    }

    private void showBorderDialog() {
        if (!canvasView.hasSelection()) return;
        float current = canvasView.getSelectedBorderPt();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(8), dp(22), 0);
        TextView value = new TextView(this);
        value.setTextColor(Theme.TEXT);
        value.setTextSize(15);
        value.setText(current < 0.5f ? "Border: Off" : String.format(java.util.Locale.US, "Thickness: %.0f pt", current));
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
        dialog().setTitle("Border thickness")
                .setMessage("Black outline. Slide to 0 to remove it.")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Apply", (d, w) -> {
                    pushUndo();
                    if (canvasView.setSelectedBorderPt(seek.getProgress())) redo.clear(); else undo.pollFirst();
                    updateControls();
                })
                .show();
    }

    private void confirmClear() {
        if (canvasView.getItemCount() == 0) return;
        dialog().setTitle("Clear the page?")
                .setMessage("Removes every picture, including ones off the page. You can undo right after.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", (d, w) -> {
                    pushUndo();
                    canvasView.clearAll();
                    redo.clear();
                    showUndoBar("Page cleared");
                    updateControls();
                })
                .show();
    }

    private void showAbout() {
        dialog().setTitle("LabelSheet 1.9")
                .setMessage("Letter page: 8.5 × 11 in.\n\n"
                        + "• Add places pictures on the page. Tap a picture to see its tools.\n"
                        + "• Drag to move. Corner handles keep proportions; side handles stretch one way.\n"
                        + "• Off page moves a picture into the grey side area (or just drag it there). Anything outside the page is NOT printed — handy for labels you'll use later. Tap On page to bring it back.\n"
                        + "• Pictures crossing the page edge get an orange outline and will be cut off when printed.\n"
                        + "• Long-press a picture to start picking several, then tap others to add them. Delete, align, space, border or size them together.\n"
                        + "• Pinch or use − / + to zoom. Tap the % to fit everything.\n\n"
                        + "Printing: Print wakes the " + DEFAULT_PRINTER_NAME + " (searching 192.168.58.100–110) before opening Android printing. Long-press Print for printer settings. Keep scaling at 100% / Actual size for exact label sizes.\n\n"
                        + "Troubleshooting: ⋮ → Troubleshooting → Copy diagnostic log. The log records actions and errors, never your label images.")
                .setPositiveButton("OK", null)
                .show();
    }

    // ───────────────────────────── Images ─────────────────────────────

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
                        Uri local = copyImageToPrivateStorage(data.getClipData().getItemAt(i).getUri());
                        if (local != null && canvasView.addImage(local)) added++;
                    }
                } else if (uri != null) {
                    Uri local = copyImageToPrivateStorage(uri);
                    if (local != null && canvasView.addImage(local)) added++;
                }
                if (added == 0) { undo.pollFirst(); showToast("I couldn't open that image. Try another screenshot."); }
                else { redo.clear(); if (added > 1) showToast(added + " pictures added."); }
            } else if (requestCode == REQ_REPLACE && uri != null) {
                pushUndo();
                Uri local = copyImageToPrivateStorage(uri);
                if (local == null || !canvasView.replaceSelected(local)) { undo.pollFirst(); showToast("I couldn't replace that picture."); }
                else redo.clear();
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
    }

    // ───────────────────────────── Undo / redo ─────────────────────────────

    private void pushUndo() {
        if (restoringHistory) return;
        String state = canvasView.toJson();
        if (undo.isEmpty() || !state.equals(undo.peekFirst())) undo.addFirst(state);
        while (undo.size() > 40) undo.removeLast();
    }

    private void undo() {
        if (canvasView.isCropMode()) { cancelCrop(); return; }
        if (undo.isEmpty()) return;
        String current = canvasView.toJson();
        String previous = undo.removeFirst();
        redo.addFirst(current);
        restoringHistory = true;
        canvasView.fromJson(previous);
        restoringHistory = false;
        persist();
        hideUndoBar();
        Diagnostics.log("UNDO", "ok");
        updateControls();
    }

    private void redo() {
        if (canvasView.isCropMode()) return;
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
    }

    /** Bottom snackbar with an Undo action, shown after bigger changes. */
    private void showUndoBar(String message) {
        hideUndoBar();
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), 0, dp(4), 0);
        bar.setBackground(Depth.raised(Theme.lighter(Theme.SNACK, 0.12f), Theme.SNACK, Theme.darker(Theme.SNACK, 0.4f), dp(14), dp(2), false));
        bar.setElevation(dp(6));
        TextView t = new TextView(this);
        t.setText(message);
        t.setTextColor(Color.WHITE);
        t.setTextSize(14);
        bar.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView action = new TextView(this);
        action.setText("UNDO");
        action.setTextColor(Theme.SNACK_ACTION);
        action.setTypeface(Typeface.DEFAULT_BOLD);
        action.setGravity(Gravity.CENTER);
        action.setPadding(dp(14), 0, dp(14), 0);
        action.setBackground(ripple(10));
        action.setClickable(true);
        action.setOnClickListener(v -> undo());
        bar.addView(action, new LinearLayout.LayoutParams(-2, dp(48)));
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-1, dp(52), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        p.setMargins(dp(12), 0, dp(12), dp(12));
        if (railLayout) p.width = dp(420);
        canvasHost.addView(bar, p);
        undoBar = bar;
        ui.postDelayed(() -> { if (undoBar == bar) hideUndoBar(); }, 5000);
    }

    private void hideUndoBar() {
        if (undoBar != null && canvasHost != null) canvasHost.removeView(undoBar);
        undoBar = null;
    }

    // ───────────────────────────── Printing ─────────────────────────────

    private void printSheet() {
        if (canvasView.getItemCount() == 0) { showToast("Add at least one picture first."); return; }
        if (canvasView.getOnPageCount() == 0) {
            showToast("Everything is off the page. Move a picture onto the page to print it.");
            return;
        }
        int crossing = canvasView.getCrossingEdgeCount();
        if (crossing > 0) {
            dialog().setTitle("Some labels will be cut off")
                    .setMessage(crossing + (crossing == 1 ? " picture crosses" : " pictures cross")
                            + " the page edge (orange outline). The part outside the page won't print.")
                    .setNegativeButton("Fix first", null)
                    .setPositiveButton("Print anyway", (d, w) -> startPrintPreflight())
                    .show();
            return;
        }
        startPrintPreflight();
    }

    private void startPrintPreflight() {
        if (printPreparing) { showToast("Printer is already being prepared…"); return; }
        printPreparing = true;
        SharedPreferences prefs = getSharedPreferences("printer", MODE_PRIVATE);
        final String preferred = prefs.getString("host", DEFAULT_PRINTER_HOST);
        Diagnostics.log("PRINT_PREFLIGHT_STARTED", "preferred=" + PrinterWake.normalizeHost(preferred) + ", scan=192.168.58.100-110");
        showToast("Waking the printer…");
        new Thread(() -> {
            final PrinterWake.Result result = PrinterWake.reconnectForPrint(preferred, prefs.getBoolean("aggressive_reconnect", true));
            if (result != null && !result.host.equals(PrinterWake.normalizeHost(preferred))) {
                prefs.edit().putString("host", result.host).apply();
                Diagnostics.log("PRINTER_IP_UPDATED", "old=" + PrinterWake.normalizeHost(preferred) + ", new=" + result.host);
            }
            runOnUiThread(() -> {
                if (result != null) {
                    Diagnostics.log("PRINT_SERVICE_REACHABLE", "host=" + result.host + ", port=" + result.port +
                            (result.discovered ? ", discovered=true" : "") + "; physical print outcome is not yet known");
                    // Give Android's print service a moment to rediscover a printer whose DHCP address changed.
                    canvasView.postDelayed(() -> { printPreparing = false; openSystemPrintDialog(result.host); }, 1200);
                } else {
                    Diagnostics.log("PRINT_PRINTER_NOT_FOUND", "searched=192.168.58.100-110; falling back to Android print service");
                    showToast("Printer didn't answer. Opening Android printing anyway.");
                    canvasView.postDelayed(() -> { printPreparing = false; openSystemPrintDialog(null); }, 500);
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
            Diagnostics.log("PRINT_STARTED", "labels=" + canvasView.getOnPageCount() + ", aside=" + canvasView.getOffPageCount()
                    + ", orientation=" + (canvasView.isPortrait() ? "portrait" : "landscape")
                    + (discoveredHost == null ? "" : ", printer=" + discoveredHost));
            pm.print("LabelSheet - Letter", new SheetPrintAdapter(), attrs);
        } catch (Exception e) {
            Diagnostics.error("PRINT_START_FAILED", e);
            showToast("Print couldn't start. Save the diagnostic log if it keeps happening.");
        }
    }

    private final class SheetPrintAdapter extends PrintDocumentAdapter {
        @Override public void onLayout(PrintAttributes oldAttributes, PrintAttributes newAttributes,
                                       CancellationSignal cancellationSignal, LayoutResultCallback callback, Bundle extras) {
            if (cancellationSignal.isCanceled()) { callback.onLayoutCancelled(); return; }
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
                if (cancellationSignal.isCanceled()) { callback.onWriteCancelled(); return; }
                doc = createPdfDocument();
                try (FileOutputStream out = new FileOutputStream(destination.getFileDescriptor())) { doc.writeTo(out); }
                Diagnostics.log("PRINT_PDF_READY", "one Letter page generated successfully");
                callback.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES});
            } catch (Exception e) {
                Diagnostics.error("PRINT_WRITE_FAILED", e);
                callback.onWriteFailed(e.getMessage());
            } finally {
                if (doc != null) doc.close();
            }
        }

        @Override public void onFinish() { Diagnostics.log("PRINT_HANDOFF_FINISHED", "Android print dialog/process finished"); }
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
        if (canvasView.getOnPageCount() == 0) { showToast("Put at least one picture on the page first."); return; }
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
            showToast("PDF saved.");
        } catch (Exception e) {
            Diagnostics.error("PDF_EXPORT_FAILED", e);
            showToast("PDF export failed. The log captured the error.");
        } finally {
            if (doc != null) doc.close();
        }
    }

    // ───────────────────────────── Diagnostics ─────────────────────────────

    private void copyLog() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("LabelSheet diagnostic log", Diagnostics.read()));
            Diagnostics.log("LOG_COPIED", "diagnostic log copied to clipboard");
            showToast("Diagnostic log copied.");
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
            showToast("Diagnostic log saved.");
        } catch (Exception e) {
            Diagnostics.error("LOG_EXPORT_FAILED", e);
            showToast("Couldn't save the diagnostic log.");
        }
    }

    // ───────────────────────────── Printer wake ─────────────────────────────

    private void initializePrinterDefaults() {
        SharedPreferences prefs = getSharedPreferences("printer", MODE_PRIVATE);
        SharedPreferences.Editor e = prefs.edit();
        boolean changed = false;
        if (!prefs.contains("host")) { e.putString("host", DEFAULT_PRINTER_HOST); changed = true; }
        if (!prefs.contains("printer_name")) { e.putString("printer_name", DEFAULT_PRINTER_NAME); changed = true; }
        if (!prefs.contains("keep_alive_enabled")) { e.putBoolean("keep_alive_enabled", true); changed = true; }
        if (!prefs.getBoolean("v14_printer_defaults", false)) {
            // v1.4 changed the default from 15 minutes to 3 hours, including upgrades from older builds.
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
        showToast("Looking for the printer…");
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
                    dialog().setTitle("Printer found")
                            .setMessage(DEFAULT_PRINTER_NAME + "\n" + result.host + " • " + PrinterWake.serviceDescription(result)
                                    + "\n\nThe printer answered on the network. If a page still doesn't come out, check Canon Auto Power On.")
                            .setPositiveButton("Print now", (d, w) -> printSheet())
                            .setNegativeButton("Close", null).show();
                    updateControls();
                } else {
                    Diagnostics.log("PRINTER_WAKE_NO_RESPONSE", "searched=192.168.58.100-110");
                    dialog().setTitle("Printer didn't answer")
                            .setMessage("Check that the printer is on and on the same Wi-Fi, and that Canon Auto Power On is enabled.")
                            .setPositiveButton("Try again", (d, w) -> wakePrinter())
                            .setNegativeButton("Close", null).show();
                }
            });
        }).start();
    }

    private void showPrinterSettingsDialog() {
        SharedPreferences prefs = getSharedPreferences("printer", MODE_PRIVATE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(6), dp(22), 0);

        box.addView(label(DEFAULT_PRINTER_NAME + " — last address found (auto-search 192.168.58.100–110)"));
        EditText hostInput = new EditText(this);
        hostInput.setSingleLine(true);
        hostInput.setText(prefs.getString("host", DEFAULT_PRINTER_HOST));
        hostInput.setSelectAllOnFocus(true);
        box.addView(hostInput, new LinearLayout.LayoutParams(-1, -2));

        CheckBox keepAlive = new CheckBox(this);
        keepAlive.setText("Keep printer awake in the background");
        keepAlive.setChecked(prefs.getBoolean("keep_alive_enabled", true));
        box.addView(keepAlive);

        CheckBox aggressive = new CheckBox(this);
        aggressive.setText("Retry the connection a few times before printing");
        aggressive.setChecked(prefs.getBoolean("aggressive_reconnect", true));
        box.addView(aggressive);

        box.addView(label("Wake every (minutes, minimum 15; 180 = 3 hours)"));
        EditText intervalInput = new EditText(this);
        intervalInput.setSingleLine(true);
        intervalInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        intervalInput.setText(String.valueOf(Math.max(15, prefs.getInt("interval_minutes", DEFAULT_KEEPALIVE_MINUTES))));
        box.addView(intervalInput, new LinearLayout.LayoutParams(-1, -2));

        TextView note = label("Android may delay background checks to save battery. A reachable address doesn't guarantee a page prints.");
        note.setTextSize(11.5f);
        box.addView(note);

        AlertDialog dlg = dialog()
                .setTitle("Printer settings")
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", null)
                .create();
        dlg.setOnShowListener(x -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String host = hostInput.getText().toString().trim();
            int minutes = 15;
            try { minutes = Integer.parseInt(intervalInput.getText().toString().trim()); } catch (Exception ignored) { }
            if (host.isEmpty()) host = DEFAULT_PRINTER_HOST;
            if (minutes < 15) { minutes = 15; showToast("Android's minimum background interval is 15 minutes, so I set it to 15."); }
            if (minutes > 1440) minutes = 1440;
            prefs.edit()
                    .putString("host", host)
                    .putBoolean("keep_alive_enabled", keepAlive.isChecked())
                    .putBoolean("aggressive_reconnect", aggressive.isChecked())
                    .putInt("interval_minutes", minutes)
                    .apply();
            Diagnostics.log("PRINTER_SETTINGS_SAVED", "host=" + PrinterWake.normalizeHost(host) + ", keepAlive=" + keepAlive.isChecked() + ", interval=" + minutes);
            schedulePrinterKeepAlive(keepAlive.isChecked(), minutes, true);
            dlg.dismiss();
        }));
        dlg.show();
    }

    private void restorePrinterKeepAliveSchedule() {
        SharedPreferences prefs = getSharedPreferences("printer", MODE_PRIVATE);
        if (prefs.getBoolean("keep_alive_enabled", true)) {
            schedulePrinterKeepAlive(true, Math.max(15, prefs.getInt("interval_minutes", DEFAULT_KEEPALIVE_MINUTES)), false);
        }
    }

    /** @param announce show a toast; false on app start so opening the app isn't noisy. */
    private void schedulePrinterKeepAlive(boolean enabled, int minutes, boolean announce) {
        JobScheduler scheduler = (JobScheduler) getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) { Diagnostics.log("PRINTER_KEEPALIVE_SCHEDULE_FAILED", "JobScheduler unavailable"); return; }
        scheduler.cancel(PRINTER_KEEPALIVE_JOB_ID);
        if (!enabled) {
            Diagnostics.log("PRINTER_KEEPALIVE_DISABLED", "scheduled job cancelled");
            if (announce) showToast("Automatic printer keep-awake is off.");
            return;
        }
        int actual = Math.max(15, minutes);
        JobInfo job = new JobInfo.Builder(PRINTER_KEEPALIVE_JOB_ID, new ComponentName(this, PrinterKeepAliveService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(actual * 60_000L)
                .setPersisted(true)
                .build();
        int result = scheduler.schedule(job);
        Diagnostics.log("PRINTER_KEEPALIVE_SCHEDULED", "minutes=" + actual + ", result=" + result);
        if (result != JobScheduler.RESULT_SUCCESS) showToast("Android could not schedule printer keep-awake. Check the diagnostic log.");
        else if (announce) {
            if (actual % 60 == 0) showToast("Printer keep-awake: about every " + (actual / 60) + " hour" + ((actual / 60) == 1 ? "" : "s") + ".");
            else showToast("Printer keep-awake: about every " + actual + " minutes.");
        }
    }

    // ───────────────────────────── State ─────────────────────────────

    private void updateControls() {
        if (canvasView == null) return;
        if (undoButton != null) { undoButton.setEnabled(!undo.isEmpty()); undoButton.setAlpha(undo.isEmpty() ? 0.3f : 1f); }
        if (redoButton != null) { redoButton.setEnabled(!redo.isEmpty()); redoButton.setAlpha(redo.isEmpty() ? 0.3f : 1f); }
        if (zoomLabel != null) zoomLabel.setText(canvasView.getZoomPercent() + "%");
        refreshTools();
        updateStatus();
    }

    private void updateStatus() {
        if (subtitle == null || canvasView == null) return;
        String text;
        if (canvasView.isCropMode()) {
            text = "Drag the corners or edges to trim • drag inside to move";
        } else if (canvasView.isMultiSelectMode()) {
            int n = canvasView.getSelectionCount();
            text = n == 0 ? "Tap pictures to pick them" : n + " picked • tap more to add or remove";
        } else if (canvasView.hasSelection()) {
            text = canvasView.selectedSizeText();
        } else {
            int on = canvasView.getOnPageCount(), off = canvasView.getOffPageCount();
            StringBuilder b = new StringBuilder(canvasView.isPortrait() ? "Letter portrait" : "Letter landscape");
            b.append(" • ").append(on).append(on == 1 ? " label" : " labels");
            if (off > 0) b.append(" • ").append(off).append(" off page");
            int crossing = canvasView.getCrossingEdgeCount();
            if (crossing > 0) b.append(" • ").append(crossing).append(" cut off");
            text = b.toString();
        }
        subtitle.setText(text);
    }

    private void persist() {
        if (canvasView == null) return;
        SheetStore.save(this, canvasView.toJson());
    }

    @Override public void onSelectionChanged(boolean hasSelection) { updateControls(); }

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
        rebuildUi();
    }

    /** Re-creates the screen (after rotation or a colour change) without losing the sheet or undo history. */
    private void rebuildUi() {
        if (canvasView == null) return;
        if (canvasView.isCropMode()) applyCrop();
        String sheet = canvasView.toJson();
        undoBar = null;
        setContentView(buildUi());
        canvasView.fromJson(sheet);
        updateControls();
    }

    @Override public void onBackPressed() {
        if (canvasView != null && canvasView.isCropMode()) { cancelCrop(); return; }
        if (canvasView != null && canvasView.isMultiSelectMode()) { canvasView.setMultiSelectMode(false); canvasView.clearSelection(); return; }
        if (canvasView != null && canvasView.hasSelection()) { canvasView.clearSelection(); return; }
        super.onBackPressed();
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
                    showToast(added + " pictures added to the page.");
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
                showToast("Picture added to the page.");
            } else {
                undo.pollFirst();
                showToast("I couldn't import that shared image.");
            }
        } catch (Exception e) {
            Diagnostics.error("SHARED_IMAGE_IMPORT_FAILED", e);
            showToast("The shared image couldn't be imported. The log captured the error.");
        }
        updateControls();
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


    // ───────────────────────────── Colour schemes ─────────────────────────────

    private void applyWindowColors() {
        android.view.Window w = getWindow();
        w.setStatusBarColor(Theme.lighter(Theme.BAR, 0.16f));
        w.setNavigationBarColor(Theme.SURFACE);
        w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Theme.SURFACE));
        View decor = w.getDecorView();
        int flags = decor.getSystemUiVisibility();
        if (Theme.BAR_DARK) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR; else flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) {
            if (Theme.DARK) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR; else flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        decor.setSystemUiVisibility(flags);
    }

    private void showSchemePicker() {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(4), dp(8), dp(4));
        java.util.ArrayList<Theme.Scheme> all = new java.util.ArrayList<>(java.util.Arrays.asList(Theme.PRESETS));
        Theme.Scheme custom = Theme.customOrNull(this);
        if (custom != null) all.add(custom);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        AlertDialog dlg = dialog().setTitle("Color scheme").setView(scroll)
                .setNeutralButton("Customize…", (d, w) -> showCustomizeScheme())
                .setPositiveButton("Done", null).create();
        String group = null;
        for (Theme.Scheme sc : all) {
            String g = "custom".equals(sc.id) ? "Yours" : (sc.bold ? "Bold" : "Soft");
            if (!g.equals(group)) {
                group = g;
                TextView h = new TextView(this);
                h.setText(g.toUpperCase(java.util.Locale.US));
                h.setTextSize(11.5f);
                h.setLetterSpacing(0.08f);
                h.setTypeface(Typeface.DEFAULT_BOLD);
                h.setTextColor(Theme.HILITE);
                h.setPadding(dp(12), dp(12), 0, dp(4));
                list.addView(h);
            }
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(8), dp(12), dp(8));
            row.setBackground(ripple(12));
            row.setClickable(true);
            row.setContentDescription(sc.name);
            row.addView(schemePreview(sc), new LinearLayout.LayoutParams(dp(76), dp(44)));
            TextView name = new TextView(this);
            name.setText(sc.name);
            name.setTextSize(15);
            name.setTextColor(Theme.TEXT);
            name.setPadding(dp(14), 0, 0, 0);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
            boolean isCurrent = sc.id.equals(Theme.current.id);
            if (isCurrent) {
                android.widget.ImageView check = new android.widget.ImageView(this);
                check.setImageResource(R.drawable.ic_check);
                check.setImageTintList(ColorStateList.valueOf(Theme.HILITE));
                row.addView(check, new LinearLayout.LayoutParams(dp(28), dp(28)));
            }
            row.setOnClickListener(v -> {
                Theme.apply(sc);
                Theme.save(this, sc);
                Diagnostics.log("COLOR_SCHEME", sc.id);
                rebuildUi();
                dlg.dismiss();
                showSchemePicker();
            });
            list.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        dlg.show();
    }

    /** Tiny picture of the scheme: bar, page area, accent button and a text line. */
    private View schemePreview(Theme.Scheme sc) {
        FrameLayout f = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(sc.workspace);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), Theme.blend(sc.workspace, sc.text, 0.25f));
        f.setBackground(bg);
        View bar = new View(this);
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(sc.bars);
        barBg.setCornerRadii(new float[]{dp(8), dp(8), dp(8), dp(8), 0, 0, 0, 0});
        bar.setBackground(barBg);
        f.addView(bar, new FrameLayout.LayoutParams(-1, dp(15), Gravity.TOP));
        View line = new View(this);
        line.setBackgroundColor(Theme.bestOn(sc.bars));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(26), dp(3), Gravity.TOP | Gravity.START);
        lp.setMargins(dp(7), dp(6), 0, 0);
        f.addView(line, lp);
        View btn = new View(this);
        GradientDrawable bb = new GradientDrawable();
        bb.setColor(sc.accent);
        bb.setCornerRadius(dp(5));
        btn.setBackground(bb);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(dp(18), dp(9), Gravity.TOP | Gravity.END);
        bp.setMargins(0, dp(3), dp(6), 0);
        f.addView(btn, bp);
        View page = new View(this);
        page.setBackgroundColor(Color.WHITE);
        FrameLayout.LayoutParams pg = new FrameLayout.LayoutParams(dp(18), dp(22), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        pg.bottomMargin = dp(3);
        f.addView(page, pg);
        return f;
    }

    private static final String[] ROLE_NAMES = {"Action color (Add, Print)", "Top bar", "Around the page", "Text and icons"};

    private void showCustomizeScheme() {
        final Theme.Scheme original = Theme.current;
        final Theme.Scheme[] working = {original};
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(4), dp(14), dp(4));
        TextView intro = label("Starting from \"" + original.name + "\". Tap a colour to change it — the app updates as you go.");
        box.addView(intro);
        TextView warn = label("");
        warn.setTextColor(Theme.DANGER);
        View[] swatches = new View[4];
        Runnable refresh = () -> {
            int[] cols = {working[0].accent, working[0].bars, working[0].workspace, working[0].text};
            for (int i = 0; i < 4; i++) swatches[i].setBackground(swatch(cols[i]));
            double c = Theme.contrast(working[0].text, working[0].bars);
            warn.setText(c < 4.5 ? "Text and bars are close in brightness — text may be hard to read." : "");
            warn.setVisibility(c < 4.5 ? View.VISIBLE : View.GONE);
        };
        for (int i = 0; i < 4; i++) {
            final int role = i;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(8), dp(8), dp(8));
            row.setBackground(ripple(12));
            row.setClickable(true);
            swatches[i] = new View(this);
            row.addView(swatches[i], new LinearLayout.LayoutParams(dp(36), dp(36)));
            TextView t = new TextView(this);
            t.setText(ROLE_NAMES[i]);
            t.setTextSize(15);
            t.setTextColor(Theme.TEXT);
            t.setPadding(dp(14), 0, 0, 0);
            row.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
            row.setOnClickListener(v -> {
                int[] cols = {working[0].accent, working[0].bars, working[0].workspace, working[0].text};
                showColorPicker(ROLE_NAMES[role], cols[role], color -> {
                    working[0] = working[0].with(role, color);
                    Theme.apply(working[0]);
                    rebuildUi();
                    refresh.run();
                });
            });
            box.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        box.addView(warn);
        refresh.run();
        dialog().setTitle("Customize colors").setView(box)
                .setNegativeButton("Cancel", (d, w) -> { Theme.apply(original); rebuildUi(); })
                .setNeutralButton("Presets…", (d, w) -> { Theme.apply(original); rebuildUi(); showSchemePicker(); })
                .setPositiveButton("Save", (d, w) -> {
                    if (working[0] == original) return;
                    Theme.Scheme saved = working[0];
                    Theme.save(this, saved);
                    Theme.apply(saved);
                    Diagnostics.log("COLOR_SCHEME", "custom saved");
                    showToast("Custom colors saved. Find them as \"Custom\" in Color scheme.");
                })
                .setOnCancelListener(d -> { Theme.apply(original); rebuildUi(); })
                .show();
    }

    private GradientDrawable swatch(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        g.setStroke(dp(1), Theme.OUTLINE);
        return g;
    }

    private interface ColorCallback { void picked(int color); }

    private static final int[] QUICK_COLORS = {
            0xFF2563EB, 0xFF0F8B8D, 0xFF2E7D32, 0xFFD9480F, 0xFF6D28D9, 0xFFD6336C, 0xFFE11D48, 0xFFCA8A04,
            0xFFFFFFFF, 0xFFF4F6FA, 0xFFE3E9F1, 0xFFECEFF4, 0xFF94A3B8, 0xFF1E2A3B, 0xFF111722, 0xFF000000};

    /** Pick a colour: quick swatches, hue/saturation/brightness sliders, or a #RRGGBB code. */
    private void showColorPicker(String title, int initial, ColorCallback callback) {
        final float[] hsv = new float[3];
        Color.colorToHSV(initial, hsv);
        final boolean[] syncing = {false};
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(6), dp(20), 0);

        View preview = new View(this);
        box.addView(preview, new LinearLayout.LayoutParams(-1, dp(44)));
        EditText hex = new EditText(this);
        hex.setSingleLine(true);
        hex.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        hex.setHint("#RRGGBB");
        box.addView(hex, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        SeekBar hue = new SeekBar(this), sat = new SeekBar(this), val = new SeekBar(this);
        hue.setMax(360); sat.setMax(100); val.setMax(100);
        GradientDrawable rainbow = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000});
        rainbow.setCornerRadius(dp(6));
        hue.setProgressDrawable(rainbow);

        Runnable show = () -> {
            int c = Color.HSVToColor(hsv);
            GradientDrawable g = new GradientDrawable();
            g.setColor(c);
            g.setCornerRadius(dp(10));
            g.setStroke(dp(1), Theme.OUTLINE);
            preview.setBackground(g);
            if (!syncing[0]) { syncing[0] = true; hex.setText(String.format("#%06X", c & 0xFFFFFF)); syncing[0] = false; }
        };
        Runnable toSliders = () -> {
            syncing[0] = true;
            hue.setProgress(Math.round(hsv[0])); sat.setProgress(Math.round(hsv[1] * 100)); val.setProgress(Math.round(hsv[2] * 100));
            syncing[0] = false;
        };

        for (int r = 0; r < 2; r++) {
            LinearLayout row = new LinearLayout(this);
            for (int c = 0; c < 8; c++) {
                final int color = QUICK_COLORS[r * 8 + c];
                View dot = new View(this);
                dot.setBackground(swatch(color));
                dot.setClickable(true);
                dot.setOnClickListener(v -> { Color.colorToHSV(color, hsv); toSliders.run(); show.run(); });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(30), 1f);
                lp.setMargins(dp(3), dp(4), dp(3), dp(4));
                row.addView(dot, lp);
            }
            grid.addView(row);
        }
        box.addView(grid);

        SeekBar.OnSeekBarChangeListener l = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int v, boolean user) {
                if (syncing[0]) return;
                hsv[0] = hue.getProgress(); hsv[1] = sat.getProgress() / 100f; hsv[2] = val.getProgress() / 100f;
                show.run();
            }
            @Override public void onStartTrackingTouch(SeekBar b) {}
            @Override public void onStopTrackingTouch(SeekBar b) {}
        };
        hue.setOnSeekBarChangeListener(l); sat.setOnSeekBarChangeListener(l); val.setOnSeekBarChangeListener(l);
        box.addView(label("Hue")); box.addView(hue, new LinearLayout.LayoutParams(-1, dp(36)));
        box.addView(label("Strength")); box.addView(sat, new LinearLayout.LayoutParams(-1, dp(36)));
        box.addView(label("Brightness")); box.addView(val, new LinearLayout.LayoutParams(-1, dp(36)));

        hex.addTextChangedListener(new SimpleWatcher(() -> {
            if (syncing[0]) return;
            String t = hex.getText().toString().trim();
            if (!t.startsWith("#")) t = "#" + t;
            if (t.length() != 7) return;
            try {
                Color.colorToHSV(Color.parseColor(t), hsv);
                syncing[0] = true; show.run(); syncing[0] = false;
                toSliders.run();
            } catch (IllegalArgumentException ignored) { }
        }));
        toSliders.run();
        show.run();
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        dialog().setTitle(title).setView(sv)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Use color", (d, w) -> callback.picked(Color.HSVToColor(hsv)))
                .show();
    }

    // ───────────────────────────── View helpers ─────────────────────────────

    private ImageButton iconButton(int icon, String description, View.OnClickListener click) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(icon);
        b.setImageTintList(ColorStateList.valueOf(Theme.ON_BAR));
        b.setBackground(Depth.glass(Theme.ON_BAR, dp(14)));
        b.setContentDescription(description);
        b.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
        lp.leftMargin = dp(5);
        b.setLayoutParams(lp);
        return b;
    }

    private Button textButton(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setMinHeight(0); b.setMinimumHeight(0); b.setMinWidth(0); b.setMinimumWidth(0);
        b.setStateListAnimator(null);
        b.setTextColor(primary ? Theme.ON_PRIMARY : Theme.HILITE);
        b.setBackground(primary ? pill(Theme.PRIMARY, 0, 22) : ripple(22));
        b.setPadding(dp(18), 0, dp(18), 0);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, dp(44));
        p.leftMargin = dp(6);
        b.setLayoutParams(p);
        return b;
    }

    private GradientDrawable pill(int fill, int stroke, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    private RippleDrawable ripple(int radiusDp) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(radiusDp));
        return new RippleDrawable(ColorStateList.valueOf(Theme.RIPPLE), null, mask);
    }

    private View divider(boolean horizontal) {
        View v = new View(this);
        v.setBackgroundColor(Theme.OUTLINE);
        v.setLayoutParams(horizontal ? new LinearLayout.LayoutParams(-1, dp(1)) : new LinearLayout.LayoutParams(dp(1), -1));
        return v;
    }

    private void showToast(String msg) { Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
