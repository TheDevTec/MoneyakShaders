package com.moneyakshaders.client;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Per-section event timeline. Spec §22 fáze 1 requires a "timeline of chunk
 * loading" so we can see which pipeline phase eats real time when the world
 * streams in.
 *
 * <p>For each section (packed long chunkX/chunkY/chunkZ), records the ns
 * timestamp of each phase it passes through. A ring of the most recent 512
 * sections is kept and phase-to-phase latency percentiles are derived from it.
 * Sections outside the ring are dropped (bounded memory).
 */
public final class ChunkLoadTimeline {
	public static final int PHASE_RECEIVED = 0;
	public static final int PHASE_SNAPSHOT_DONE = 1;
	public static final int PHASE_MESH_QUEUED = 2;
	public static final int PHASE_MESH_STARTED = 3;
	public static final int PHASE_MESH_DONE = 4;
	public static final int PHASE_UPLOAD_QUEUED = 5;
	public static final int PHASE_UPLOAD_DONE = 6;
	public static final int PHASE_FIRST_DRAWN = 7;
	private static final int PHASE_COUNT = 8;

	private static final int RING = 512;
	private static final AtomicInteger INDEX = new AtomicInteger();
	private static final AtomicLongArray KEYS = new AtomicLongArray(RING);
	private static final AtomicLongArray STAMPS = new AtomicLongArray(RING * PHASE_COUNT);
	private static final ConcurrentHashMap<Long, Integer> KEY_TO_SLOT = new ConcurrentHashMap<>();

	private static volatile double[] p50PhaseMs = new double[PHASE_COUNT];
	private static volatile double[] p95PhaseMs = new double[PHASE_COUNT];
	private static volatile double[] p99PhaseMs = new double[PHASE_COUNT];
	private static volatile int lastSampleCount;

	private ChunkLoadTimeline() {
	}

	public static long key(int chunkX, int chunkY, int chunkZ) {
		return ((long) (chunkX & 0x3FFFFFF) << 38)
				| ((long) (chunkZ & 0x3FFFFFF) << 12)
				| (long) (chunkY & 0xFFF);
	}

	public static void mark(long sectionKey, int phase) {
		// Timeline is useful diagnostics, never a reason to make the hot streaming path allocate
		// concurrent-map entries. The public callers intentionally do not all have to carry this
		// condition, so disabling the option makes every phase marker a single cheap branch.
		if (!com.moneyakshaders.MoneyakShadersConfig.get().chunkLoadTimelineEnabled) return;
		if (phase < 0 || phase >= PHASE_COUNT) return;
		Integer slot = KEY_TO_SLOT.get(sectionKey);
		if (slot == null) {
			int fresh = INDEX.getAndIncrement() & (RING - 1);
			long evicted = KEYS.getAndSet(fresh, sectionKey);
			if (evicted != 0L) {
				KEY_TO_SLOT.remove(evicted, fresh);
			}
			for (int i = 0; i < PHASE_COUNT; i++) {
				STAMPS.set(fresh * PHASE_COUNT + i, 0L);
			}
			slot = fresh;
			KEY_TO_SLOT.put(sectionKey, slot);
		}
		STAMPS.set(slot * PHASE_COUNT + phase, System.nanoTime());
	}

	/** Recompute percentiles for each phase's latency FROM PHASE_RECEIVED. Cheap; call ~1/s. */
	public static void snapshot() {
		long[][] deltas = new long[PHASE_COUNT][RING];
		int[] counts = new int[PHASE_COUNT];
		int total = 0;
		for (int i = 0; i < RING; i++) {
			long recv = STAMPS.get(i * PHASE_COUNT + PHASE_RECEIVED);
			if (recv == 0L) continue;
			total++;
			for (int p = 1; p < PHASE_COUNT; p++) {
				long stamp = STAMPS.get(i * PHASE_COUNT + p);
				if (stamp <= recv) continue;
				deltas[p][counts[p]++] = stamp - recv;
			}
		}
		double[] p50 = new double[PHASE_COUNT];
		double[] p95 = new double[PHASE_COUNT];
		double[] p99 = new double[PHASE_COUNT];
		for (int p = 1; p < PHASE_COUNT; p++) {
			int c = counts[p];
			if (c == 0) continue;
			long[] arr = Arrays.copyOf(deltas[p], c);
			Arrays.sort(arr);
			p50[p] = arr[(int) (c * 0.50)] / 1_000_000.0;
			p95[p] = arr[Math.min(c - 1, (int) (c * 0.95))] / 1_000_000.0;
			p99[p] = arr[Math.min(c - 1, (int) (c * 0.99))] / 1_000_000.0;
		}
		p50PhaseMs = p50;
		p95PhaseMs = p95;
		p99PhaseMs = p99;
		lastSampleCount = total;
	}

	public static double p95Ms(int phase) {
		double[] arr = p95PhaseMs;
		return phase < 0 || phase >= arr.length ? 0.0 : arr[phase];
	}

	public static double p50Ms(int phase) {
		double[] arr = p50PhaseMs;
		return phase < 0 || phase >= arr.length ? 0.0 : arr[phase];
	}

	public static double p99Ms(int phase) {
		double[] arr = p99PhaseMs;
		return phase < 0 || phase >= arr.length ? 0.0 : arr[phase];
	}

	public static int lastSampleCount() { return lastSampleCount; }
}
