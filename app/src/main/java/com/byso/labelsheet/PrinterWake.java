package com.byso.labelsheet;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

final class PrinterWake {
    static final String DEFAULT_PREFIX = "192.168.58.";
    static final int DEFAULT_RANGE_START = 100;
    static final int DEFAULT_RANGE_END = 110;

    static final class Result {
        final String host;
        final int port;
        final boolean discovered;
        Result(String host, int port, boolean discovered) {
            this.host = host;
            this.port = port;
            this.discovered = discovered;
        }
    }

    private PrinterWake() {}

    static String normalizeHost(String host) {
        if (host == null) return "";
        String h = host.trim();
        h = h.replaceFirst("^https?://", "");
        int slash = h.indexOf('/');
        if (slash >= 0) h = h.substring(0, slash);
        int colon = h.indexOf(':');
        if (colon > 0 && h.indexOf(':', colon + 1) < 0) h = h.substring(0, colon);
        return h.trim();
    }

    static int tryWake(String host) {
        return probePrinter(host, 700);
    }

    /** Reconnect in bounded stages; never pretend an open TCP port proves paper was printed. */
    static Result reconnectForPrint(String preferredHost, boolean aggressive) {
        long start = android.os.SystemClock.elapsedRealtime();
        String preferred = normalizeHost(preferredHost);
        int tries = aggressive ? 3 : 1;
        for (int i = 0; i < tries && !preferred.isEmpty(); i++) {
            int port = probePrinter(preferred, aggressive ? 850 : 500);
            Diagnostics.log("PRINTER_RECONNECT_PROBE", "attempt=" + (i + 1) +
                    ", service=" + (port > 0 ? port : "unreachable") +
                    ", elapsed_ms=" + (android.os.SystemClock.elapsedRealtime() - start));
            if (port > 0) return new Result(preferred, port, false);
            if (i + 1 < tries) try { Thread.sleep(800L * (i+1)); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
        }
        Result discovered = discover(DEFAULT_PREFIX, DEFAULT_RANGE_START, DEFAULT_RANGE_END, preferred);
        Diagnostics.log("PRINTER_RECONNECT_SCAN",
                "found=" + (discovered != null) +
                ", elapsed_ms=" + (android.os.SystemClock.elapsedRealtime() - start));
        return discovered;
    }

    static String serviceDescription(Result r) {
        if (r == null) return "No network print service responded.";
        switch (r.port) {
            case 631: return "IPP network print service responded (port 631).";
            case 9100: return "Raw print service responded (port 9100).";
            case 80: return "Printer web interface responded (port 80); print readiness unconfirmed.";
            default: return "Network connection responded; print readiness unconfirmed.";
        }
    }

    static Result findAndWake(String preferredHost) {
        String preferred = normalizeHost(preferredHost);
        if (!preferred.isEmpty()) {
            int p = probePrinter(preferred, 500);
            if (p > 0) return new Result(preferred, p, false);
        }
        return discover(DEFAULT_PREFIX, DEFAULT_RANGE_START, DEFAULT_RANGE_END, preferred);
    }

    static Result discover(String prefix, int start, int end, String skipHost) {
        String skip = normalizeHost(skipHost);
        String fallbackHost = null;
        int fallbackPort = -1;
        for (int last = start; last <= end; last++) {
            String host = prefix + last;
            if (host.equals(skip)) continue;

            // Canon's web UI, when available, is the strongest identity check.
            if (isCanonHttp(host, 260)) {
                int p = probePrinter(host, 320);
                if (p < 0) p = 80;
                return new Result(host, p, true);
            }

            // IPP and JetDirect are printer-specific enough to use as fallback discovery.
            int p = connect(host, 9100, 260) ? 9100 : (connect(host, 631, 260) ? 631 : -1);
            if (p > 0 && fallbackHost == null) {
                fallbackHost = host;
                fallbackPort = p;
            }
        }
        return fallbackHost == null ? null : new Result(fallbackHost, fallbackPort, true);
    }

    private static int probePrinter(String host, int timeoutMs) {
        String target = normalizeHost(host);
        if (target.isEmpty()) return -1;
        if (connect(target, 9100, timeoutMs)) return 9100;
        if (connect(target, 631, timeoutMs)) return 631;
        if (isCanonHttp(target, timeoutMs)) return 80;
        return -1;
    }

    private static boolean connect(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return socket.isConnected();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isCanonHttp(String host, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, 80), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            OutputStream out = socket.getOutputStream();
            String req = "GET / HTTP/1.0\r\nHost: " + host + "\r\nConnection: close\r\n\r\n";
            out.write(req.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[1024];
            int total = 0;
            int n;
            while (total < 8192 && (n = reader.read(buf, 0, Math.min(buf.length, 8192 - total))) > 0) {
                sb.append(buf, 0, n);
                total += n;
            }
            String body = sb.toString().toLowerCase(java.util.Locale.US);
            return body.contains("canon") || body.contains("ts6500") || body.contains("pixma");
        } catch (Exception ignored) {
            return false;
        }
    }
}
