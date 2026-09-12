package com.moneyakshaders.client;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Frame-time histogram. Records the wall-clock time between successive
 * {@link #tickFrameEnd()} calls (one per HUD render) into a ring buffer and
 * exposes p50/p95/p99/max computed on demand. Spec §16 requires these figures
 * so we can tell burst-caused stutter apart from a plain low average.
 *
 * <p>The ring is lock-free: writers publish into an atomic slot, readers copy
 * and sort; a torn value at worst mis-orders one sample per read.
 */
public final class FrameProfiler {
	/** ~4 seconds at 60 fps. Enough to see a 1-frame hitch as a real p99, not smoothed away. */
	private static final int RING = 240;

	private static final AtomicLongArray FRAME_NS = new AtomicLongArray(RING);
	private static final AtomicLong INDEX = new AtomicLong();
	private static final AtomicLong LAST_END_NS = new AtomicLong();

	private static volatile long p50Ns, p95Ns, p99Ns, maxNs, avgNs, latestNs;

	private FrameProfiler() {
	}

	/** Call once per rendered frame (from the HUD render, which runs near end of frame). */
	public static void tickFrameEnd() {
		long now = System.nanoTime();
		long prev = LAST_END_NS.getAndSet(now);
		if (prev == 0L) {
			return;
		}
		long dt = now - prev;
		if (dt <= 0L || dt > 5_000_000_000L) {
			return;
		}
		// RING is 240, not a power of two: a bit mask repeatedly overwrote only part
		// of the history and distorted the percentiles driving streaming budgets.
		int idx = (int) Math.floorMod(INDEX.getAndIncrement(), (long) RING);
		FRAME_NS.set(idx, dt);
		latestNs = dt;
	}

	/** Recompute percentiles from the ring. Callable once/second; not free (allocates + sort). */
	public static void snapshot() {
		long[] copy = new long[RING];
		int n = 0;
		long sum = 0L;
		for (int i = 0; i < RING; i++) {
			long v = FRAME_NS.get(i);
			if (v > 0L) {
				copy[n++] = v;
				sum += v;
			}
		}
		if (n == 0) {
			p50Ns = p95Ns = p99Ns = maxNs = avgNs = 0L;
			return;
		}
		long[] trimmed = Arrays.copyOf(copy, n);
		Arrays.sort(trimmed);
		p50Ns = trimmed[(int) (n * 0.50)];
		p95Ns = trimmed[Math.min(n - 1, (int) (n * 0.95))];
		p99Ns = trimmed[Math.min(n - 1, (int) (n * 0.99))];
		maxNs = trimmed[n - 1];
		avgNs = sum / n;
	}

	public static double p50Ms() { return p50Ns / 1_000_000.0; }
	public static double p95Ms() { return p95Ns / 1_000_000.0; }
	public static double p99Ms() { return p99Ns / 1_000_000.0; }
	public static double maxMs() { return maxNs / 1_000_000.0; }
	public static double avgMs() { return avgNs / 1_000_000.0; }
	/** Most recently completed frame; unlike the percentile snapshot this updates every frame. */
	public static double latestMs() { return latestNs / 1_000_000.0; }
}
