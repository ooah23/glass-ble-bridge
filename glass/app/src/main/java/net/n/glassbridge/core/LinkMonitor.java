package net.n.glassbridge.core;

/** Heartbeat bookkeeping, pure (caller supplies a monotonic clock in ms). Send every 5 s; no hb/hback for 15 s = link lost. */
public final class LinkMonitor {
    public static final long INTERVAL_MS = 5000, TIMEOUT_MS = 15000;
    public static final int NONE = 0, SEND = 1, LOST = 2;
    private long base, lastSent = -1, lastAck = -1;
    private int counter;

    /** Call when the link becomes authenticated. lastAck survives so "last seen" keeps counting across reconnects. */
    public void start(long now) { base = now; lastSent = -1; }
    /** Any hb or hback from the peer. */
    public void received(long now) { lastAck = now; }
    public int counter() { return counter; }

    public int tick(long now) {
        long ref = Math.max(base, lastAck);
        if (now - ref >= TIMEOUT_MS) return LOST;
        if (lastSent < 0 || now - lastSent >= INTERVAL_MS) { lastSent = now; counter++; return SEND; }
        return NONE;
    }

    /** Seconds since the peer was last heard, or -1 if never. */
    public long ageSeconds(long now) { return lastAck < 0 ? -1 : Math.max(0, (now - lastAck) / 1000); }
}
