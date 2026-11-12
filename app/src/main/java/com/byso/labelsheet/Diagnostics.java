package com.byso.labelsheet;

import android.content.Context;
import android.os.Build;
import android.content.pm.PackageInfo;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class Diagnostics {
    private static final Object LOCK = new Object();
    private static File logFile;
    private static final long MAX_BYTES = 220_000;

    static void init(Context context) {
        synchronized (LOCK) {
            if (logFile != null) return;
            logFile = new File(context.getFilesDir(), "labelsheet-diagnostics.txt");
            String version = "unknown";
            try {
                PackageInfo pi = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                version = pi.versionName == null ? "unknown" : pi.versionName;
            } catch (Exception ignored) { }
            log("APP_START", "LabelSheet " + version + " | Android " + Build.VERSION.RELEASE +
                    " (SDK " + Build.VERSION.SDK_INT + ") | " + Build.MANUFACTURER + " " + Build.MODEL);
        }
    }

    static void log(String event, String details) {
        synchronized (LOCK) {
            if (logFile == null) return;
            try {
                trimIfNeeded();
                String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
                try (FileWriter w = new FileWriter(logFile, true)) {
                    w.write(ts + " | " + sanitize(event) + " | " + sanitize(details) + "\n");
                }
            } catch (Exception ignored) { }
        }
    }

    static void error(String event, Throwable t) {
        String msg = t == null ? "unknown error" : t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage());
        log(event, msg);
    }

    static String read() {
        synchronized (LOCK) {
            if (logFile == null || !logFile.exists()) return "No diagnostic events have been recorded yet.";
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new FileReader(logFile))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
            } catch (IOException e) {
                return "Could not read diagnostic log: " + e.getMessage();
            }
            return sb.toString();
        }
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replace('\n', ' ').replace('\r', ' ');
    }

    private static void trimIfNeeded() throws IOException {
        if (!logFile.exists() || logFile.length() < MAX_BYTES) return;
        String content = read();
        int keepFrom = Math.max(0, content.length() / 2);
        int newline = content.indexOf('\n', keepFrom);
        if (newline >= 0) keepFrom = newline + 1;
        try (FileWriter w = new FileWriter(logFile, false)) {
            w.write("--- older diagnostic entries trimmed ---\n");
            w.write(content.substring(keepFrom));
        }
    }
}
