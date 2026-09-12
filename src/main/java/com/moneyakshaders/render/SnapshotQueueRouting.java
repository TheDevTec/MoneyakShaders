package com.moneyakshaders.render;

import java.util.Queue;
import java.util.function.ToIntFunction;

/** A changed priority moves live work; only cancelled jobs may disappear from the queues. */
final class SnapshotQueueRouting {
    static <T> T poll(Queue<T> urgent, Queue<T> streaming, boolean highPriority, ToIntFunction<T> lane) {
        Queue<T> source = highPriority ? urgent : streaming;
        T entry;
        while ((entry = source.poll()) != null) {
            int current = lane.applyAsInt(entry);
            if (current < 0) continue;
            if ((current == 0) == highPriority) return entry;
            (current == 0 ? urgent : streaming).add(entry);
        }
        return null;
    }
}
