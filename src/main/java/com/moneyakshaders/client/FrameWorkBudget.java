package com.moneyakshaders.client;

import java.util.concurrent.atomic.AtomicLong;

import com.moneyakshaders.MoneyakShadersConfig;

import java.util.Arrays;

/**
 * Adaptive per-frame work budget (spec §15). Instead of a hard "N items per
 * frame" cap, buckets get a wall-clock ms allowance and any consumer can ask
 * how many ns are left in their bucket for the current frame.
 *
 * <p>Buckets are frame-scoped: {@link #beginFrame()} resets them. Consumers
 * call {@link #startBucket(String)} → do work in a while-loop guarded by
 * {@link #hasBudget(String)} → {@link #endBucket(String)}. The bookkeeping is
 * lock-free (atomic longs, one per bucket).
 *
 * <p>Adaptivity: when the rolling frame average overruns the target frametime,
 * next frame the budgets are proportionally shrunk. When frames finish
 * comfortably under target, budgets expand back to their configured ceiling.
 * Small integer moves per frame (spec: hysterézi).
 */
public final class FrameWorkBudget {
	public static final String BUCKET_MESH_UPLOAD = "mesh-upload";
	public static final String BUCKET_SNAPSHOT = "section-snapshot";
	public static final String BUCKET_SHADOW = "shadow";
	public static final String BUCKET_LIGHT_APPLY = "light-apply";

	private static final class Bucket {
		private static final int HISTORY = 120;
		final AtomicLong startNs = new AtomicLong();
		final AtomicLong consumedNs = new AtomicLong();
		volatile long allowanceNs;
		volatile long ceilingNs;
		final long[] samplesNs = new long[HISTORY];
		int sampleCount;
		int sampleCursor;
		synchronized void record(long durationNs) {
			samplesNs[sampleCursor++ % HISTORY] = durationNs;
			if (sampleCount < HISTORY) sampleCount++;
		}
		synchronized double percentileMs(double percentile) {
			if (sampleCount == 0) return 0.0;
			long[] sorted = Arrays.copyOf(samplesNs, sampleCount);
			Arrays.sort(sorted);
			return sorted[Math.min(sampleCount - 1, (int) Math.floor((sampleCount - 1) * percentile))] / 1_000_000.0;
		}
	}

	private static final Bucket MESH_UPLOAD = new Bucket();
	private static final Bucket SNAPSHOT = new Bucket();
	private static final Bucket SHADOW = new Bucket();
	private static final Bucket LIGHT_APPLY = new Bucket();
	private static final Bucket BLOCK_EDIT = new Bucket();

	private static volatile long frameStartNs;
	private static volatile int scalePct = 100;
	private static volatile int effectiveScalePct = 100;
	// Snapshot extraction is the only render-thread streaming producer. It may safely use spare
	// frame time, unlike uploads/shadows which can create GPU stalls, so it has its own headroom scale.
	private static volatile int snapshotScalePct = 100;
	private static volatile int effectiveSnapshotScalePct = 100;
	private static final StreamingFrameBudget STREAMING = new StreamingFrameBudget();
	private static double effectiveTargetMs = 4.25;
	public static double effectiveTargetMs() { return effectiveTargetMs; }
	// Percentiles intentionally describe a short history, but reacting to the same sample on every
	// frame still turns one loading hitch into a visible quality/budget swing.  Require sustained
	// pressure and only move the governor at a coarse cadence instead.
	private static int pressureFrames;
	private static int recoveryFrames;
	private static final int GOVERNOR_INTERVAL_FRAMES = 20;
	private static final int PRESSURE_CONFIRMATION_FRAMES = 40;

	private FrameWorkBudget() {
	}

