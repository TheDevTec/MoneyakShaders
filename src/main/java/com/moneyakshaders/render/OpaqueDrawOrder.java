package com.moneyakshaders.render;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.Objects;

/** Stable near-to-far ordering in at most four linear passes, with reusable storage. */
final class OpaqueDrawOrder<T> extends AbstractList<T> {
    private Object[] values = new Object[256], scratch = new Object[256];
    private int[] keys = new int[256], scratchKeys = new int[256];
    private final int[] offsets = new int[256];
    private int count, varyingBits, firstKey;

    void enqueue(T value, float distanceSquared) {
        if (count == values.length) {
            int capacity = values.length * 2;
            values = Arrays.copyOf(values, capacity);
            scratch = Arrays.copyOf(scratch, capacity);
            keys = Arrays.copyOf(keys, capacity);
            scratchKeys = Arrays.copyOf(scratchKeys, capacity);
        }
        float distance = Float.isNaN(distanceSquared) ? Float.POSITIVE_INFINITY : Math.max(0f, distanceSquared);
        int key = Float.floatToRawIntBits(distance);
        if (count == 0) firstKey = key;
        varyingBits |= key ^ firstKey;
        values[count] = value;
        keys[count++] = key;
    }

    void order() {
        if (count < 2) return;
        for (int shift = 0; shift < 32; shift += 8) {
            if (((varyingBits >>> shift) & 255) == 0) continue;
            Arrays.fill(offsets, 0);
            for (int i = 0; i < count; i++) offsets[(keys[i] >>> shift) & 255]++;
            int next = 0;
            for (int i = 0; i < offsets.length; i++) {
                int size = offsets[i];
                offsets[i] = next;
                next += size;
            }
            for (int i = 0; i < count; i++) {
                int at = offsets[(keys[i] >>> shift) & 255]++;
                scratch[at] = values[i];
                scratchKeys[at] = keys[i];
            }
            Object[] oldValues = values; values = scratch; scratch = oldValues;
            int[] oldKeys = keys; keys = scratchKeys; scratchKeys = oldKeys;
        }
    }

    @Override public void clear() {
        Arrays.fill(values, 0, count, null);
        Arrays.fill(scratch, 0, count, null);
        count = varyingBits = 0;
    }

    @SuppressWarnings("unchecked")
    @Override public T get(int index) { Objects.checkIndex(index, count); return (T) values[index]; }
    @Override public int size() { return count; }
}
