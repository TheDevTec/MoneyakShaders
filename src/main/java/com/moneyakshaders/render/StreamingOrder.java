package com.moneyakshaders.render;

/** Spatial continuity and fair service without increasing the total frame allowance. */
final class StreamingOrder {
    static long locality(int x, int z, int viewerX, int viewerZ) {
        long dx = (long) x - viewerX, dz = (long) z - viewerZ;
        return Math.max(Math.abs(dx), Math.abs(dz)) * 1_000_000L + dx * dx + dz * dz;
    }

    static boolean urgentFirst(long frame, boolean urgent, boolean streaming) {
        return urgent && (!streaming || (frame & 1) == 0);
    }
}