	public static void beginFrame() {
		MoneyakShadersConfig cfg = MoneyakShadersConfig.get();
		frameStartNs = System.nanoTime();

		double lastMs = FrameProfiler.avgMs();
		double p95Ms = FrameProfiler.p95Ms();
		double p99Ms = FrameProfiler.p99Ms();
		double latestMs = FrameProfiler.latestMs();
		boolean streaming = com.moneyakshaders.render.ExperimentalSectionRender.pendingSnapshotCount() > 0
				|| com.moneyakshaders.render.ExperimentalSectionRender.meshInFlightCount() > 0
				|| com.moneyakshaders.render.ExperimentalSectionRender.uploadBacklogCount() > 0;
		STREAMING.sample(latestMs, (SNAPSHOT.consumedNs.get() + MESH_UPLOAD.consumedNs.get()
				+ BLOCK_EDIT.consumedNs.get()) / 1_000_000.0,
				streaming);
		effectiveTargetMs = STREAMING.targetMs(cfg.frameBudgetTargetMs);
		if (lastMs > 0.0 && cfg.frameBudgetTargetMs > 0) {
			double target = effectiveTargetMs;
			// The average hides exactly the teleport/chunk-burst hitches this governor exists to
			// prevent. p95 controls sustained pressure; an extreme p99 gets one stronger but still
			// bounded correction. Percentiles are refreshed once/sec and therefore deliberately
			// cannot cause frame-to-frame oscillation.
			boolean emergencyTail = p99Ms > target * 2.0;
			boolean tailPressure = p95Ms > target * 1.10 || p99Ms > target * 1.45;
			boolean underPressure = emergencyTail || tailPressure || lastMs > target * 1.15;
			if (underPressure) {
				pressureFrames++;
				recoveryFrames = 0;
			} else if (lastMs < target * 0.85) {
				recoveryFrames++;
				pressureFrames = 0;
			} else {
				pressureFrames = 0;
				recoveryFrames = 0;
			}
			if (pressureFrames >= PRESSURE_CONFIRMATION_FRAMES
					&& pressureFrames % GOVERNOR_INTERVAL_FRAMES == 0) {
				if (scalePct > 40) scalePct -= emergencyTail ? 8 : 4;
				if (snapshotScalePct > 40) snapshotScalePct -= emergencyTail ? 12 : 8;
			} else if (recoveryFrames >= PRESSURE_CONFIRMATION_FRAMES
					&& recoveryFrames % GOVERNOR_INTERVAL_FRAMES == 0) {
				if (scalePct < 100) scalePct += 2;
				if (lastMs < target * 0.60 && snapshotScalePct < 160) snapshotScalePct += 4;
				else if (snapshotScalePct < 100) snapshotScalePct += 2;
			}
		} else {
			scalePct = 100;
			snapshotScalePct = 100;
			pressureFrames = 0;
			recoveryFrames = 0;
		}

		// Percentile control deliberately has hysteresis, but chunk packet/snapshot bursts need a
		// one-frame feedback path as well. Contract the very next frame's optional work from the latest
		// completed frame; a fast frame instantly restores the persistent governor's normal allowance.
		int immediateScale = 100;
		if (cfg.frameBudgetTargetMs > 0 && latestMs > 0.0) {
			double target = effectiveTargetMs;
			if (latestMs > target * 2.0) immediateScale = 35;
			else if (latestMs > target * 1.5) immediateScale = 45;
			else if (latestMs > target * 1.15) immediateScale = 60;
			else if (latestMs > target) immediateScale = 75;
			else if (latestMs > target * 0.90) immediateScale = 90;
		}
		effectiveScalePct = Math.min(scalePct, immediateScale);
		effectiveSnapshotScalePct = immediateScale < 100
				? Math.min(snapshotScalePct, immediateScale)
				: snapshotScalePct;

		configure(MESH_UPLOAD, cfg.frameBudgetMeshUploadMs, effectiveScalePct);
		configure(SNAPSHOT, cfg.frameBudgetSnapshotMs, effectiveSnapshotScalePct);
		int snapshots = com.moneyakshaders.render.ExperimentalSectionRender.pendingSnapshotCount();
		int meshes = com.moneyakshaders.render.ExperimentalSectionRender.meshInFlightCount();
		int uploads = com.moneyakshaders.render.ExperimentalSectionRender.uploadBacklogCount();
		long preparationNs = STREAMING.preparationNanos(snapshots, meshes, uploads, latestMs);
		int snapshotShare = snapshots > 0 && uploads == 0 && meshes <= 2 ? 90 : 65;
		if (MESH_UPLOAD.allowanceNs > 0) MESH_UPLOAD.allowanceNs = Math.min(MESH_UPLOAD.allowanceNs, preparationNs * (100 - snapshotShare) / 100);
		if (SNAPSHOT.allowanceNs > 0) SNAPSHOT.allowanceNs = Math.min(SNAPSHOT.allowanceNs, preparationNs * snapshotShare / 100);
		configure(SHADOW, cfg.frameBudgetShadowMs);
		configure(LIGHT_APPLY, cfg.frameBudgetLightApplyMs);
		configure(BLOCK_EDIT, 1, 60);
	}

	private static void configure(Bucket b, int cfgMs) {
		configure(b, cfgMs, scalePct);
	}

	private static void configure(Bucket b, int cfgMs, int localScalePct) {
		long ceiling = Math.max(0L, cfgMs) * 1_000_000L;
		b.ceilingNs = ceiling;
		b.allowanceNs = ceiling * localScalePct / 100L;
		b.consumedNs.set(0L);
		b.startNs.set(0L);
	}

	private static Bucket forName(String name) {
		if (BUCKET_MESH_UPLOAD.equals(name)) return MESH_UPLOAD;
		if (BUCKET_SNAPSHOT.equals(name)) return SNAPSHOT;
		if (BUCKET_SHADOW.equals(name)) return SHADOW;
		if (BUCKET_LIGHT_APPLY.equals(name)) return LIGHT_APPLY;
		if ("block-edit".equals(name)) return BLOCK_EDIT;
		return null;
	}

	public static void startBucket(String name) {
		Bucket b = forName(name);
		if (b != null) b.startNs.set(System.nanoTime());
	}

	public static void endBucket(String name) {
		Bucket b = forName(name);
		if (b == null) return;
		long start = b.startNs.getAndSet(0L);
		if (start == 0L) return;
		long duration = System.nanoTime() - start;
		b.consumedNs.addAndGet(duration);
		b.record(duration);
	}

	public static boolean hasBudget(String name) {
		if (!MoneyakShadersConfig.get().frameBudgetEnabled) return true;
		Bucket b = forName(name);
		if (b == null) return true;
		long allowance = b.allowanceNs;
		if (allowance <= 0L) return true;
		long consumed = b.consumedNs.get();
		long start = b.startNs.get();
		long live = start == 0L ? 0L : System.nanoTime() - start;
		return consumed + live < allowance;
	}

	public static int currentScalePct() { return effectiveScalePct; }
	public static int snapshotScalePct() { return effectiveSnapshotScalePct; }

	public static double consumedMs(String name) {
		Bucket b = forName(name);
		return b == null ? 0.0 : b.consumedNs.get() / 1_000_000.0;
	}

	public static double allowanceMs(String name) {
		Bucket b = forName(name);
		return b == null ? 0.0 : b.allowanceNs / 1_000_000.0;
	}

	public static double p50Ms(String name) { return percentileMs(name, 0.50); }
	public static double p95Ms(String name) { return percentileMs(name, 0.95); }
	public static double p99Ms(String name) { return percentileMs(name, 0.99); }

	private static double percentileMs(String name, double percentile) {
		Bucket b = forName(name);
		return b == null ? 0.0 : b.percentileMs(percentile);
	}
}
