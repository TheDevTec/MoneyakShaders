package com.moneyakshaders.render;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * Compact multi-producer / single-consumer queue for section keys.
 *
 * <p>A {@code ConcurrentHashMap.newKeySet()} boxes every packed section key and its iterator is
 * weakly consistent, which is a poor fit for the hot dirty-section path. Producers only need to
 * publish a key once; the render thread drains a coherent batch once per frame. A short lock keeps
 * the primitive queue and dedupe set atomic while avoiding per-key nodes, boxing and resize churn.
 */
final class MpscLongDeduplicatingQueue {
	private final Object lock = new Object();
	private final LongOpenHashSet pending = new LongOpenHashSet();
	private final LongArrayFIFOQueue order = new LongArrayFIFOQueue();

	/** Adds a key only when it is not already awaiting consumption. */
	boolean offer(long key) {
		synchronized (lock) {
			if (!pending.add(key)) {
				return false;
			}
			order.enqueue(key);
			return true;
		}
	}

	/**
	 * Moves up to {@code limit} keys into the render thread's reusable scratch list.
	 * A non-positive limit drains all currently published keys.
	 */
	int drainTo(LongArrayList sink, int limit) {
		sink.clear();
		synchronized (lock) {
			int count = 0;
			while (!order.isEmpty() && (limit <= 0 || count < limit)) {
				long key = order.dequeueLong();
				pending.remove(key);
				sink.add(key);
				count++;
			}
			return count;
		}
	}

	void clear() {
		synchronized (lock) {
			pending.clear();
			order.clear();
		}
	}

	boolean isEmpty() {
		synchronized (lock) {
			return order.isEmpty();
		}
	}
}
