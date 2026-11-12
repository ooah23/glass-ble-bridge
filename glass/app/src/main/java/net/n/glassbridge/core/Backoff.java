package net.n.glassbridge.core;

/** Exponential backoff: 2s, 4s, ... capped at 60s. */
public final class Backoff {
    private long cur = 2000;
    public long next() { long v = cur; cur = Math.min(cur * 2, 30000); return v; }
    public void reset() { cur = 2000; }
}
