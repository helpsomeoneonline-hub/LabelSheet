package com.byso.labelsheet;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.SharedPreferences;

public class PrinterKeepAliveService extends JobService {
    @Override public boolean onStartJob(JobParameters params) {
        Diagnostics.init(getApplicationContext());
        SharedPreferences prefs = getSharedPreferences("printer", MODE_PRIVATE);
        if (!prefs.getBoolean("keep_alive_enabled", true)) {
            Diagnostics.log("PRINTER_KEEPALIVE_SKIPPED", "disabled");
            return false;
        }
        final String preferred = prefs.getString("host", "192.168.58.100");
        new Thread(() -> {
            PrinterWake.Result result = PrinterWake.findAndWake(preferred);
            if (result != null) {
                if (!result.host.equals(PrinterWake.normalizeHost(preferred))) {
                    prefs.edit().putString("host", result.host).apply();
                    Diagnostics.log("PRINTER_IP_UPDATED", "old=" + PrinterWake.normalizeHost(preferred) + ", new=" + result.host);
                }
                Diagnostics.log("PRINTER_KEEPALIVE_OK", "host=" + result.host + ", port=" + result.port + (result.discovered ? ", discovered=true" : ""));
            } else {
                Diagnostics.log("PRINTER_KEEPALIVE_NO_RESPONSE", "searched=192.168.58.100-110");
            }
            jobFinished(params, false);
        }).start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        Diagnostics.log("PRINTER_KEEPALIVE_STOPPED", "OS stopped job; it can run again later");
        return true;
    }
}
