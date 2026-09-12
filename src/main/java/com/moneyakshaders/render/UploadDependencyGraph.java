package com.moneyakshaders.render;

import java.util.function.LongPredicate;
import it.unimi.dsi.fastutil.longs.*;

/** Dependencies belong to pending geometry edits, not to unrelated future lighting revisions. */
final class UploadDependencyGraph {
    private final Long2ObjectOpenHashMap<LongOpenHashSet> dependencies = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<LongOpenHashSet> waiters = new Long2ObjectOpenHashMap<>();

    boolean add(long waiter, long dependency) {
        if (waiter == dependency || reaches(dependency, waiter)) return false;
        dependencies.computeIfAbsent(waiter, ignored -> new LongOpenHashSet()).add(dependency);
        waiters.computeIfAbsent(dependency, ignored -> new LongOpenHashSet()).add(waiter);
        return true;
    }

    boolean ready(long waiter, LongPredicate irrelevant) {
        LongOpenHashSet required = dependencies.get(waiter);
        if (required == null) return true;
        for (LongIterator it = required.iterator(); it.hasNext();) {
            long dependency = it.nextLong();
            if (!irrelevant.test(dependency)) continue;
            it.remove();
            LongOpenHashSet reverse = waiters.get(dependency);
            if (reverse != null) { reverse.remove(waiter); if (reverse.isEmpty()) waiters.remove(dependency); }
        }
        if (!required.isEmpty()) return false;
        dependencies.remove(waiter);
        return true;
    }

    void published(long key) {
        LongOpenHashSet blocked = waiters.remove(key);
        if (blocked == null) return;
        for (long waiter : blocked) {
            LongOpenHashSet required = dependencies.get(waiter);
            if (required != null) { required.remove(key); if (required.isEmpty()) dependencies.remove(waiter); }
        }
    }

    void remove(long waiter) {
        LongOpenHashSet required = dependencies.remove(waiter);
        if (required == null) return;
        for (long dependency : required) {
            LongOpenHashSet reverse = waiters.get(dependency);
            if (reverse != null) { reverse.remove(waiter); if (reverse.isEmpty()) waiters.remove(dependency); }
        }
    }

    private boolean reaches(long start, long target) {
        LongArrayList todo = new LongArrayList();
        LongOpenHashSet seen = new LongOpenHashSet();
        todo.add(start);
        while (!todo.isEmpty()) {
            long key = todo.removeLong(todo.size() - 1);
            if (key == target) return true;
            if (!seen.add(key)) continue;
            LongOpenHashSet required = dependencies.get(key);
            if (required != null) for (long dependency : required) todo.add(dependency);
        }
        return false;
    }

    void clear() { dependencies.clear(); waiters.clear(); }
}
