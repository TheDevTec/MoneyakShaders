package com.moneyakshaders.render;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Iterator;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Owns detached registries until their resources have been released in bounded slices. */
final class DeferredDisposal<T> {
    private final ArrayDeque<Iterator<T>> batches = new ArrayDeque<>();
    private int pending;

    void detach(Collection<T> values) {
        if (!values.isEmpty()) { batches.add(values.iterator()); pending += values.size(); }
    }

    int drain(int limit, long nanos, LongSupplier clock, Consumer<T> disposer) {
        long start = clock.getAsLong();
        int disposed = 0;
        while (disposed < limit && !batches.isEmpty()) {
            Iterator<T> iterator = batches.peek();
            if (!iterator.hasNext()) { batches.remove(); continue; }
            disposer.accept(iterator.next());
            pending--; disposed++;
            if (!iterator.hasNext()) batches.remove();
            if (clock.getAsLong() - start >= nanos) break;
        }
        return disposed;
    }

    int pending() { return pending; }
}
