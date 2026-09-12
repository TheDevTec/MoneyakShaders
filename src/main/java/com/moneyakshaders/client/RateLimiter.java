package com.moneyakshaders.client;

/**
 * Simple per-second rate limiter. Thread-safe, coarse-grained window.
 */
public final class RateLimiter {
    private final int permitsPerSecond;
    private long windowStartMillis;
    private int used;

    public RateLimiter(int permitsPerSecond) {
        this.permitsPerSecond = Math.max(0, permitsPerSecond);
        this.windowStartMillis = System.currentTimeMillis();
        this.used = 0;
    }

    public synchronized boolean tryAcquire() {
        if (permitsPerSecond == 0) {
            return true;
        }
        long now = System.currentTimeMillis();
        if (now - windowStartMillis >= 1000L) {
            windowStartMillis = now;
            used = 0;
        }
        if (used < permitsPerSecond) {
            used++;
            return true;
        }
        return false;
    }
}
