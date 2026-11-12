package com.byso.labelsheet;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

public class CropActivity extends Activity {
    private CropView cropView;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Diagnostics.init(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF111419);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets;
        });

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(14), dp(8), dp(14), dp(6));
        Button cancel = button("Cancel", false);
        cancel.setOnClickListener(v -> finish());
        top.addView(cancel);
        TextView title = new TextView(this);
        title.setText("Crop Picture");
        title.setTextColor(Color.WHITE);
        title.setTextSize(19);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1f));
        Button done = button("Done", true);
        done.setOnClickListener(v -> finishCrop());
        top.addView(done);
        root.addView(top);

        TextView hint = new TextView(this);
        hint.setText("Drag corners or edges • drag inside the frame to move it");
        hint.setTextColor(0xFFB8C0CA);
        hint.setTextSize(12);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(10), 0, dp(10), dp(6));
        root.addView(hint);

        cropView = new CropView(this);
        root.addView(cropView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER);
        bottom.setPadding(dp(12), dp(7), dp(12), dp(10));
        Button reset = button("Reset Crop", false);
        reset.setOnClickListener(v -> cropView.resetCrop());
        bottom.addView(reset);
        root.addView(bottom);
        setContentView(root);
        root.requestApplyInsets();

        try {
            String uriText = getIntent().getStringExtra("imageUri");
            if (uriText == null || uriText.trim().isEmpty()) throw new IllegalArgumentException("Missing image URI");
            Uri uri = Uri.parse(uriText);
            Bitmap bitmap = decodeBitmap(uri, 3000);
            if (bitmap == null) throw new IllegalStateException("Could not decode image");
            cropView.setImage(bitmap,
                    getIntent().getFloatExtra("cropLeft", 0f),
                    getIntent().getFloatExtra("cropTop", 0f),
                    getIntent().getFloatExtra("cropRight", 0f),
                    getIntent().getFloatExtra("cropBottom", 0f));
        } catch (Exception e) {
            Diagnostics.error("VISUAL_CROP_LOAD_FAILED", e);
            Toast.makeText(this, "I couldn't open this picture for cropping.", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void finishCrop() {
        if (cropView == null || !cropView.isReady()) return;
        float[] c = cropView.getCropFractions();
        Intent result = new Intent();
        result.putExtra("cropLeft", c[0]);
        result.putExtra("cropTop", c[1]);
        result.putExtra("cropRight", c[2]);
        result.putExtra("cropBottom", c[3]);
        setResult(RESULT_OK, result);
        finish();
    }

    private Button button(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(12);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(primary ? 0xFF19B5A5 : 0xFF343A44);
        bg.setCornerRadius(dp(10));
        b.setBackground(bg);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40));
        p.setMargins(dp(3), 0, dp(3), 0);
        b.setLayoutParams(p);
        return b;
    }

    private Bitmap decodeBitmap(Uri uri, int maxSide) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = open(uri)) { BitmapFactory.decodeStream(in, null, bounds); }
        int sample = 1;
        while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > maxSide) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try (InputStream in = open(uri)) { return BitmapFactory.decodeStream(in, null, opts); }
    }

    private InputStream open(Uri uri) throws Exception {
        if ("file".equalsIgnoreCase(uri.getScheme())) return new FileInputStream(new File(uri.getPath()));
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) throw new IllegalStateException("Could not open image");
        return in;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private static final class CropView extends View {
        private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint shadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint handleFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint handleStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Bitmap bitmap;
        private final RectF imageRect = new RectF();
        private final RectF cropRect = new RectF();
        private float initL, initT, initR, initB;
        private boolean rectsReady = false;
        private int active = -1;
        private float lastX, lastY;
        private final float handleR;
        private final float touchR;
        private final float minSize;

        CropView(android.content.Context context) {
            super(context);
            setBackgroundColor(0xFF0B0D10);
            float d = getResources().getDisplayMetrics().density;
            handleR = 8f * d;
            touchR = 30f * d;
            minSize = 56f * d;
            shadePaint.setColor(0x99000000);
            linePaint.setColor(0xFF19B5A5);
            linePaint.setStyle(Paint.Style.STROKE);
            linePaint.setStrokeWidth(2.2f * d);
            gridPaint.setColor(0x99FFFFFF);
            gridPaint.setStyle(Paint.Style.STROKE);
            gridPaint.setStrokeWidth(1f * d);
            handleFill.setColor(Color.WHITE);
            handleFill.setStyle(Paint.Style.FILL);
            handleStroke.setColor(0xFF19B5A5);
            handleStroke.setStyle(Paint.Style.STROKE);
            handleStroke.setStrokeWidth(2.5f * d);
        }

        void setImage(Bitmap bitmap, float l, float t, float r, float b) {
            this.bitmap = bitmap;
            initL = clamp(l, 0f, .85f);
            initT = clamp(t, 0f, .85f);
            initR = clamp(r, 0f, .85f);
            initB = clamp(b, 0f, .85f);
            rectsReady = false;
            invalidate();
        }

        boolean isReady() { return bitmap != null && rectsReady; }

        void resetCrop() {
            if (imageRect.isEmpty()) return;
            cropRect.set(imageRect);
            invalidate();
        }

        float[] getCropFractions() {
            if (!isReady()) return new float[]{0f,0f,0f,0f};
            float l = (cropRect.left - imageRect.left) / imageRect.width();
            float t = (cropRect.top - imageRect.top) / imageRect.height();
            float r = (imageRect.right - cropRect.right) / imageRect.width();
            float b = (imageRect.bottom - cropRect.bottom) / imageRect.height();
            return new float[]{clamp(l,0f,.85f), clamp(t,0f,.85f), clamp(r,0f,.85f), clamp(b,0f,.85f)};
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (bitmap == null || getWidth() <= 0 || getHeight() <= 0) return;
            ensureRects();
            Matrix m = new Matrix();
            RectF src = new RectF(0, 0, bitmap.getWidth(), bitmap.getHeight());
            m.setRectToRect(src, imageRect, Matrix.ScaleToFit.FILL);
            canvas.drawBitmap(bitmap, m, bitmapPaint);

            canvas.drawRect(imageRect.left, imageRect.top, imageRect.right, cropRect.top, shadePaint);
            canvas.drawRect(imageRect.left, cropRect.bottom, imageRect.right, imageRect.bottom, shadePaint);
            canvas.drawRect(imageRect.left, cropRect.top, cropRect.left, cropRect.bottom, shadePaint);
            canvas.drawRect(cropRect.right, cropRect.top, imageRect.right, cropRect.bottom, shadePaint);

            canvas.drawRect(cropRect, linePaint);
            float x1 = cropRect.left + cropRect.width()/3f;
            float x2 = cropRect.left + cropRect.width()*2f/3f;
            float y1 = cropRect.top + cropRect.height()/3f;
            float y2 = cropRect.top + cropRect.height()*2f/3f;
            canvas.drawLine(x1, cropRect.top, x1, cropRect.bottom, gridPaint);
            canvas.drawLine(x2, cropRect.top, x2, cropRect.bottom, gridPaint);
            canvas.drawLine(cropRect.left, y1, cropRect.right, y1, gridPaint);
            canvas.drawLine(cropRect.left, y2, cropRect.right, y2, gridPaint);

            drawHandle(canvas, cropRect.left, cropRect.top);
            drawHandle(canvas, cropRect.right, cropRect.top);
            drawHandle(canvas, cropRect.left, cropRect.bottom);
            drawHandle(canvas, cropRect.right, cropRect.bottom);
            drawHandle(canvas, cropRect.centerX(), cropRect.top);
            drawHandle(canvas, cropRect.centerX(), cropRect.bottom);
            drawHandle(canvas, cropRect.left, cropRect.centerY());
            drawHandle(canvas, cropRect.right, cropRect.centerY());
        }

        private void ensureRects() {
            float margin = 20f * getResources().getDisplayMetrics().density;
            float aw = Math.max(1f, getWidth() - margin*2f);
            float ah = Math.max(1f, getHeight() - margin*2f);
            float ar = bitmap.getWidth() / (float)Math.max(1, bitmap.getHeight());
            float w = aw, h = w/ar;
            if (h > ah) { h = ah; w = h*ar; }
            float l = (getWidth()-w)/2f;
            float t = (getHeight()-h)/2f;
            RectF newImage = new RectF(l,t,l+w,t+h);
            if (!rectsReady || Math.abs(newImage.width()-imageRect.width()) > 1f || Math.abs(newImage.height()-imageRect.height()) > 1f) {
                imageRect.set(newImage);
                cropRect.set(
                        imageRect.left + initL*imageRect.width(),
                        imageRect.top + initT*imageRect.height(),
                        imageRect.right - initR*imageRect.width(),
                        imageRect.bottom - initB*imageRect.height());
                if (cropRect.width() < minSize || cropRect.height() < minSize) cropRect.set(imageRect);
                rectsReady = true;
            }
        }

        private void drawHandle(Canvas c, float x, float y) {
            c.drawCircle(x, y, handleR, handleFill);
            c.drawCircle(x, y, handleR, handleStroke);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (!isReady()) return true;
            float x=e.getX(), y=e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    active = hit(x,y);
                    lastX=x; lastY=y;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (active < 0) return true;
                    float dx=x-lastX, dy=y-lastY;
                    adjust(active, dx, dy);
                    lastX=x; lastY=y;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    active=-1;
                    return true;
            }
            return true;
        }

        private int hit(float x,float y) {
            float[][] pts={{cropRect.left,cropRect.top},{cropRect.right,cropRect.top},{cropRect.left,cropRect.bottom},{cropRect.right,cropRect.bottom},
                    {cropRect.centerX(),cropRect.top},{cropRect.centerX(),cropRect.bottom},{cropRect.left,cropRect.centerY()},{cropRect.right,cropRect.centerY()}};
            for(int i=0;i<pts.length;i++) if(Math.hypot(x-pts[i][0],y-pts[i][1])<=touchR) return i;
            if(cropRect.contains(x,y)) return 8;
            return -1;
        }

        private void adjust(int a,float dx,float dy) {
            float l=cropRect.left,t=cropRect.top,r=cropRect.right,b=cropRect.bottom;
            if(a==8) {
                float nx=clamp(dx, imageRect.left-l, imageRect.right-r);
                float ny=clamp(dy, imageRect.top-t, imageRect.bottom-b);
                cropRect.offset(nx,ny); return;
            }
            if(a==0||a==2||a==6) l=clamp(l+dx,imageRect.left,r-minSize);
            if(a==1||a==3||a==7) r=clamp(r+dx,l+minSize,imageRect.right);
            if(a==0||a==1||a==4) t=clamp(t+dy,imageRect.top,b-minSize);
            if(a==2||a==3||a==5) b=clamp(b+dy,t+minSize,imageRect.bottom);
            cropRect.set(l,t,r,b);
        }

        private static float clamp(float v,float min,float max){ return Math.max(min,Math.min(max,v)); }
    }
}
