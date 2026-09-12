package com.moneyakshaders.render;

import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

/** Render-thread registry. No full-world copies, sorting or iterators retained across frames. */
final class SceneResidencyScheduler {
    private final LongLinkedOpenHashSet registered = new LongLinkedOpenHashSet();
    private final LongLinkedOpenHashSet urgent = new LongLinkedOpenHashSet();
    private final Long2LongOpenHashMap sleeping = new Long2LongOpenHashMap();
    private final Long2LongOpenHashMap irrelevantSince = new Long2LongOpenHashMap();
    private long sleepingBytes;
    private int remaining;

    void register(long key) {
        registered.addAndMoveToFirst(key);
        awake(key);
        remaining = Math.min(registered.size(), remaining + 1);
    }

    void remove(long key) {
        registered.remove(key);
        urgent.remove(key);
        awake(key);
        remaining = Math.min(remaining, registered.size());
    }

    void prioritize(long key) {
        if (registered.contains(key)) urgent.add(key);
    }

    void prioritizeNear(long key) {
        if (registered.contains(key)) urgent.addAndMoveToFirst(key);
    }

    void beginSweep() { remaining = registered.size(); }

    int process(int limit, long nanos, LongSupplier clock, LongConsumer visitor) {
        long start = clock.getAsLong();
        int count = 0, priorityCount = 0;
        // Reserve half the slice for round-robin work so repeated visibility requests cannot starve it.
        while (count < limit && (!urgent.isEmpty() || remaining > 0 && !registered.isEmpty())) {
            long key;
            if (!urgent.isEmpty() && (priorityCount < Math.max(1, limit / 2) || remaining == 0)) {
                key = urgent.removeFirstLong();
                priorityCount++;
            } else {
                key = registered.firstLong();
                registered.addAndMoveToLast(key);
                remaining--;
                urgent.remove(key);
            }
            visitor.accept(key);
            count++;
            if (clock.getAsLong() - start >= nanos) break;
        }
        return count;
    }

    boolean shouldSleep(long key, boolean relevant, long nowMs, long graceMs) {
        if (relevant) { irrelevantSince.remove(key); return false; }
        if (!irrelevantSince.containsKey(key)) irrelevantSince.put(key, nowMs);
        return nowMs - irrelevantSince.get(key) >= graceMs;
    }

    void sleep(long key, long bytes) {
        sleepingBytes += bytes - sleeping.put(key, bytes);
    }

    void awake(long key) {
        sleepingBytes -= sleeping.remove(key);
        irrelevantSince.remove(key);
    }

    int sleepingCount() { return sleeping.size(); }
    long sleepingBytes() { return sleepingBytes; }
    int pending() { return remaining + urgent.size(); }

    void clear() {
        registered.clear(); urgent.clear(); sleeping.clear(); irrelevantSince.clear();
        sleepingBytes = 0; remaining = 0;
    }
}
