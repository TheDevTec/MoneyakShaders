package com.moneyakshaders.render;

/** Estimate amortized pass cost separately from its peak cost; repeated calls in one frame coalesce. */
final class PassCadence {
    private int lastFrame = Integer.MIN_VALUE;
    private double interval = 1;

    void invoke(int frame) {
        if (lastFrame != Integer.MIN_VALUE && frame > lastFrame) {
            interval = interval * 0.8 + Math.min(120, frame - lastFrame) * 0.2;
        }
        lastFrame = frame;
    }

    double amortized(double passMs) { return passMs / Math.max(1, interval); }
}
