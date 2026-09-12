package com.moneyakshaders.client;

/** Preserve measured render throughput instead of spending all time below a fixed 14 ms target. */
public final class StreamingFrameBudget {
    private double baseMs;

    public void sample(double frameMs, double preparationMs, boolean busy) {
        if (!Double.isFinite(frameMs) || !Double.isFinite(preparationMs) || frameMs <= 0) return;
        double renderMs = Math.max(0.5, frameMs - Math.max(0, preparationMs));
        if (baseMs == 0) baseMs = renderMs;
        else if (renderMs < baseMs) baseMs += (renderMs - baseMs) * 0.2;
        else if (!busy) baseMs += (renderMs - baseMs) * 0.02;
        // Never learn the streaming slowdown as the new normal while workers are busy.
    }

    public double targetMs(double configured) {
        return Math.min(Math.max(1, configured), Math.max(2.5, (baseMs > 0 ? baseMs : 3) * 1.35 + 0.2));
    }

    public long preparationNanos() {
        return (long) (Math.max(0.35, Math.min(2, (baseMs > 0 ? baseMs : 3) * 0.3)) * 1_000_000);
    }

    public long preparationNanos(int snapshots, int meshes, int uploads, double frameMs) {
        long normal = preparationNanos();
        // A full producer window with idle consumers is starvation, not GPU pressure. Borrow a
        // bounded slice only while frames remain fast; a slow frame immediately removes the loan.
        if (snapshots >= 32 && meshes <= 2 && uploads == 0 && frameMs > 0 && frameMs <= 8.0)
            return Math.max(normal, (long) (Math.max(0.85, Math.min(1.2, frameMs * 0.15)) * 1_000_000));
        return normal;
    }
}
