package com.moneyakshaders.render;

import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Best-fit free ranges indexed by both offset and size. All operations are render-thread owned. */
final class ArenaAllocator {
    private final TreeMap<Long, Long> ranges = new TreeMap<>();
    private final TreeMap<Long, TreeSet<Long>> bySize = new TreeMap<>();
    private long freeBytes;

    ArenaAllocator(long capacity) { add(0, capacity); freeBytes = capacity; }

    private void add(long offset, long size) {
        ranges.put(offset, size);
        bySize.computeIfAbsent(size, ignored -> new TreeSet<>()).add(offset);
    }

    private void remove(long offset, long size) {
        ranges.remove(offset);
        TreeSet<Long> offsets = bySize.get(size);
        offsets.remove(offset);
        if (offsets.isEmpty()) bySize.remove(size);
    }

    long alloc(long size) {
        if (size <= 0) return -1;
        Map.Entry<Long, TreeSet<Long>> fit = bySize.ceilingEntry(size);
        if (fit == null) return -1;
        long available = fit.getKey(), offset = fit.getValue().first();
        remove(offset, available);
        if (available > size) add(offset + size, available - size);
        freeBytes -= size;
        return offset;
    }

    void free(long offset, long size) {
        if (size <= 0) return;
        freeBytes += size;
        Map.Entry<Long, Long> before = ranges.lowerEntry(offset);
        if (before != null && before.getKey() + before.getValue() == offset) {
            long previousSize = before.getValue();
            offset = before.getKey();
            remove(offset, previousSize);
            size += previousSize;
        }
        Long after = ranges.get(offset + size);
        if (after != null) {
            remove(offset + size, after);
            size += after;
        }
        add(offset, size);
    }

    long freeBytes() { return freeBytes; }
    long largestFreeBlock() { return bySize.isEmpty() ? 0 : bySize.lastKey(); }
}
