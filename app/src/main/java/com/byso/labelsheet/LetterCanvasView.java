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
        default void onCropModeChanged(boolean cropping) {}
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
    private float defaultNewImageBorderPt = 0f;

    /** Default border applies only when inserting a new image; existing labels keep their own style. */
    public void setDefaultNewImageBorder(float points) {
        defaultNewImageBorderPt = clamp(points, 0f, 24f);
    }

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
    // Long-press a picture to start picking several (then tap others to add/remove).
    private boolean longPressFired = false;
    private final Runnable longPress = () -> {
        if (dragging || panningCanvas || this.cropMode || downHitIndex < 0 || downHitIndex >= items.size()) return;
        longPressFired = true;
        multiSelectMode = true;
        selectedSet.clear();
        selectedSet.add(downHitIndex);
        selected = downHitIndex;
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        Diagnostics.log("MULTI_SELECT_MODE", "on (long-press)");
        invalidate();
        if (listener != null) listener.onSelectionChanged(true);
    };
    private boolean showGuideX = false;
    private boolean showGuideY = false;
    private float guideXNorm = 0.5f;
    private float guideYNorm = 0.5f;
    private final float touchSlop;

    // Side parking area around the page, in page-size units. Pictures parked there are never printed.
    static final float BOARD_X = 0.85f;
    static final float BOARD_Y = 0.45f;
    private static final float MIN_ZOOM = 0.35f;
    private static final float MAX_ZOOM = 4.0f;
    private final Paint boardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boardEdgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint captionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emptyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint warnPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dimPaint = new Paint();

    private static float minX(SheetItem i) { return -BOARD_X; }
    private static float maxX(SheetItem i) { return 1f + BOARD_X - i.w; }
    private static float minY(SheetItem i) { return -BOARD_Y; }
    private static float maxY(SheetItem i) { return 1f + BOARD_Y - i.h; }

    /** Keeps a picture inside the page + side area (it may sit fully off the page). */
    private static void clampToBoard(SheetItem i) {
        i.x = clamp(i.x, minX(i), maxX(i));
        i.y = clamp(i.y, minY(i), maxY(i));
    }

    /** Fully inside the printable Letter page. */
    static boolean isFullyOnPage(SheetItem i) {
        float e = 0.0005f;
        return i.x >= -e && i.y >= -e && i.x + i.w <= 1f + e && i.y + i.h <= 1f + e;
    }

    /** Touches the page at all (will at least partly print). */
    static boolean touchesPage(SheetItem i) {
        return i.x < 1f && i.x + i.w > 0f && i.y < 1f && i.y + i.h > 0f;
    }

    public int getOnPageCount() { int n = 0; for (SheetItem i : items) if (touchesPage(i)) n++; return n; }
    public int getOffPageCount() { return items.size() - getOnPageCount(); }
    public int getCrossingEdgeCount() { int n = 0; for (SheetItem i : items) if (touchesPage(i) && !isFullyOnPage(i)) n++; return n; }
    public boolean isSelectedOffPage() { return hasSelection() && !touchesPage(items.get(selected)); }
    public boolean isSelectionMultiple() { return selectedSet.size() > 1; }

    // ───── On-page crop mode ─────
    // While cropping, the whole original picture is shown faintly and the kept area is framed with handles.
    // Dragging an edge/corner trims; dragging inside slides the frame over the picture. The picture never
    // changes scale: the label box shrinks/grows with the crop, so nothing is stretched.
    private static final float MIN_CROP_KEEP = 0.05f;
    private boolean cropMode = false;
    private int cropIndex = -1;
    private float[] cropStart;      // x,y,w,h,cl,ct,cr,cb when crop mode started (for Cancel)
    private float[] cropDown;       // same, captured at finger down
    private float[] cropFull;       // full-picture frame fx,fy,fw,fh (fixed during a gesture)
    private int cropHandle = HANDLE_NONE;
    private static final int HANDLE_MOVE = 99;
    private boolean cropGestureActive = false;
    private final Paint ghostPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint veilPaint = new Paint();
    private final Paint frameWhite = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint frameAccent = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thirdsPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public boolean isCropMode() { return cropMode; }

    private static float[] snapshot(SheetItem i) {
        return new float[]{i.x, i.y, i.w, i.h, i.cropLeft, i.cropTop, i.cropRight, i.cropBottom};
    }

    private static void restore(SheetItem i, float[] v) {
        i.x = v[0]; i.y = v[1]; i.w = v[2]; i.h = v[3];
        i.cropLeft = v[4]; i.cropTop = v[5]; i.cropRight = v[6]; i.cropBottom = v[7];
    }

    /** Frame the uncropped picture would occupy at the current scale. */
    private static float[] fullFrame(SheetItem i) {
        float kx = Math.max(0.01f, 1f - i.cropLeft - i.cropRight);
        float ky = Math.max(0.01f, 1f - i.cropTop - i.cropBottom);
        float fw = i.w / kx, fh = i.h / ky;
        return new float[]{i.x - i.cropLeft * fw, i.y - i.cropTop * fh, fw, fh};
    }

    /** Applies crop fractions while keeping the picture's scale and position fixed. */
    private static void setCropKeepingScale(SheetItem i, float[] full, float l, float t, float r, float b) {
        i.cropLeft = l; i.cropTop = t; i.cropRight = r; i.cropBottom = b;
        i.x = full[0] + l * full[2];
        i.y = full[1] + t * full[3];
        i.w = full[2] * (1f - l - r);
        i.h = full[3] * (1f - t - b);
    }

    public boolean startCrop() {
        if (!hasSelection() || items.get(selected).locked || items.get(selected).bitmap == null) return false;
        if (multiSelectMode) { multiSelectMode = false; }
        cropIndex = selected;
        selectedSet.clear(); selectedSet.add(selected);
        cropStart = snapshot(items.get(cropIndex));
        cropMode = true;
        Diagnostics.log("CROP_MODE", "on");
        invalidate();
        if (listener != null) { listener.onCropModeChanged(true); listener.onSelectionChanged(true); }
        return true;
    }

    /** Undoes all trimming on the picture being cropped (shows the whole picture again). */
    public void resetCrop() {
        if (!cropMode || cropIndex < 0 || cropIndex >= items.size()) return;
        SheetItem it = items.get(cropIndex);
        float[] f = fullFrame(it);
        setCropKeepingScale(it, f, 0f, 0f, 0f, 0f);
        // Also restore the picture's true proportions (fixes pictures stretched by the old crop tool).
        if (it.bitmap != null && it.bitmap.getHeight() > 0) {
            float pw = portrait ? 8.5f : 11f, ph = portrait ? 11f : 8.5f;
            float aspect = it.bitmap.getWidth() / (float) it.bitmap.getHeight();
            float cy = it.y + it.h / 2f;
            it.h = (it.w * pw / aspect) / ph;
            if (it.h > 1f + 2f * BOARD_Y) { float k = (1f + 2f * BOARD_Y) / it.h; it.h *= k; it.w *= k; }
            it.y = cy - it.h / 2f;
        }
        invalidate();
    }

    /** Leaves crop mode. apply=false restores the picture. Returns true if the picture changed. */
    public boolean finishCrop(boolean apply) {
        if (!cropMode) return false;
        boolean changed = false;
        if (cropIndex >= 0 && cropIndex < items.size()) {
            SheetItem it = items.get(cropIndex);
            if (!apply) restore(it, cropStart);
            else {
                clampToBoard(it);
                float[] now = snapshot(it);
                for (int k = 0; k < now.length; k++) if (Math.abs(now[k] - cropStart[k]) > 0.0001f) changed = true;
            }
        }
        cropMode = false;
        cropIndex = -1;
        cropGestureActive = false;
        Diagnostics.log("CROP_MODE", apply ? (changed ? "applied" : "unchanged") : "cancelled");
        invalidate();
        if (listener != null) {
            listener.onCropModeChanged(false);
            if (changed) listener.onSheetChanged();
            listener.onSelectionChanged(hasSelection());
        }
        return changed;
    }

    private void drawCropMode(Canvas canvas) {
        if (cropIndex < 0 || cropIndex >= items.size()) return;
        SheetItem it = items.get(cropIndex);
        if (it.bitmap == null) return;
        float d = getResources().getDisplayMetrics().density;
        RectF dst = itemRect(it, pageRect);
        float[] f = fullFrame(it);
        RectF full = new RectF(pageRect.left + f[0] * pageRect.width(), pageRect.top + f[1] * pageRect.height(),
                pageRect.left + (f[0] + f[2]) * pageRect.width(), pageRect.top + (f[1] + f[3]) * pageRect.height());
        canvas.save();
        canvas.rotate(it.rotation, dst.centerX(), dst.centerY());
        // whole picture, faint, with the trimmed-away parts darkened
        Matrix m = new Matrix();
        m.setRectToRect(new RectF(0, 0, it.bitmap.getWidth(), it.bitmap.getHeight()), full, Matrix.ScaleToFit.FILL);
        canvas.drawBitmap(it.bitmap, m, ghostPaint);
        canvas.drawRect(full.left, full.top, full.right, dst.top, veilPaint);
        canvas.drawRect(full.left, dst.bottom, full.right, full.bottom, veilPaint);
        canvas.drawRect(full.left, dst.top, dst.left, dst.bottom, veilPaint);
        canvas.drawRect(dst.right, dst.top, full.right, dst.bottom, veilPaint);
        canvas.restore();

        drawItem(canvas, it, pageRect, false, false);

        canvas.save();
        canvas.rotate(it.rotation, dst.centerX(), dst.centerY());
        for (int k = 1; k <= 2; k++) {
            float gx = dst.left + dst.width() * k / 3f, gy = dst.top + dst.height() * k / 3f;
            canvas.drawLine(gx, dst.top, gx, dst.bottom, thirdsPaint);
            canvas.drawLine(dst.left, gy, dst.right, gy, thirdsPaint);
        }
        canvas.drawRect(dst, frameWhite);
        canvas.drawRect(dst, frameAccent);
        // corner brackets + edge bars: the parts you grab
        float L = Math.min(18f * d, Math.min(dst.width(), dst.height()) / 3f);
        Paint grip = new Paint(Paint.ANTI_ALIAS_FLAG);
        grip.setStyle(Paint.Style.STROKE);
        grip.setStrokeCap(Paint.Cap.ROUND);
        grip.setStrokeWidth(5f * d);
        grip.setColor(Color.WHITE);
        grip.setShadowLayer(3f * d, 0f, 1f * d, 0x66000000);
        float l = dst.left, t = dst.top, r = dst.right, b = dst.bottom, cx = dst.centerX(), cy = dst.centerY();
        float[][] lines = {
                {l, t, l + L, t}, {l, t, l, t + L}, {r, t, r - L, t}, {r, t, r, t + L},
                {l, b, l + L, b}, {l, b, l, b - L}, {r, b, r - L, b}, {r, b, r, b - L},
                {cx - L / 2f, t, cx + L / 2f, t}, {cx - L / 2f, b, cx + L / 2f, b},
                {l, cy - L / 2f, l, cy + L / 2f}, {r, cy - L / 2f, r, cy + L / 2f}};
        for (float[] ln : lines) canvas.drawLine(ln[0], ln[1], ln[2], ln[3], grip);
        canvas.restore();
    }

    private boolean onCropTouch(MotionEvent event) {
        if (cropIndex < 0 || cropIndex >= items.size()) return true;
        SheetItem it = items.get(cropIndex);
        RectF dst = itemRect(it, pageRect);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downX = event.getX(); downY = event.getY();
                float[] p = rotatePoint(downX, downY, dst.centerX(), dst.centerY(), -it.rotation);
                float radius = 24f * getResources().getDisplayMetrics().density;
                float[][] hs = {
                        {dst.left, dst.top, HANDLE_TL}, {dst.right, dst.top, HANDLE_TR},
                        {dst.left, dst.bottom, HANDLE_BL}, {dst.right, dst.bottom, HANDLE_BR},
                        {dst.centerX(), dst.top, HANDLE_TOP}, {dst.centerX(), dst.bottom, HANDLE_BOTTOM},
                        {dst.left, dst.centerY(), HANDLE_LEFT}, {dst.right, dst.centerY(), HANDLE_RIGHT}};
                cropHandle = HANDLE_NONE;
                float best = radius;
                for (float[] h : hs) {
                    float dd = (float) Math.hypot(p[0] - h[0], p[1] - h[1]);
                    if (dd <= best) { best = dd; cropHandle = (int) h[2]; }
                }
                // Grab an edge anywhere along its length, not only at the bar.
                if (cropHandle == HANDLE_NONE) {
                    float edge = 16f * getResources().getDisplayMetrics().density;
                    boolean inY = p[1] > dst.top && p[1] < dst.bottom, inX = p[0] > dst.left && p[0] < dst.right;
                    if (inY && Math.abs(p[0] - dst.left) < edge) cropHandle = HANDLE_LEFT;
                    else if (inY && Math.abs(p[0] - dst.right) < edge) cropHandle = HANDLE_RIGHT;
                    else if (inX && Math.abs(p[1] - dst.top) < edge) cropHandle = HANDLE_TOP;
                    else if (inX && Math.abs(p[1] - dst.bottom) < edge) cropHandle = HANDLE_BOTTOM;
                    else if (dst.contains(p[0], p[1])) cropHandle = HANDLE_MOVE;
                }
                cropGestureActive = true;
                cropDown = snapshot(it);
                cropFull = fullFrame(it);
                if (cropHandle == HANDLE_NONE) {
                    // Tap outside the picture: finish cropping.
                    float[] f = cropFull;
                    RectF full = new RectF(pageRect.left + f[0] * pageRect.width(), pageRect.top + f[1] * pageRect.height(),
                            pageRect.left + (f[0] + f[2]) * pageRect.width(), pageRect.top + (f[1] + f[3]) * pageRect.height());
                    if (!full.contains(p[0], p[1])) { cropGestureActive = false; finishCrop(true); }
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!cropGestureActive || cropHandle == HANDLE_NONE || scaleDetector.isInProgress()) return true;
                double rad = Math.toRadians(-it.rotation);
                float sdx = event.getX() - downX, sdy = event.getY() - downY;
                float ldx = (float) (sdx * Math.cos(rad) - sdy * Math.sin(rad));
                float ldy = (float) (sdx * Math.sin(rad) + sdy * Math.cos(rad));
                float fx = ldx / Math.max(1f, pageRect.width()) / Math.max(0.0001f, cropFull[2]);
                float fy = ldy / Math.max(1f, pageRect.height()) / Math.max(0.0001f, cropFull[3]);
                float l = cropDown[4], t = cropDown[5], r = cropDown[6], b = cropDown[7];
                if (cropHandle == HANDLE_MOVE) {
                    float mx = clamp(fx, -l, r), my = clamp(fy, -t, b);
                    l += mx; r -= mx; t += my; b -= my;
                } else {
                    if (cropHandle == HANDLE_LEFT || cropHandle == HANDLE_TL || cropHandle == HANDLE_BL)
                        l = clamp(l + fx, 0f, 1f - r - MIN_CROP_KEEP);
                    if (cropHandle == HANDLE_RIGHT || cropHandle == HANDLE_TR || cropHandle == HANDLE_BR)
                        r = clamp(r - fx, 0f, 1f - l - MIN_CROP_KEEP);
                    if (cropHandle == HANDLE_TOP || cropHandle == HANDLE_TL || cropHandle == HANDLE_TR)
                        t = clamp(t + fy, 0f, 1f - b - MIN_CROP_KEEP);
                    if (cropHandle == HANDLE_BOTTOM || cropHandle == HANDLE_BL || cropHandle == HANDLE_BR)
                        b = clamp(b - fy, 0f, 1f - t - MIN_CROP_KEEP);
                }
                setCropKeepingScale(it, cropFull, l, t, r, b);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                cropGestureActive = false;
                cropHandle = HANDLE_NONE;
                if (listener != null) listener.onSelectionChanged(true);
                return true;
        }
        return true;
    }

    public LetterCanvasView(Context context) {
        this(context, null);
    }

    public LetterCanvasView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        touchSlop = 4f * getResources().getDisplayMetrics().density;

        pagePaint.setColor(Color.WHITE);
        pagePaint.setShadowLayer(26f * getResources().getDisplayMetrics().density / 2f, 0f, 9f * getResources().getDisplayMetrics().density, 0x66000000);

        selectedPaint.setStyle(Paint.Style.STROKE);
        selectedPaint.setStrokeWidth(2.2f * getResources().getDisplayMetrics().density);
        selectedPaint.setColor(Theme.SELECT);
        selectedPaint.setStrokeWidth(3f * getResources().getDisplayMetrics().density);

        safePaint.setStyle(Paint.Style.STROKE);
        safePaint.setStrokeWidth(1f * getResources().getDisplayMetrics().density);
        safePaint.setColor(0x556C7A89);
        safePaint.setPathEffect(new DashPathEffect(new float[]{8f, 8f}, 0f));

        guidePaint.setStyle(Paint.Style.STROKE);
        guidePaint.setStrokeWidth(1.3f * getResources().getDisplayMetrics().density);
        guidePaint.setColor(Theme.SELECT);

        float d = getResources().getDisplayMetrics().density;
        boardPaint.setColor(Theme.BOARD);
        boardEdgePaint.setStyle(Paint.Style.STROKE);
        boardEdgePaint.setStrokeWidth(1.2f * d);
        boardEdgePaint.setColor(Theme.BOARD_EDGE);
        boardEdgePaint.setAlpha(110);
        captionPaint.setColor(Theme.CAPTION);
        captionPaint.setTextSize(11.5f * d);
        captionPaint.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));
        emptyPaint.setColor(0xFF8A9AB0);
        emptyPaint.setTextSize(15f * d);
        emptyPaint.setTextAlign(Paint.Align.CENTER);
        warnPaint.setStyle(Paint.Style.STROKE);
        warnPaint.setStrokeWidth(2.2f * d);
        warnPaint.setColor(0xFFF59E0B);
        warnPaint.setPathEffect(new DashPathEffect(new float[]{7f * d, 4f * d}, 0f));
        badgePaint.setColor(0xE6334155);
        badgeTextPaint.setColor(Color.WHITE);
        badgeTextPaint.setTextSize(10.5f * d);
        badgeTextPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        dimPaint.setColor(0x33FFFFFF);
        ghostPaint.setAlpha(110);
        veilPaint.setColor(0x80101828);
        frameWhite.setStyle(Paint.Style.STROKE);
        frameWhite.setStrokeWidth(3.5f * d);
        frameWhite.setColor(Color.WHITE);
        frameAccent.setStyle(Paint.Style.STROKE);
        frameAccent.setStrokeWidth(1.5f * d);
        frameAccent.setColor(Theme.SELECT);
        thirdsPaint.setStyle(Paint.Style.STROKE);
        thirdsPaint.setStrokeWidth(1f * d);
        thirdsPaint.setColor(0x99FFFFFF);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                panningCanvas = false;
                return true;
            }

            @Override public boolean onScale(ScaleGestureDetector detector) {
                float oldZoom = zoom;
                float newZoom = clamp(oldZoom * detector.getScaleFactor(), MIN_ZOOM, MAX_ZOOM);
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
        zoom = clamp(value, MIN_ZOOM, MAX_ZOOM);
        clampPan();
        invalidate();
        if (listener != null) listener.onSelectionChanged(hasSelection());
    }

    public void zoomBy(float factor) { setZoom(zoom * factor); }

    /** Fits the page, and zooms out just enough to also show any pictures parked beside it. */
    public void fitPage() {
        float l = 0f, t = 0f, r = 1f, b = 1f;
        for (SheetItem i : items) {
            l = Math.min(l, i.x); t = Math.min(t, i.y);
            r = Math.max(r, i.x + i.w); b = Math.max(b, i.y + i.h);
        }
        float need = Math.max((r - l), (b - t));
        float baseW = getBasePageWidth(), baseH = getBasePageHeight();
        float fitW = getWidth() > 0 ? (getWidth() - 24f * getResources().getDisplayMetrics().density) / Math.max(1f, (r - l) * baseW) : 1f;
        float fitH = getHeight() > 0 ? (getHeight() - 24f * getResources().getDisplayMetrics().density) / Math.max(1f, (b - t) * baseH) : 1f;
        zoom = need <= 1.0001f ? 1f : clamp(Math.min(1f, Math.min(fitW, fitH)), MIN_ZOOM, 1f);
        panX = -((l + r) / 2f - 0.5f) * baseW * zoom;
        panY = -((t + b) / 2f - 0.5f) * baseH * zoom;
        clampPan();
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

    public void selectAll() {
        if (items.isEmpty()) return;
        multiSelectMode = true;
        selectedSet.clear();
        for (int i = 0; i < items.size(); i++) selectedSet.add(i);
        selected = items.size() - 1;
        invalidate();
        if (listener != null) listener.onSelectionChanged(true);
    }

    public boolean hasBorderOnSelected() { return hasSelection() && items.get(selected).borderPt > 0.01f; }

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
            item.borderPt = defaultNewImageBorderPt;
            item.x = clamp(item.x, -BOARD_X, 1f + BOARD_X - item.w);
            item.y = clamp(item.y, -BOARD_Y, 1f + BOARD_Y - item.h);
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
        copy.x = clamp(copy.x + 0.035f, -BOARD_X, 1f + BOARD_X - copy.w);
        copy.y = clamp(copy.y + 0.035f, -BOARD_Y, 1f + BOARD_Y - copy.h);
        items.add(copy);
        selected = items.size() - 1;
        selectedSet.clear();
        selectedSet.add(selected);
        Diagnostics.log("LABEL_DUPLICATED", "labels=" + items.size());
        invalidate();
        if (listener != null) listener.onSheetChanged();
        return true;
    }

    /**
     * Moves the selected picture(s) into the side area beside the page (not printed),
     * or, if they are already parked, back onto the page. Returns 1 = parked, 2 = placed on page, 0 = nothing changed.
     */
    public int toggleSelectedParking() {
        if (!hasSelection()) return 0;
        ArrayList<Integer> targets = new ArrayList<>();
        if (selectedSet.isEmpty()) targets.add(selected); else targets.addAll(selectedSet);
        boolean park = touchesPage(items.get(selected));
        int changed = 0;
        if (park) {
            // Stack parked pictures down the right-hand side area, below anything already parked there.
            float x = 1f + 0.06f;
            float y = 0f;
            for (SheetItem it : items) {
                if (!touchesPage(it) && it.x >= 1f) y = Math.max(y, it.y + it.h + 0.02f);
            }
            for (int idx : targets) {
                if (idx < 0 || idx >= items.size()) continue;
                SheetItem it = items.get(idx);
                if (it.locked) continue;
                // Never resize: a label must come back at the same printed size.
                if (it.w > BOARD_X - 0.08f) {
                    // Too wide for the side strip: park it below the page instead.
                    it.x = 0f;
                    it.y = 1f + 0.04f;
                    clampToBoard(it);
                    changed++;
                    continue;
                }
                if (y + it.h > 1f + BOARD_Y) { y = 0f; x = -BOARD_X + 0.04f; }
                it.x = x; it.y = y;
                clampToBoard(it);
                y += it.h + 0.02f;
                changed++;
            }
        } else {
            float off = 0f;
            for (int idx : targets) {
                if (idx < 0 || idx >= items.size()) continue;
                SheetItem it = items.get(idx);
                if (it.locked) continue;
                it.x = clamp(0.5f - it.w / 2f + off, 0f, Math.max(0f, 1f - it.w));
                it.y = clamp(0.5f - it.h / 2f + off, 0f, Math.max(0f, 1f - it.h));
                off += 0.03f;
                changed++;
            }
        }
        if (changed == 0) return 0;
        Diagnostics.log(park ? "LABEL_PARKED" : "LABEL_UNPARKED", "count=" + changed);
        invalidate();
        if (listener != null) { listener.onSelectionChanged(true); listener.onSheetChanged(); }
        return park ? 1 : 2;
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
        item.x = clamp(item.x, -BOARD_X, 1f + BOARD_X - item.w);
        item.y = clamp(item.y, -BOARD_Y, 1f + BOARD_Y - item.h);
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
            item.x = clamp(cx - item.w / 2f, -BOARD_X, 1f + BOARD_X - item.w);
            item.y = clamp(cy - item.h / 2f, -BOARD_Y, 1f + BOARD_Y - item.h);
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
            item.x = clamp(item.x, -BOARD_X, 1f + BOARD_X - item.w);
            item.y = clamp(item.y, -BOARD_Y, 1f + BOARD_Y - item.h);
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
            item.x = clamp(cx - newW / 2f, -BOARD_X, 1f + BOARD_X - newW);
            item.y = clamp(cy - newH / 2f, -BOARD_Y, 1f + BOARD_Y - newH);
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
                item.x = clamp(cx - w / 2f, -BOARD_X, 1f + BOARD_X - w);
                item.y = clamp(cy - h / 2f, -BOARD_Y, 1f + BOARD_Y - h);
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
        item.x = clamp(cx - newW / 2f, -BOARD_X, 1f + BOARD_X - newW);
        item.y = clamp(cy - newH / 2f, -BOARD_Y, 1f + BOARD_Y - newH);
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
            item.x = clamp(item.x + dx, -BOARD_X, 1f + BOARD_X - item.w);
            item.y = clamp(item.y + dy, -BOARD_Y, 1f + BOARD_Y - item.h);
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
            copy.x = clamp(source.x + off, -BOARD_X, 1f + BOARD_X - copy.w);
            copy.y = clamp(source.y + off, -BOARD_Y, 1f + BOARD_Y - copy.h);
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
        // Replace what is on the page; pictures parked in the side area stay where they are.
        for (int i = items.size() - 1; i >= 0; i--) {
            SheetItem it = items.get(i);
            if (touchesPage(it) && !it.locked) items.remove(i);
        }
        for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) {
            SheetItem copy = source.copy(); copy.locked = false;
            copy.x = margin + c * (source.w + gapX); copy.y = margin + r * (source.h + gapY);
            if (copy.x + copy.w <= 1f - margin + 0.001f && copy.y + copy.h <= 1f - margin + 0.001f) items.add(copy);
        }
        selected = -1; selectedSet.clear();
        if (listener != null) listener.onSelectionChanged(false);
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
            clampToBoard(i);
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
        // Only pictures touching the page are arranged; parked and locked pictures are left alone.
        ArrayList<SheetItem> targets = new ArrayList<>();
        for (SheetItem it : items) if (touchesPage(it) && !it.locked) targets.add(it);
        int n = targets.size();
        if (n == 0) return;
        int cols = n <= 2 ? 1 : (n <= 8 ? 2 : 3);
        int rows = (int) Math.ceil(n / (double) cols);
        float margin = 0.045f;
        float gap = 0.025f;
        float cellW = (1f - margin * 2f - gap * (cols - 1)) / cols;
        float cellH = (1f - margin * 2f - gap * (rows - 1)) / rows;
        float pageAspect = portrait ? (8.5f / 11f) : (11f / 8.5f);

        for (int i = 0; i < n; i++) {
            SheetItem item = targets.get(i);
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
        String where = !touchesPage(item) ? " • off page (won't print)" : (!isFullyOnPage(item) ? " • crosses page edge" : "");
        return String.format(java.util.Locale.US, "%.2f × %.2f in%s%s", item.w * pageW, item.h * pageH, item.locked ? " • Locked" : "", where);
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
        canvas.save();
        canvas.clipRect(target);
        for (SheetItem item : items) {
            if (item.bitmap == null || !touchesPage(item)) continue; // side-area pictures are never printed
            drawItem(canvas, item, target, false, false);
        }
        canvas.restore();
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

        // Side parking area: everything here stays on screen but is never printed.
        float d = getResources().getDisplayMetrics().density;
        RectF board = new RectF(pageRect.left - BOARD_X * pw, pageRect.top - BOARD_Y * ph,
                pageRect.right + BOARD_X * pw, pageRect.bottom + BOARD_Y * ph);
        drawRecessed(canvas, board, 18f * d);
        String cap = "SIDE AREA • NOT PRINTED";
        float capW = captionPaint.measureText(cap);
        float visibleRight = Math.min(board.right, getWidth());
        if (visibleRight - pageRect.right >= capW + 28f * d) {
            canvas.drawText(cap, pageRect.right + 14f * d, Math.max(board.top, 0f) + 22f * d, captionPaint);
        } else if (Math.min(board.bottom, getHeight()) - pageRect.bottom >= 30f * d) {
            canvas.drawText(cap, pageRect.left, pageRect.bottom + 22f * d, captionPaint);
        }

        canvas.drawRect(pageRect, pagePaint);

        float safeX = pageRect.width() * (0.25f / (portrait ? 8.5f : 11f));
        float safeY = pageRect.height() * (0.25f / (portrait ? 11f : 8.5f));
        RectF safe = new RectF(pageRect.left + safeX, pageRect.top + safeY,
                pageRect.right - safeX, pageRect.bottom - safeY);
        canvas.drawRect(safe, safePaint);

        for (int i = 0; i < items.size(); i++) {
            SheetItem item = items.get(i);
            if (item.bitmap == null) continue;
            if (cropMode && i == cropIndex) continue;
            boolean selectedState = selectedSet.contains(i) || i == selected;
            RectF sr = itemRect(item, pageRect);
            canvas.save();
            canvas.rotate(item.rotation, sr.centerX(), sr.centerY());
            canvas.drawRect(sr, itemShadowPaint); // on-screen depth only; never printed
            canvas.restore();
            drawItem(canvas, item, pageRect, selectedState, i == selected && !item.locked);
            if (multiSelectMode && selectedSet.contains(i)) drawPickBadge(canvas, sr.right, sr.top);
            RectF r = itemRect(item, pageRect);
            if (!touchesPage(item)) {
                // Parked: veil + "Not printed" tag so it is obvious at a glance.
                canvas.save();
                canvas.rotate(item.rotation, r.centerX(), r.centerY());
                canvas.drawRect(r, dimPaint);
                canvas.restore();
                drawBadge(canvas, "NOT PRINTED", r.left, r.top);
            } else if (!isFullyOnPage(item)) {
                canvas.save();
                canvas.rotate(item.rotation, r.centerX(), r.centerY());
                canvas.drawRect(r, warnPaint);
                canvas.restore();
                drawBadge(canvas, "EDGE CUT OFF", r.left, r.top);
            }
            if (item.locked) drawBadge(canvas, "LOCKED", r.left, r.bottom - 20f * d);
        }

        if (cropMode) drawCropMode(canvas);

        if (items.isEmpty()) {
            float cy = pageRect.centerY();
            canvas.drawText("Your page is empty", pageRect.centerX(), cy - 6f * d, emptyPaint);
            Paint small = new Paint(emptyPaint);
            small.setTextSize(12.5f * d);
            canvas.drawText("Tap Add to place label pictures", pageRect.centerX(), cy + 16f * d, small);
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
            float dd = getResources().getDisplayMetrics().density;
            float r = 6.5f * dd;
            Paint handle = new Paint(Paint.ANTI_ALIAS_FLAG);
            handle.setStyle(Paint.Style.FILL);
            handle.setShadowLayer(2.5f * dd, 0f, 1.5f * dd, 0x66000000);
            float[][] handles = new float[][]{
                    {dst.left, dst.top}, {dst.centerX(), dst.top}, {dst.right, dst.top},
                    {dst.left, dst.centerY()}, {dst.right, dst.centerY()},
                    {dst.left, dst.bottom}, {dst.centerX(), dst.bottom}, {dst.right, dst.bottom}
            };
            Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
            edge.setColor(Theme.SELECT);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(2.5f * dd);
            for (float[] hp : handles) {
                handle.setShader(new android.graphics.RadialGradient(hp[0] - r * 0.3f, hp[1] - r * 0.35f, r * 1.4f,
                        Color.WHITE, 0xFFD5DCE6, android.graphics.Shader.TileMode.CLAMP));
                canvas.drawCircle(hp[0], hp[1], r, handle);
                canvas.drawCircle(hp[0], hp[1], r, edge);
            }
        }
        canvas.restore();
    }

    private final Paint itemShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    {
        itemShadowPaint.setColor(Color.WHITE);
        itemShadowPaint.setShadowLayer(5f * getResources().getDisplayMetrics().density, 0f,
                2.5f * getResources().getDisplayMetrics().density, 0x55000000);
    }

    /** Side area drawn as a groove pressed into the surface: fill, inner top shadow, light bottom lip. */
    private void drawRecessed(Canvas canvas, RectF r, float radius) {
        float d = getResources().getDisplayMetrics().density;
        canvas.drawRoundRect(r, radius, radius, boardPaint);
        canvas.save();
        android.graphics.Path clip = new android.graphics.Path();
        clip.addRoundRect(r, radius, radius, android.graphics.Path.Direction.CW);
        canvas.clipPath(clip);
        Paint inner = new Paint(Paint.ANTI_ALIAS_FLAG);
        inner.setShader(new android.graphics.LinearGradient(0, r.top, 0, r.top + 14f * d,
                0x47000000, 0x00000000, android.graphics.Shader.TileMode.CLAMP));
        canvas.drawRect(r.left, r.top, r.right, r.top + 14f * d, inner);
        inner.setShader(new android.graphics.LinearGradient(r.left, 0, r.left + 10f * d, 0,
                0x26000000, 0x00000000, android.graphics.Shader.TileMode.CLAMP));
        canvas.drawRect(r.left, r.top, r.left + 10f * d, r.bottom, inner);
        canvas.restore();
        Paint lip = new Paint(Paint.ANTI_ALIAS_FLAG);
        lip.setStyle(Paint.Style.STROKE);
        lip.setStrokeWidth(1.2f * d);
        lip.setColor(Theme.DARK ? 0x22FFFFFF : 0x99FFFFFF);
        RectF lr = new RectF(r.left, r.top + 1.2f * d, r.right, r.bottom + 1.2f * d);
        canvas.save();
        canvas.clipRect(r.left, r.centerY(), r.right, r.bottom + 3f * d);
        canvas.drawRoundRect(lr, radius, radius, lip);
        canvas.restore();
        canvas.drawRoundRect(r, radius, radius, boardEdgePaint);
    }

    /** Round check badge on pictures picked in multi-select. */
    private void drawPickBadge(Canvas canvas, float cx, float cy) {
        float d = getResources().getDisplayMetrics().density;
        float r = 12f * d;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Theme.SELECT);
        p.setShadowLayer(3f * d, 0f, 1.5f * d, 0x66000000);
        canvas.drawCircle(cx, cy, r, p);
        p.clearShadowLayer();
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2f * d);
        p.setColor(Color.WHITE);
        canvas.drawCircle(cx, cy, r, p);
        p.setStrokeWidth(2.6f * d);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        android.graphics.Path check = new android.graphics.Path();
        check.moveTo(cx - 5f * d, cy + 0.5f * d);
        check.lineTo(cx - 1.5f * d, cy + 4f * d);
        check.lineTo(cx + 5.5f * d, cy - 4f * d);
        int on = Theme.bestOn(Theme.SELECT);
        p.setColor(on);
        canvas.drawPath(check, p);
    }

    private void drawBadge(Canvas canvas, String text, float x, float y) {
        float d = getResources().getDisplayMetrics().density;
        float tw = badgeTextPaint.measureText(text);
        RectF b = new RectF(x + 4f * d, y + 4f * d, x + 4f * d + tw + 12f * d, y + 20f * d);
        canvas.drawRoundRect(b, 8f * d, 8f * d, badgePaint);
        canvas.drawText(text, b.left + 6f * d, b.bottom - 4.5f * d, badgeTextPaint);
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
        // Content = page plus the side area, so parked pictures can always be scrolled into view.
        float contentW = baseW * zoom * (1f + 2f * BOARD_X);
        float contentH = baseH * zoom * (1f + 2f * BOARD_Y);
        float m = 12f * getResources().getDisplayMetrics().density;
        float maxX = Math.max(0f, (contentW - getWidth()) / 2f + m);
        float maxY = Math.max(0f, (contentH - getHeight()) / 2f + m);
        panX = clamp(panX, -maxX, maxX);
        panY = clamp(panY, -maxY, maxY);
    }

    private boolean canPan() {
        float baseW = getBasePageWidth(), baseH = getBasePageHeight();
        return baseW * zoom * (1f + 2f * BOARD_X) > getWidth() + 1f
                || baseH * zoom * (1f + 2f * BOARD_Y) > getHeight() + 1f;
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
        if (cropMode) return onCropTouch(event);
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
                longPressFired = false;
                removeCallbacks(longPress);
                if (!multiSelectMode && hit >= 0 && resizeHandle == HANDLE_NONE)
                    postDelayed(longPress, android.view.ViewConfiguration.getLongPressTimeout());

                if (multiSelectMode && resizeHandle == HANDLE_NONE) {
                    if (hit >= 0) {
                        if (!selectedSet.contains(hit)) selectedSet.add(hit);
                        selected = hit;
                    } else {
                        if (canPan()) {
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
                    if (hit < 0 && canPan()) {
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
                if (scaleDetector.isInProgress()) { removeCallbacks(longPress); return true; }
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (Math.hypot(dx, dy) > touchSlop) removeCallbacks(longPress);
                if (longPressFired) return true; // finger still down after long-press: don't drag

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
                        primary.x = clamp(startItemX + dx / pageRect.width(), -BOARD_X, 1f + BOARD_X - primary.w);
                        primary.y = clamp(startItemY + dy / pageRect.height(), -BOARD_Y, 1f + BOARD_Y - primary.h);
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
                removeCallbacks(longPress);
                if (event.getPointerCount() > 1 || event.getActionMasked() == MotionEvent.ACTION_CANCEL) { /* no tap */ }
                else if (!longPressFired && multiSelectMode && !dragging && !panningCanvas && resizeHandle == HANDLE_NONE &&
                        downHitIndex >= 0 && downHitWasSelected) {
                    // Tap an already-picked picture to unpick it; unpicking the last one leaves multi-select.
                    selectedSet.remove(downHitIndex);
                    if (selectedSet.isEmpty()) { selected = -1; multiSelectMode = false; }
                    else if (selected == downHitIndex) selected = selectedSet.iterator().next();
                    if (listener != null) listener.onSelectionChanged(hasSelection());
                }
                longPressFired = false;
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
            minDx = Math.max(minDx, minX(item) - sx);
            maxDx = Math.min(maxDx, maxX(item) - sx);
            minDy = Math.max(minDy, minY(item) - sy);
            maxDy = Math.min(maxDy, maxY(item) - sy);
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
                    item.x = clamp(item.x + snapDx, -BOARD_X, 1f + BOARD_X - item.w);
                    item.y = clamp(item.y + snapDy, -BOARD_Y, 1f + BOARD_Y - item.h);
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

        left = clamp(left, -BOARD_X, 1f + BOARD_X);
        right = clamp(right, -BOARD_X, 1f + BOARD_X);
        top = clamp(top, -BOARD_Y, 1f + BOARD_Y);
        bottom = clamp(bottom, -BOARD_Y, 1f + BOARD_Y);

        if (right - left < minW) {
            if (resizeHandle == HANDLE_LEFT) left = right - minW;
            else right = left + minW;
        }
        if (bottom - top < minH) {
            if (resizeHandle == HANDLE_TOP) top = bottom - minH;
            else bottom = top + minH;
        }

        left = clamp(left, -BOARD_X, 1f + BOARD_X - minW);
        right = clamp(right, left + minW, 1f + BOARD_X);
        top = clamp(top, -BOARD_Y, 1f + BOARD_Y - minH);
        bottom = clamp(bottom, top + minH, 1f + BOARD_Y);
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

        final float bl = -BOARD_X, bt = -BOARD_Y, br = 1f + BOARD_X, bb = 1f + BOARD_Y;
        if (resizeHandle == HANDLE_TL) {
            anchorX = startItemX + startItemW; anchorY = startItemY + startItemH;
            maxScaleX = (anchorX - bl) / startItemW; maxScaleY = (anchorY - bt) / startItemH;
        } else if (resizeHandle == HANDLE_TR) {
            anchorX = startItemX; anchorY = startItemY + startItemH;
            maxScaleX = (br - anchorX) / startItemW; maxScaleY = (anchorY - bt) / startItemH;
        } else if (resizeHandle == HANDLE_BL) {
            anchorX = startItemX + startItemW; anchorY = startItemY;
            maxScaleX = (anchorX - bl) / startItemW; maxScaleY = (bb - anchorY) / startItemH;
        } else {
            anchorX = startItemX; anchorY = startItemY;
            maxScaleX = (br - anchorX) / startItemW; maxScaleY = (bb - anchorY) / startItemH;
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
            item.x = clamp(bestTargetX - bestOffsetX, -BOARD_X, 1f + BOARD_X - item.w);
            guideXNorm = bestTargetX;
            showGuideX = true;
        }
        if (bestDy <= thresholdY) {
            item.y = clamp(bestTargetY - bestOffsetY, -BOARD_Y, 1f + BOARD_Y - item.h);
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
