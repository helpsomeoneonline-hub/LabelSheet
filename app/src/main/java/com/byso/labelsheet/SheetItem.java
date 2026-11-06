package com.byso.labelsheet;

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
    boolean locked;
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
        this.locked = false;
    }

    SheetItem copy() {
        SheetItem c = new SheetItem(uri, x, y, w, h);
        c.rotation = rotation;
        c.borderPt = borderPt;
        c.cropLeft = cropLeft;
        c.cropTop = cropTop;
        c.cropRight = cropRight;
        c.cropBottom = cropBottom;
        c.locked = locked;
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
        o.put("locked", locked);
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
        item.locked = o.optBoolean("locked", false);
        return item;
    }
}
