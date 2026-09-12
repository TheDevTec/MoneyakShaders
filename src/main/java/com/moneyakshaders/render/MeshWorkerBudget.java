package com.moneyakshaders.render;

/** Slow queue-driven CPU scaling; ready meshes are backpressure, not a reason to add workers. */
final class MeshWorkerBudget {
    private long nextDecision;
    private int pressure, spare;

    int update(long now, int current, int maximum, int queued, boolean readyFull,
            double frameMs, double targetMs) {
        if (now < nextDecision) return current;
        nextDecision = now + 1000;
        boolean overloaded = frameMs > 0 && targetMs > 0 && frameMs > targetMs * 1.2;
        if (overloaded || readyFull) {
            spare = 0;
            if (++pressure >= 2) { pressure = 0; return Math.max(1, current - 1); }
        } else if (queued >= current * 2 && frameMs > 0 && frameMs < targetMs * 0.9) {
            pressure = 0;
            if (++spare >= 3) { spare = 0; return Math.min(maximum, current + 1); }
        } else { spare = pressure = 0; }
        return Math.min(maximum, current);
    }
}
