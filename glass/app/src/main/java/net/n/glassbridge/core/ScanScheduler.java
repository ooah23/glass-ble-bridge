package net.n.glassbridge.core;

/**
 * Pure scan/connect scheduler (caller supplies a monotonic clock in ms). Guarantees:
 * one scan or connection at a time, scan duty cycle 2 s on / 8 s off while searching,
 * connectGatt at least 5 s apart, failure backoff 2/4/8.. capped at 30 s.
 */
public final class ScanScheduler {
    public static final long SCAN_ON_MS = 2000, SCAN_OFF_MS = 8000, MIN_CONNECT_GAP_MS = 5000;
    private final Backoff backoff = new Backoff();
    private boolean scanning, connecting;
    private long lastConnect = Long.MIN_VALUE / 2;

    public boolean isBusy() { return scanning || connecting; }

    /** True if a scan window may start now (nothing scanning or connecting). */
    public boolean beginScan() {
        if (scanning || connecting) return false;
        scanning = true;
        return true;
    }

    public void endScan() { scanning = false; }

    /** True if connectGatt may be called now: not already connecting and >= 5 s since the last attempt. Ends the scan. */
    public boolean beginConnect(long now) {
        if (connecting || now - lastConnect < MIN_CONNECT_GAP_MS) return false;
        scanning = false;
        connecting = true;
        lastConnect = now;
        return true;
    }

    /** Connection attempt or link over (success of auth is reported separately). */
    public void endConnect() { connecting = false; }

    /** Auth succeeded: reset backoff. */
    public void authed() { backoff.reset(); }

    /** Delay before the next scan window after an empty window (duty cycle off time). */
    public long afterEmptyWindow() { return SCAN_OFF_MS; }

    /** Delay before the next scan after a failure or disconnect: backoff, but never inside the 5 s connect gap. */
    public long afterFailure(long now) {
        long d = backoff.next();
        long gapLeft = lastConnect + MIN_CONNECT_GAP_MS - now;
        return Math.max(d, gapLeft);
    }
}
