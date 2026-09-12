package com.moneyakshaders.render;

import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/** Incremental portal traversal. Revisit a section through different faces to avoid false occlusion. */
final class SceneVisibilityGraph {
    interface Connectivity { long get(int x, int y, int z); }
    private static final int[] DX = {0, 0, 0, 0, -1, 1};
    private static final int[] DY = {-1, 1, 0, 0, 0, 0};
    private static final int[] DZ = {0, 0, -1, 1, 0, 0};
    private final long[] queue;
    private final byte[] faces;
    private final Long2ByteOpenHashMap visited = new Long2ByteOpenHashMap();
    private LongOpenHashSet result = new LongOpenHashSet();
    private int head, tail, cx, cz, radius, bottom, top;
    private boolean running, overflow, occlude;

    SceneVisibilityGraph(int capacity) { queue = new long[capacity]; faces = new byte[capacity]; }

    static long key(int x, int y, int z) {
        return ((long) x & 0x3fffffL) << 42 | ((long) z & 0x3fffffL) << 20 | (long) y & 0xfffffL;
    }

    void begin(int x, int y, int z, int radius, int bottom, int top, boolean occlude) {
        this.cx = x; this.cz = z; this.radius = radius;
        this.bottom = bottom; this.top = top; this.occlude = occlude;
        head = tail = 0; overflow = false; running = true;
        visited.clear(); result.clear();
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++)
            for (int dz = -1; dz <= 1; dz++) add(x + dx, y + dy, z + dz, -1);
    }

    private void add(int x, int y, int z, int face) {
        long key = key(x, y, z);
        int bit = face < 0 ? 63 : 1 << face;
        int previous = visited.get(key) & 63;
        if ((previous & bit) == bit) return;
        if (tail == queue.length) { overflow = true; running = false; return; }
        visited.put(key, (byte) (previous | bit));
        queue[tail] = key; faces[tail++] = (byte) face;
    }

    /** Returns true exactly when this invocation finishes a build (including conservative overflow). */
    boolean step(int limit, long budgetNs, LongSupplier clock, Connectivity connectivity, LongConsumer discovered) {
        if (!running) return false;
        long start = clock.getAsLong();
        int work = 0;
        while (running && head < tail && work++ < limit) {
            long key = queue[head];
            int entry = faces[head++];
            int x = (int) (key >> 42), y = (int) (key << 44 >> 44), z = (int) (key << 22 >> 42);
            if (result.add(key)) discovered.accept(key);
            long mask = occlude ? connectivity.get(x, y, z) : -1L;
            for (int exit = 0; exit < 6; exit++) {
                if (entry >= 0 && (mask >>> (entry * 6 + exit) & 1L) == 0) continue;
                int nx = x + DX[exit], ny = y + DY[exit], nz = z + DZ[exit];
                if (Math.abs(nx - cx) > radius || Math.abs(nz - cz) > radius || ny < bottom || ny > top) continue;
                add(nx, ny, nz, exit ^ 1);
                if (overflow) break;
            }
            if (clock.getAsLong() - start >= budgetNs) break;
        }
        if (overflow || head == tail) { running = false; return true; }
        return false;
    }

    boolean running() { return running; }
    boolean overflow() { return overflow; }
    int pending() { return tail - head; }

    /** Swap buffers, so publication never copies the entire visible set on the render thread. */
    LongOpenHashSet publish(LongOpenHashSet previous) {
        if (running) throw new IllegalStateException("Incomplete visibility graph");
        LongOpenHashSet published = result;
        result = previous;
        if (overflow) published.clear();
        return published;
    }

    void reset() { running = overflow = false; head = tail = 0; visited.clear(); result.clear(); }
}
