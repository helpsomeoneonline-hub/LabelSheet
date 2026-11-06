package com.byso.labelsheet;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class LetterCanvasView extends View {
    public interface Listener {
        void onSelectionChanged(boolean hasSelection);
        void onEditStarted();
        void onSheetChanged();
    }

    private final ArrayList<SheetItem> items = new ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint pagePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint safePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ScaleGestureDetector scaleDetector;
    private Listener listener;

    private boolean portrait = true;
    private int selected = -1;
    private final LinkedHashSet<Integer> selectedSet = new LinkedHashSet<>();
    private boolean multiSelectMode = false;
    private boolean downHitWasSelected = false;
    private int downHitIndex = -1;
    private final ArrayList<Float> groupStartX = new ArrayList<>();
    private final ArrayList<Float> groupStartY = new ArrayList<>();
    private float zoom = 1f;
    private float panX = 0f, panY = 0f;
    private boolean panningCanvas = false;
    private float panStartX, panStartY;
    private RectF pageRect = new RectF();
    private float downX, downY;
    private float startItemX, startItemY, startItemW, startItemH;
    private boolean dragging = false;
    private int resizeHandle = HANDLE_NONE;
    private static final int HANDLE_NONE = 0;
    private static final int HANDLE_LEFT = 1;
    private static final int HANDLE_RIGHT = 2;
    private static final int HANDLE_TOP = 3;
    private static final int HANDLE_BOTTOM = 4;
    private static final int HANDLE_TL = 5;
    private static final int HANDLE_TR = 6;
    private static final int HANDLE_BL = 7;
    private static final int HANDLE_BR = 8;
    private boolean gestureUndoPushed = false;
    private boolean showGuideX = false;
    private boolean showGuideY = false;
    private float guideXNorm = 0.5f;
    private float guideYNorm = 0.5f;
    private final float touchSlop;

    public LetterCanvasView(Context context) {
        this(context, null);
    }

    public LetterCanvasView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        touchSlop = 4f * getResources().getDisplayMetrics().density;

        pagePaint.setColor(Color.WHITE);
        pagePaint.setShadowLayer(16f, 0f, 6f, 0x55000000);

        selectedPaint.setStyle(Paint.Style.STROKE);
        selectedPaint.setStrokeWidth(2.2f * getResources().getDisplayMetrics().density);
        selectedPaint.setColor(Color.rgb(25, 181, 165));

        safePaint.setStyle(Paint.Style.STROKE);
        safePaint.setStrokeWidth(1f * getResources().getDisplayMetrics().density);
        safePaint.setColor(0x556C7A89);
        safePaint.setPathEffect(new DashPathEffect(new float[]{8f, 8f}, 0f));

        guidePaint.setStyle(Paint.Style.STROKE);
        guidePaint.setStrokeWidth(1.3f * getResources().getDisplayMetrics().density);
        guidePaint.setColor(Color.rgb(25, 181, 165));

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                panningCanvas = false;
                return true;
            }

            @Override public boolean onScale(ScaleGestureDetector detector) {
                float oldZoom = zoom;
                float newZoom = clamp(oldZoom * detector.getScaleFactor(), 0.65f, 4.0f);
                if (Math.abs(newZoom - oldZoom) < 0.0001f) return true;
                float cx = getWidth() / 2f + panX;
                float cy = getHeight() / 2f + panY;
                float focusX = detector.getFocusX();
                float focusY = detector.getFocusY();
                float ratio = newZoom / oldZoom;
                panX = focusX - getWidth() / 2f - (focusX - cx) * ratio;
                panY = focusY - getHeight() / 2f - (focusY - cy) * ratio;
                zoom = newZoom;
                clampPan();
                invalidate();
                return true;
            }

            @Override public void onScaleEnd(ScaleGestureDetector detector) {
                Diagnostics.log("CANVAS_ZOOM", "percent=" + getZoomPercent());
                if (listener != null) listener.onSelectionChanged(hasSelection());
            }
        });
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isPortrait() { return portrait; }

    public void setPortrait(boolean portrait) {
        this.portrait = portrait;
        invalidate();
    }

    public void toggleOrientation() {
        portrait = !portrait;
        invalidate();
        if (listener != null) listener.onSheetChanged();
    }

    public boolean hasSelection() {
        return selected >= 0 && selected < items.size();
    }

    public int getSelectionCount() { return selectedSet.size(); }

    public boolean isMultiSelectMode() { return multiSelectMode; }

    public void setMultiSelectMode(boolean enabled) {
        multiSelectMode = enabled;
        if (!enabled && hasSelection()) {
            selectedSet.clear();
            selectedSet.add(selected);
        }
        Diagnostics.log("MULTI_SELECT_MODE", enabled ? "on" : "off");
        invalidate();
        if (listener != null) listener.onSelectionChanged(hasSelection());
    }

    public int getZoomPercent() { return Math.round(zoom * 100f); }

    public void setZoom(float value) {
        zoom = clamp(value, 0.65f, 4.0f);
        clampPan();
        invalidate();
        if (listener != null) listener.onSelectionChanged(hasSelection());
    }

    public void zoomBy(float factor) { setZoom(zoom * factor); }

    public void fitPage() {
        zoom = 1f;
        panX = 0f;
        panY = 0f;
        invalidate();
        Diagnostics.log("CANVAS_ZOOM", "fit_page");
        if (listener != null) listener.onSelectionChanged(hasSelection());
    }

    public boolean fitSelection() {
        if (!hasSelection()) return false;
        SheetItem item = items.get(selected);
        float target = Math.min(3.2f, Math.max(1f, 0.72f / Math.max(item.w, item.h)));
        zoom = clamp(target, 1f, 4f);
        float baseW = getBasePageWidth();
        float baseH = getBasePageHeight();
        float itemCx = (item.x + item.w / 2f - 0.5f) * baseW * zoom;
        float itemCy = (item.y + item.h / 2f - 0.5f) * baseH * zoom;
        panX = -itemCx;
        panY = -itemCy;
        clampPan();
        invalidate();
        Diagnostics.log("CANVAS_ZOOM", "fit_selection=" + getZoomPercent());
        if (listener != null) listener.onSelectionChanged(true);
        return true;
    }

    public int getItemCount() { return items.size(); }

    public void clearSelection() {
        selected = -1;
        selectedSet.clear();
        invalidate();
        if (listener != null) listener.onSelectionChanged(false);
    }

    public boolean addImage(Uri uri) {
        try {
            Bitmap bitmap = decodeBitmap(uri, 1800);
            if (bitmap == null) return false;
            float pageAspect = portrait ? 8.5f / 11f : 11f / 8.5f;
            float imageAspect = bitmap.getWidth() / (float) Math.max(1, bitmap.getHeight());
            float w = 0.44f;
            float h = w * pageAspect / Math.max(0.01f, imageAspect);
            if (h > 0.40f) {
                h = 0.40f;
                w = h * imageAspect / pageAspect;
            }
            w = clamp(w, 0.12f, 0.80f);
            h = clamp(h, 0.08f, 0.65f);
            float offset = Math.min(0.06f * items.size(), 0.24f);
            SheetItem item = new SheetItem(uri.toString(), 0.08f + offset, 0.08f + offset, w, h);
            item.x = clamp(item.x, 0f, 1f - item.w);
            item.y = clamp(item.y, 0f, 1f - item.h);
            item.bitmap = bitmap;
            items.add(item);
            selected = items.size() - 1;
            selectedSet.clear();
            selectedSet.add(selected);
            Diagnostics.log("IMAGE_IMPORTED", "size=" + bitmap.getWidth() + "x" + bitmap.getHeight() + ", labels=" + items.size());
            invalidate();
            if (listener != null) {
                listener.onSelectionChanged(true);
                listener.onSheetChanged();
            }
            return true;
        } catch (Exception e) {
            Diagnostics.error("IMAGE_IMPORT_FAILED", e);
            return false;
        }
    }

    public boolean replaceSelected(Uri uri) {
        if (!hasSelection() || items.get(selected).locked) return false;
        try {
            Bitmap bitmap = decodeBitmap(uri, 1800);
            if (bitmap == null) return false;
            SheetItem item = items.get(selected);
            item.uri = uri.toString();
            item.bitmap = bitmap;
            Diagnostics.log("IMAGE_REPLACED", "size=" + bitmap.getWidth() + "x" + bitmap.getHeight());
            invalidate();
            if (listener != null) listener.onSheetChanged();
            return true;
        } catch (Exception e) {
            Diagnostics.error("IMAGE_REPLACE_FAILED", e);
            return false;
        }
    }

    public boolean duplicateSelected() {
        if (!hasSelection()) return false;
        SheetItem original = items.get(selected);
        SheetItem copy = original.copy();
        copy.locked = false;
        copy.x = clamp(copy.x + 0.035f, 0f, 1f - copy.w);
        copy.y = clamp(copy.y + 0.035f, 0f, 1f - copy.h);
        items.add(copy);
        selected = items.size() - 1;
        selectedSet.clear();
        selectedSet.add(selected);
        Diagnostics.log("LABEL_DUPLICATED", "labels=" + items.size());
        invalidate();
        if (listener != null) listener.onSheetChanged();
        return true;
    }

    public boolean deleteSelected() {
        if (!hasSelection()) return false;
        ArrayList<Integer> targets = new ArrayList<>();
        if (selectedSet.isEmpty()) targets.add(selected); else targets.addAll(selectedSet);
        targets.sort(Collections.reverseOrder());
        int removed = 0;
        for (int idx : targets) {
            if (idx >= 0 && idx < items.size() && !items.get(idx).locked) { items.remove(idx); removed++; }
        }
        if (removed == 0) return false;
        selected = -1; selectedSet.clear();
        Diagnostics.log("LABEL_DELETED", "removed=" + removed + ", labels=" + items.size());
        invalidate();
        if (listener != null) { listener.onSelectionChanged(false); listener.onSheetChanged(); }
        return true;
    }

    public boolean rotateSelected() {
        if (!hasSelection()) return false;
        SheetItem item = items.get(selected);
        if (item.locked) return false;
        item.rotation = (item.rotation + 90f) % 360f;
        float temp = item.w;
        item.w = clamp(item.h * (portrait ? (11f / 8.5f) : (8.5f / 11f)), 0.05f, 0.95f);
        item.h = clamp(temp * (portrait ? (8.5f / 11f) : (11f / 8.5f)), 0.035f, 0.95f);
        item.x = clamp(item.x, 0f, 1f - item.w);
        item.y = clamp(item.y, 0f, 1f - item.h);
        Diagnostics.log("LABEL_ROTATED", "rotation=" + item.rotation);
        invalidate();
        if (listener != null) listener.onSheetChanged();
        return true;
    }


    public float getSelectedBorderPt() {
        return hasSelection() ? items.get(selected).borderPt : 0f;
    }

    public boolean setSelectedBorderPt(float points) {
        if (!hasSelection()) return false;
        SheetItem item = items.get(selected);
        if (item.locked) return false;
        item.borderPt = clamp(points, 0f, 24f);
        Diagnostics.log("BORDER_CHANGED", "points=" + item.borderPt);
        invalidate();
        if (listener != null) listener.onSheetChanged();
        return true;
    }

    public Uri getSelectedUri() {
        if (!hasSelection()) return null;
        return Uri.parse(items.get(selected).uri);
    }

    public float[] getSelectedCrop() {
        if (!hasSelection()) return new float[]{0f, 0f, 0f, 0f};
        SheetItem item = items.get(selected);
        return new float[]{item.cropLeft, item.cropTop, item.cropRight, item.cropBottom};
    }

    public boolean setSelectedCrop(float left, float top, float right, float bottom) {
        if (!hasSelection()) return false;
        SheetItem item = items.get(selected);
        if (item.locked) return false;
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

    public boolean isSelectedLocked() {
        return hasSelection() && items.get(selected).locked;
    }

    public boolean toggleLockSelected() {
        if (!hasSelection()) return false;
        boolean next = !items.get(selected).locked;
        Set<Integer> targets = selectedSet.isEmpty() ? java.util.Collections.singleton(selected) : new LinkedHashSet<>(selectedSet);
        for (int i : targets) if (i >= 0 && i < items.size()) items.get(i).locked = next;
        Diagnostics.log("LABEL_LOCK", (next ? "locked" : "unlocked") + ", count=" + targets.size());
        invalidate();
        if (listener != null) listener.onSheetChanged();
        return true;
    }

    public String getSelectedFormatJson() {
        if (!hasSelection()) return null;
        SheetItem item = items.get(selected);
        try {
            JSONObject o = new JSONObject();
            o.put("w", item.w); o.put("h", item.h); o.put("rotation", item.rotation);
            o.put("borderPt", item.borderPt);
            o.put("cropLeft", item.cropLeft); o.put("cropTop", item.cropTop);
            o.put("cropRight", item.cropRight); o.put("cropBottom", item.cropBottom);
            return o.toString();
        } catch (JSONException e) { return null; }
    }

    public boolean applyFormatJson(String json) {
        if (!hasSelection() || json == null) return false;
        SheetItem item = items.get(selected);
        if (item.locked) return false;
        try {
            JSONObject o = new JSONObject(json);
            float cx = item.x + item.w / 2f, cy = item.y + item.h / 2f;
            item.w = clamp((float) o.optDouble("w", item.w), 0.04f, 0.95f);
            item.h = clamp((float) o.optDouble("h", item.h), 0.03f, 0.95f);
            item.rotation = (float) o.optDouble("rotation", item.rotation);
            item.borderPt = clamp((float) o.optDouble("borderPt", item.borderPt), 0f, 24f);
            item.cropLeft = clamp((float) o.optDouble("cropLeft", item.cropLeft), 0f, 0.45f);
            item.cropTop = clamp((float) o.optDouble("cropTop", item.cropTop), 0f, 0.45f);
            item.cropRight = clamp((float) o.optDouble("cropRight", item.cropRight), 0f, 0.45f);
            item.cropBottom = clamp((float) o.optDouble("cropBottom", item.cropBottom), 0f, 0.45f);
            item.x = clamp(cx - item.w / 2f, 0f, 1f - item.w);
            item.y = clamp(cy - item.h / 2f, 0f, 1f - item.h);
            Diagnostics.log("FORMAT_PASTED", selectedSizeText());
            invalidate();
            if (listener != null) listener.onSheetChanged();
            return true;
        } catch (Exception e) {
            Diagnostics.error("FORMAT_PASTE_FAILED", e);
            return false;
        }
    }


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
        if (!(!Float.isNaN(widthIn) && !Float.isInfinite(widthIn)) || !(!Float.isNaN(heightIn) && !Float.isInfinite(heightIn))
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
            if (Float.isNaN(rot) || Float.isInfinite(rot)) return 0;
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

    public boolean setSelectedSizeInches(float widthIn, float heightIn) {
        if (!hasSelection()) return false;
        SheetItem item = items.get(selected);
        if (item.locked) return false;
        float pageW = portrait ? 8.5f : 11f;
        float pageH = portrait ? 11f : 8.5f;
        float newW = clamp(widthIn / pageW, 0.04f, 0.95f);
        float newH = clamp(heightIn / pageH, 0.03f, 0.95f);
        float cx = item.x + item.w / 2f, cy = item.y + item.h / 2f;
        item.w = newW; item.h = newH;
        item.x = clamp(cx - newW / 2f, 0f, 1f - newW);
        item.y = clamp(cy - newH / 2f, 0f, 1f - newH);
        Diagnostics.log("EXACT_SIZE_SET", selectedSizeText());
        invalidate(); if (listener != null) listener.onSheetChanged(); return true;
    }

    public float[] getSelectedSizeInches() {
        if (!hasSelection()) return new float[]{0f,0f};
        SheetItem item = items.get(selected);
        return new float[]{item.w * (portrait ? 8.5f : 11f), item.h * (portrait ? 11f : 8.5f)};
    }

    public boolean nudgeSelected(float dxIn, float dyIn) {
        if (!hasSelection()) return false;
        float dx = dxIn / (portrait ? 8.5f : 11f);
        float dy = dyIn / (portrait ? 11f : 8.5f);
        Set<Integer> targets = selectedSet.isEmpty() ? java.util.Collections.singleton(selected) : new LinkedHashSet<>(selectedSet);
        boolean changed = false;
        for (int i : targets) {
            if (i < 0 || i >= items.size()) continue;
            SheetItem item = items.get(i); if (item.locked) continue;
            item.x = clamp(item.x + dx, 0f, 1f - item.w);
            item.y = clamp(item.y + dy, 0f, 1f - item.h);
            changed = true;
        }
        if (changed) { invalidate(); if (listener != null) listener.onSheetChanged(); }
        return changed;
    }

    public boolean bringSelectedForward() {
        if (!hasSelection() || selected >= items.size() - 1) return false;
        SheetItem item = items.remove(selected);
        int newIndex = selected + 1;
        items.add(newIndex, item);
        selected = newIndex; selectedSet.clear(); selectedSet.add(selected);
        Diagnostics.log("LAYER_ORDER", "forward"); invalidate(); if (listener != null) listener.onSheetChanged(); return true;
    }

    public boolean sendSelectedBackward() {
        if (!hasSelection() || selected <= 0) return false;
        SheetItem item = items.remove(selected);
        int newIndex = selected - 1;
        items.add(newIndex, item);
        selected = newIndex; selectedSet.clear(); selectedSet.add(selected);
        Diagnostics.log("LAYER_ORDER", "backward"); invalidate(); if (listener != null) listener.onSheetChanged(); return true;
    }

    public boolean duplicateSelectedCopies(int count) {
        if (!hasSelection() || count <= 0) return false;
        SheetItem source = items.get(selected);
        int made = 0;
        for (int n = 0; n < count; n++) {
            SheetItem copy = source.copy(); copy.locked = false;
            float off = 0.025f * (n + 1);
            copy.x = clamp(source.x + off, 0f, 1f - copy.w);
            copy.y = clamp(source.y + off, 0f, 1f - copy.h);
            items.add(copy); made++;
        }
        selected = items.size() - 1; selectedSet.clear(); selectedSet.add(selected);
        Diagnostics.log("LABEL_DUPLICATE_COPIES", "count=" + made); invalidate(); if (listener != null) listener.onSheetChanged(); return true;
    }

    public boolean fillSheetWithSelected() {
        if (!hasSelection()) return false;
        SheetItem source = items.get(selected);
        float gapX = 0.012f, gapY = 0.012f, margin = 0.025f;
        int cols = Math.max(1, (int) Math.floor((1f - margin * 2f + gapX) / (source.w + gapX)));
        int rows = Math.max(1, (int) Math.floor((1f - margin * 2f + gapY) / (source.h + gapY)));
        items.clear();
        for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) {
            SheetItem copy = source.copy(); copy.locked = false;
            copy.x = margin + c * (source.w + gapX); copy.y = margin + r * (source.h + gapY);
            if (copy.x + copy.w <= 1f - margin + 0.001f && copy.y + copy.h <= 1f - margin + 0.001f) items.add(copy);
        }
        selected = items.isEmpty() ? -1 : 0; selectedSet.clear(); if (selected >= 0) selectedSet.add(selected);
        Diagnostics.log("FILL_SHEET", "labels=" + items.size()); invalidate(); if (listener != null) listener.onSheetChanged(); return true;
    }

    public boolean alignSelected(String mode) {
        if (selectedSet.size() < 2) return false;
        ArrayList<SheetItem> list = selectedItemsUnlocked(); if (list.size() < 2) return false;
        float left=1f,right=0f,top=1f,bottom=0f;
        for (SheetItem i:list) { left=Math.min(left,i.x); right=Math.max(right,i.x+i.w); top=Math.min(top,i.y); bottom=Math.max(bottom,i.y+i.h); }
        float cx=(left+right)/2f, cy=(top+bottom)/2f;
        for (SheetItem i:list) {
            if ("left".equals(mode)) i.x=left; else if ("right".equals(mode)) i.x=right-i.w; else if ("center".equals(mode)) i.x=cx-i.w/2f;
            else if ("top".equals(mode)) i.y=top; else if ("bottom".equals(mode)) i.y=bottom-i.h; else if ("middle".equals(mode)) i.y=cy-i.h/2f;
            i.x=clamp(i.x,0f,1f-i.w); i.y=clamp(i.y,0f,1f-i.h);
        }
        Diagnostics.log("ALIGN_SELECTED", mode + ", count=" + list.size()); invalidate(); if (listener != null) listener.onSheetChanged(); return true;
    }

    public boolean distributeSelected(boolean horizontal) {
        if (selectedSet.size() < 3) return false;
        ArrayList<SheetItem> list = selectedItemsUnlocked(); if (list.size() < 3) return false;
        if (horizontal) {
            list.sort(Comparator.comparingDouble(i -> i.x));
            float start=list.get(0).x, end=list.get(list.size()-1).x+list.get(list.size()-1).w, total=0f; for(SheetItem i:list) total+=i.w;
            float gap=Math.max(0f,(end-start-total)/(list.size()-1)); float x=start; for(SheetItem i:list){i.x=x;x+=i.w+gap;}
        } else {
            list.sort(Comparator.comparingDouble(i -> i.y));
            float start=list.get(0).y, end=list.get(list.size()-1).y+list.get(list.size()-1).h, total=0f; for(SheetItem i:list) total+=i.h;
            float gap=Math.max(0f,(end-start-total)/(list.size()-1)); float y=start; for(SheetItem i:list){i.y=y;y+=i.h+gap;}
        }
        Diagnostics.log("DISTRIBUTE_SELECTED", horizontal ? "horizontal" : "vertical"); invalidate(); if(listener!=null)listener.onSheetChanged(); return true;
    }

    private ArrayList<SheetItem> selectedItemsUnlocked() {
        ArrayList<SheetItem> out = new ArrayList<>();
        for (int idx : selectedSet) if (idx >= 0 && idx < items.size() && !items.get(idx).locked) out.add(items.get(idx));
        return out;
    }

    public void autoArrange() {
        int n = items.size();
        if (n == 0) return;
        int cols = n <= 2 ? 1 : (n <= 8 ? 2 : 3);
        int rows = (int) Math.ceil(n / (double) cols);
        float margin = 0.045f;
        float gap = 0.025f;
        float cellW = (1f - margin * 2f - gap * (cols - 1)) / cols;
        float cellH = (1f - margin * 2f - gap * (rows - 1)) / rows;
        float pageAspect = portrait ? (8.5f / 11f) : (11f / 8.5f);

        for (int i = 0; i < n; i++) {
            SheetItem item = items.get(i);
            int col = i % cols;
            int row = i / cols;
            float imageAspect = 1f;
            if (item.bitmap != null && item.bitmap.getHeight() > 0) {
                imageAspect = item.bitmap.getWidth() / (float) item.bitmap.getHeight();
            }
            if (((int) item.rotation) % 180 != 0) imageAspect = 1f / Math.max(0.01f, imageAspect);

            float w = cellW * 0.92f;
            float h = w * pageAspect / Math.max(0.01f, imageAspect);
            if (h > cellH * 0.90f) {
                h = cellH * 0.90f;
                w = h * imageAspect / pageAspect;
            }
            w = Math.min(w, cellW * 0.94f);
            h = Math.min(h, cellH * 0.94f);

            float cellX = margin + col * (cellW + gap);
            float cellY = margin + row * (cellH + gap);
            item.w = Math.max(0.04f, w);
            item.h = Math.max(0.03f, h);
            item.x = cellX + (cellW - item.w) / 2f;
            item.y = cellY + (cellH - item.h) / 2f;
        }
        selected = -1;
        selectedSet.clear();
        Diagnostics.log("AUTO_ARRANGE", "labels=" + n + ", grid=" + cols + "x" + rows);
        invalidate();
        if (listener != null) {
            listener.onSelectionChanged(false);
            listener.onSheetChanged();
        }
    }

    public void clearAll() {
        items.clear();
        selected = -1;
        selectedSet.clear();
        invalidate();
        if (listener != null) {
            listener.onSelectionChanged(false);
            listener.onSheetChanged();
        }
        Diagnostics.log("SHEET_CLEARED", "ok");
    }

    public String selectedSizeText() {
        if (!hasSelection()) return items.isEmpty() ? "No labels yet" : items.size() + (items.size() == 1 ? " label" : " labels");
        SheetItem item = items.get(selected);
        float pageW = portrait ? 8.5f : 11f;
        float pageH = portrait ? 11f : 8.5f;
        return String.format(java.util.Locale.US, "Selected: %.2f × %.2f in%s", item.w * pageW, item.h * pageH, item.locked ? " • Locked" : "");
    }

    public String toJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("portrait", portrait);
            JSONArray arr = new JSONArray();
            for (SheetItem item : items) arr.put(item.toJson());
            root.put("items", arr);
            return root.toString();
        } catch (JSONException e) {
            Diagnostics.error("STATE_SERIALIZE_FAILED", e);
            return "{\"portrait\":true,\"items\":[]}";
        }
    }

    public void fromJson(String json) {
        if (json == null || json.trim().isEmpty()) return;
        try {
            JSONObject root = new JSONObject(json);
            portrait = root.optBoolean("portrait", true);
            JSONArray arr = root.optJSONArray("items");
            items.clear();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    SheetItem item = SheetItem.fromJson(arr.getJSONObject(i));
                    try { item.bitmap = decodeBitmap(Uri.parse(item.uri), 1800); }
                    catch (Exception e) { Diagnostics.error("RESTORE_IMAGE_FAILED", e); }
                    if (item.bitmap != null) items.add(item);
                }
            }
            selected = -1;
            selectedSet.clear();
            Diagnostics.log("STATE_RESTORED", "labels=" + items.size());
            invalidate();
            if (listener != null) listener.onSelectionChanged(false);
        } catch (Exception e) {
            Diagnostics.error("STATE_RESTORE_FAILED", e);
        }
    }

    public void renderForOutput(Canvas canvas, RectF target) {
        Paint white = new Paint(Paint.ANTI_ALIAS_FLAG);
        white.setColor(Color.WHITE);
        canvas.drawRect(target, white);
        for (SheetItem item : items) {
            if (item.bitmap == null) continue;
            drawItem(canvas, item, target, false, false);
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float baseW = getBasePageWidth();
        float baseH = getBasePageHeight();
        float pw = baseW * zoom;
        float ph = baseH * zoom;
        clampPan();
        float left = (getWidth() - pw) / 2f + panX;
        float top = (getHeight() - ph) / 2f + panY;
        pageRect.set(left, top, left + pw, top + ph);
        canvas.drawRect(pageRect, pagePaint);

        float safeX = pageRect.width() * (0.25f / (portrait ? 8.5f : 11f));
        float safeY = pageRect.height() * (0.25f / (portrait ? 11f : 8.5f));
        RectF safe = new RectF(pageRect.left + safeX, pageRect.top + safeY,
                pageRect.right - safeX, pageRect.bottom - safeY);
        canvas.drawRect(safe, safePaint);

        for (int i = 0; i < items.size(); i++) {
            SheetItem item = items.get(i);
            if (item.bitmap == null) continue;
            boolean selectedState = selectedSet.contains(i) || i == selected;
            drawItem(canvas, item, pageRect, selectedState, i == selected && !item.locked);
        }

        if (showGuideX) {
            float gx = pageRect.left + guideXNorm * pageRect.width();
            canvas.drawLine(gx, pageRect.top, gx, pageRect.bottom, guidePaint);
        }
        if (showGuideY) {
            float gy = pageRect.top + guideYNorm * pageRect.height();
            canvas.drawLine(pageRect.left, gy, pageRect.right, gy, guidePaint);
        }
    }

    private void drawItem(Canvas canvas, SheetItem item, RectF page, boolean selectedState, boolean showHandles) {
        RectF dst = itemRect(item, page);
        canvas.save();
        canvas.rotate(item.rotation, dst.centerX(), dst.centerY());
        Matrix m = new Matrix();
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
        if (selectedState) {
            canvas.drawRect(dst, selectedPaint);
            if (!showHandles) { canvas.restore(); return; }
            float r = 4.5f * getResources().getDisplayMetrics().density;
            Paint handle = new Paint(Paint.ANTI_ALIAS_FLAG);
            handle.setColor(Color.WHITE);
            handle.setStyle(Paint.Style.FILL);
            float[][] handles = new float[][]{
                    {dst.left, dst.top}, {dst.centerX(), dst.top}, {dst.right, dst.top},
                    {dst.left, dst.centerY()}, {dst.right, dst.centerY()},
                    {dst.left, dst.bottom}, {dst.centerX(), dst.bottom}, {dst.right, dst.bottom}
            };
            Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
            edge.setColor(Color.rgb(25, 181, 165));
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(2f * getResources().getDisplayMetrics().density);
            for (float[] hp : handles) {
                canvas.drawCircle(hp[0], hp[1], r, handle);
                canvas.drawCircle(hp[0], hp[1], r, edge);
            }
        }
        canvas.restore();
    }

    private float getBasePageWidth() {
        float padding = 20f * getResources().getDisplayMetrics().density;
        float availW = Math.max(1f, getWidth() - padding * 2f);
        float availH = Math.max(1f, getHeight() - padding * 2f);
        float ratio = portrait ? 8.5f / 11f : 11f / 8.5f;
        float pw = availW;
        float ph = pw / ratio;
        if (ph > availH) pw = availH * ratio;
        return Math.max(1f, pw);
    }

    private float getBasePageHeight() {
        float w = getBasePageWidth();
        float ratio = portrait ? 8.5f / 11f : 11f / 8.5f;
        return w / ratio;
    }

    private void clampPan() {
        float baseW = getBasePageWidth(), baseH = getBasePageHeight();
        float scaledW = baseW * zoom, scaledH = baseH * zoom;
        float maxX = Math.max(0f, (scaledW - getWidth()) / 2f + 24f * getResources().getDisplayMetrics().density);
        float maxY = Math.max(0f, (scaledH - getHeight()) / 2f + 24f * getResources().getDisplayMetrics().density);
        panX = clamp(panX, -maxX, maxX);
        panY = clamp(panY, -maxY, maxY);
        if (zoom <= 1.001f) { panX = 0f; panY = 0f; }
    }

    private RectF itemRect(SheetItem item, RectF page) {
        return new RectF(
                page.left + item.x * page.width(),
                page.top + item.y * page.height(),
                page.left + (item.x + item.w) * page.width(),
                page.top + (item.y + item.h) * page.height());
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downX = event.getX();
                downY = event.getY();
                dragging = false;
                gestureUndoPushed = false;
                panningCanvas = false;
                resizeHandle = hitResizeHandle(downX, downY);
                int hit = resizeHandle != HANDLE_NONE ? selected : hitTest(downX, downY);
                downHitIndex = hit;
                downHitWasSelected = hit >= 0 && selectedSet.contains(hit);

                if (multiSelectMode && resizeHandle == HANDLE_NONE) {
                    if (hit >= 0) {
                        if (!selectedSet.contains(hit)) selectedSet.add(hit);
                        selected = hit;
                    } else {
                        if (zoom > 1.001f) {
                            panningCanvas = true;
                            panStartX = panX;
                            panStartY = panY;
                        }
                    }
                } else {
                    if (hit != selected) {
                        selected = hit;
                        selectedSet.clear();
                        if (hit >= 0) selectedSet.add(hit);
                        resizeHandle = HANDLE_NONE;
                    } else if (hit >= 0 && selectedSet.isEmpty()) {
                        selectedSet.add(hit);
                    }
                    if (hit < 0 && zoom > 1.001f) {
                        panningCanvas = true;
                        panStartX = panX;
                        panStartY = panY;
                    }
                }

                if (hasSelection()) {
                    SheetItem item = items.get(selected);
                    startItemX = item.x;
                    startItemY = item.y;
                    startItemW = item.w;
                    startItemH = item.h;
                }
                groupStartX.clear(); groupStartY.clear();
                for (SheetItem item : items) { groupStartX.add(item.x); groupStartY.add(item.y); }
                if (listener != null) listener.onSelectionChanged(hasSelection());
                invalidate();
                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                if (scaleDetector.isInProgress()) return true;
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;

                if (panningCanvas) {
                    if (!dragging && Math.hypot(dx, dy) > touchSlop) dragging = true;
                    if (dragging) {
                        panX = panStartX + dx;
                        panY = panStartY + dy;
                        clampPan();
                        invalidate();
                    }
                    return true;
                }

                if (!hasSelection()) return true;
                SheetItem primary = items.get(selected);
                if (primary.locked) return true;
                if (!dragging && Math.hypot(dx, dy) > touchSlop) {
                    dragging = true;
                    pushGestureUndoOnce();
                }
                if (dragging && pageRect.width() > 0 && pageRect.height() > 0) {
                    if (resizeHandle != HANDLE_NONE) {
                        resizeFromHandle(primary, dx, dy);
                        showGuideX = false;
                        showGuideY = false;
                    } else if (selectedSet.size() > 1) {
                        moveSelectedGroup(dx / pageRect.width(), dy / pageRect.height());
                    } else {
                        primary.x = clamp(startItemX + dx / pageRect.width(), 0f, 1f - primary.w);
                        primary.y = clamp(startItemY + dy / pageRect.height(), 0f, 1f - primary.h);
                        applySnap(primary);
                    }
                    invalidate();
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                showGuideX = false;
                showGuideY = false;
                if (multiSelectMode && !dragging && !panningCanvas && resizeHandle == HANDLE_NONE &&
                        downHitIndex >= 0 && downHitWasSelected && selectedSet.size() > 1) {
                    selectedSet.remove(downHitIndex);
                    if (selected == downHitIndex) selected = selectedSet.iterator().next();
                    if (listener != null) listener.onSelectionChanged(hasSelection());
                }
                if (dragging && gestureUndoPushed && listener != null) listener.onSheetChanged();
                if (dragging && resizeHandle != HANDLE_NONE && hasSelection()) {
                    SheetItem item = items.get(selected);
                    Diagnostics.log("LABEL_RESIZED_HANDLE", String.format(java.util.Locale.US,
                            "x=%.3f,y=%.3f,w=%.3f,h=%.3f", item.x, item.y, item.w, item.h));
                }
                if (panningCanvas && dragging) Diagnostics.log("CANVAS_PANNED", "zoom=" + getZoomPercent());
                dragging = false;
                panningCanvas = false;
                resizeHandle = HANDLE_NONE;
                gestureUndoPushed = false;
                invalidate();
                return true;
            }
        }
        return true;
    }

    private void moveSelectedGroup(float dx, float dy) {
        if (selectedSet.isEmpty()) return;
        float minDx = -Float.MAX_VALUE, maxDx = Float.MAX_VALUE;
        float minDy = -Float.MAX_VALUE, maxDy = Float.MAX_VALUE;
        for (int idx : selectedSet) {
            if (idx < 0 || idx >= items.size()) continue;
            SheetItem item = items.get(idx);
            if (item.locked) continue;
            float sx = groupStartX.get(idx), sy = groupStartY.get(idx);
            minDx = Math.max(minDx, -sx);
            maxDx = Math.min(maxDx, 1f - item.w - sx);
            minDy = Math.max(minDy, -sy);
            maxDy = Math.min(maxDy, 1f - item.h - sy);
        }
        dx = clamp(dx, minDx, maxDx); dy = clamp(dy, minDy, maxDy);
        for (int idx : selectedSet) {
            if (idx < 0 || idx >= items.size()) continue;
            SheetItem item = items.get(idx); if (item.locked) continue;
            item.x = groupStartX.get(idx) + dx; item.y = groupStartY.get(idx) + dy;
        }
        if (selected >= 0 && selected < items.size() && !items.get(selected).locked) {
            SheetItem primary = items.get(selected);
            float beforeX = primary.x, beforeY = primary.y;
            applySnap(primary);
            float snapDx = primary.x - beforeX, snapDy = primary.y - beforeY;
            if (Math.abs(snapDx) > 0.00001f || Math.abs(snapDy) > 0.00001f) {
                for (int idx : selectedSet) {
                    if (idx == selected || idx < 0 || idx >= items.size()) continue;
                    SheetItem item = items.get(idx); if (item.locked) continue;
                    item.x = clamp(item.x + snapDx, 0f, 1f - item.w);
                    item.y = clamp(item.y + snapDy, 0f, 1f - item.h);
                }
            }
        }
    }

    private void resizeFromHandle(SheetItem item, float screenDx, float screenDy) {
        // Convert finger movement back into the item's local axes so handles still feel correct after rotation.
        double rad = Math.toRadians(-item.rotation);
        float localDxPx = (float) (screenDx * Math.cos(rad) - screenDy * Math.sin(rad));
        float localDyPx = (float) (screenDx * Math.sin(rad) + screenDy * Math.cos(rad));

        if (resizeHandle == HANDLE_TL || resizeHandle == HANDLE_TR ||
                resizeHandle == HANDLE_BL || resizeHandle == HANDLE_BR) {
            resizeCornerProportional(item, localDxPx, localDyPx);
            return;
        }

        // Side handles intentionally change only one dimension.
        float dx = localDxPx / Math.max(1f, pageRect.width());
        float dy = localDyPx / Math.max(1f, pageRect.height());
        final float minW = 0.04f;
        final float minH = 0.03f;
        float left = startItemX;
        float top = startItemY;
        float right = startItemX + startItemW;
        float bottom = startItemY + startItemH;

        if (resizeHandle == HANDLE_LEFT) left += dx;
        if (resizeHandle == HANDLE_RIGHT) right += dx;
        if (resizeHandle == HANDLE_TOP) top += dy;
        if (resizeHandle == HANDLE_BOTTOM) bottom += dy;

        left = clamp(left, 0f, 1f);
        right = clamp(right, 0f, 1f);
        top = clamp(top, 0f, 1f);
        bottom = clamp(bottom, 0f, 1f);

        if (right - left < minW) {
            if (resizeHandle == HANDLE_LEFT) left = right - minW;
            else right = left + minW;
        }
        if (bottom - top < minH) {
            if (resizeHandle == HANDLE_TOP) top = bottom - minH;
            else bottom = top + minH;
        }

        left = clamp(left, 0f, 1f - minW);
        right = clamp(right, left + minW, 1f);
        top = clamp(top, 0f, 1f - minH);
        bottom = clamp(bottom, top + minH, 1f);
        item.x = left;
        item.y = top;
        item.w = right - left;
        item.h = bottom - top;
    }

    private void resizeCornerProportional(SheetItem item, float localDxPx, float localDyPx) {
        final float minW = 0.04f;
        final float minH = 0.03f;
        float pageW = Math.max(1f, pageRect.width());
        float pageH = Math.max(1f, pageRect.height());
        float startWPx = startItemW * pageW;
        float startHPx = startItemH * pageH;

        float vx = (resizeHandle == HANDLE_TL || resizeHandle == HANDLE_BL) ? -startWPx : startWPx;
        float vy = (resizeHandle == HANDLE_TL || resizeHandle == HANDLE_TR) ? -startHPx : startHPx;
        float nvx = vx + localDxPx;
        float nvy = vy + localDyPx;
        float denom = vx * vx + vy * vy;
        float scale = denom <= 0.001f ? 1f : (nvx * vx + nvy * vy) / denom;

        float minScale = Math.max(minW / Math.max(startItemW, 0.0001f), minH / Math.max(startItemH, 0.0001f));
        float anchorX;
        float anchorY;
        float maxScaleX;
        float maxScaleY;

        if (resizeHandle == HANDLE_TL) {
            anchorX = startItemX + startItemW; anchorY = startItemY + startItemH;
            maxScaleX = anchorX / startItemW; maxScaleY = anchorY / startItemH;
        } else if (resizeHandle == HANDLE_TR) {
            anchorX = startItemX; anchorY = startItemY + startItemH;
            maxScaleX = (1f - anchorX) / startItemW; maxScaleY = anchorY / startItemH;
        } else if (resizeHandle == HANDLE_BL) {
            anchorX = startItemX + startItemW; anchorY = startItemY;
            maxScaleX = anchorX / startItemW; maxScaleY = (1f - anchorY) / startItemH;
        } else {
            anchorX = startItemX; anchorY = startItemY;
            maxScaleX = (1f - anchorX) / startItemW; maxScaleY = (1f - anchorY) / startItemH;
        }
        float maxScale = Math.max(minScale, Math.min(maxScaleX, maxScaleY));
        scale = clamp(scale, minScale, maxScale);

        float newW = startItemW * scale;
        float newH = startItemH * scale;
        if (resizeHandle == HANDLE_TL) { item.x = anchorX - newW; item.y = anchorY - newH; }
        else if (resizeHandle == HANDLE_TR) { item.x = anchorX; item.y = anchorY - newH; }
        else if (resizeHandle == HANDLE_BL) { item.x = anchorX - newW; item.y = anchorY; }
        else { item.x = anchorX; item.y = anchorY; }
        item.w = newW;
        item.h = newH;
    }

    private int hitResizeHandle(float x, float y) {
        if (!hasSelection()) return HANDLE_NONE;
        SheetItem item = items.get(selected);
        if (item.locked) return HANDLE_NONE;
        RectF r = itemRect(item, pageRect);
        float[][] pts = new float[][]{
                {r.left, r.centerY(), HANDLE_LEFT}, {r.right, r.centerY(), HANDLE_RIGHT},
                {r.centerX(), r.top, HANDLE_TOP}, {r.centerX(), r.bottom, HANDLE_BOTTOM},
                {r.left, r.top, HANDLE_TL}, {r.right, r.top, HANDLE_TR},
                {r.left, r.bottom, HANDLE_BL}, {r.right, r.bottom, HANDLE_BR}
        };
        float radius = 18f * getResources().getDisplayMetrics().density;
        for (float[] p : pts) {
            float[] rp = rotatePoint(p[0], p[1], r.centerX(), r.centerY(), item.rotation);
            if (Math.hypot(x - rp[0], y - rp[1]) <= radius) return (int) p[2];
        }
        return HANDLE_NONE;
    }

    private static float[] rotatePoint(float x, float y, float cx, float cy, float degrees) {
        double rad = Math.toRadians(degrees);
        float dx = x - cx;
        float dy = y - cy;
        return new float[]{
                cx + (float) (dx * Math.cos(rad) - dy * Math.sin(rad)),
                cy + (float) (dx * Math.sin(rad) + dy * Math.cos(rad))
        };
    }

    private void applySnap(SheetItem item) {
        // Snap the moving picture's left/center/right and top/center/bottom to:
        // page edges/centers and every other picture's edges/centers.
        final float thresholdPx = 9f * getResources().getDisplayMetrics().density;
        final float thresholdX = pageRect.width() > 0 ? thresholdPx / pageRect.width() : 0.012f;
        final float thresholdY = pageRect.height() > 0 ? thresholdPx / pageRect.height() : 0.012f;
        showGuideX = false;
        showGuideY = false;

        float[] movingX = new float[]{item.x, item.x + item.w / 2f, item.x + item.w};
        float[] movingXOffset = new float[]{0f, item.w / 2f, item.w};
        float[] movingY = new float[]{item.y, item.y + item.h / 2f, item.y + item.h};
        float[] movingYOffset = new float[]{0f, item.h / 2f, item.h};

        float bestDx = Float.MAX_VALUE;
        float bestTargetX = 0f;
        float bestOffsetX = 0f;
        float bestDy = Float.MAX_VALUE;
        float bestTargetY = 0f;
        float bestOffsetY = 0f;

        ArrayList<Float> xTargets = new ArrayList<>();
        ArrayList<Float> yTargets = new ArrayList<>();
        xTargets.add(0f); xTargets.add(0.5f); xTargets.add(1f);
        yTargets.add(0f); yTargets.add(0.5f); yTargets.add(1f);
        for (int i = 0; i < items.size(); i++) {
            SheetItem other = items.get(i);
            if (other == item) continue;
            if (selectedSet.size() > 1 && selectedSet.contains(i)) continue;
            xTargets.add(other.x);
            xTargets.add(other.x + other.w / 2f);
            xTargets.add(other.x + other.w);
            yTargets.add(other.y);
            yTargets.add(other.y + other.h / 2f);
            yTargets.add(other.y + other.h);
        }

        for (int mi = 0; mi < movingX.length; mi++) {
            for (float target : xTargets) {
                float diff = Math.abs(movingX[mi] - target);
                if (diff < bestDx) {
                    bestDx = diff;
                    bestTargetX = target;
                    bestOffsetX = movingXOffset[mi];
                }
            }
        }
        for (int mi = 0; mi < movingY.length; mi++) {
            for (float target : yTargets) {
                float diff = Math.abs(movingY[mi] - target);
                if (diff < bestDy) {
                    bestDy = diff;
                    bestTargetY = target;
                    bestOffsetY = movingYOffset[mi];
                }
            }
        }

        if (bestDx <= thresholdX) {
            item.x = clamp(bestTargetX - bestOffsetX, 0f, 1f - item.w);
            guideXNorm = bestTargetX;
            showGuideX = true;
        }
        if (bestDy <= thresholdY) {
            item.y = clamp(bestTargetY - bestOffsetY, 0f, 1f - item.h);
            guideYNorm = bestTargetY;
            showGuideY = true;
        }
    }

    private void pushGestureUndoOnce() {
        if (!gestureUndoPushed && listener != null) {
            listener.onEditStarted();
            gestureUndoPushed = true;
        }
    }

    private int hitTest(float x, float y) {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) indices.add(i);
        Collections.reverse(indices);
        for (int i : indices) {
            SheetItem item = items.get(i);
            RectF r = itemRect(item, pageRect);
            float[] local = rotatePoint(x, y, r.centerX(), r.centerY(), -item.rotation);
            if (r.contains(local[0], local[1])) return i;
        }
        return -1;
    }

    private Bitmap decodeBitmap(Uri uri, int maxDimension) throws Exception {
        ContentResolver resolver = getContext().getContentResolver();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = resolver.openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        int sample = 1;
        int max = Math.max(bounds.outWidth, bounds.outHeight);
        while (max / sample > maxDimension && sample < 32) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try (InputStream in = resolver.openInputStream(uri)) {
            return BitmapFactory.decodeStream(in, null, opts);
        }
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
