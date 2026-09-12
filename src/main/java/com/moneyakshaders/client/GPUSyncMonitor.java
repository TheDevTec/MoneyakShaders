package com.moneyakshaders.client;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Monitor GPU pipeline synchronization points.
 * Prevents CPU-GPU stalls by tracking GPU busy state and adjusting mesh generation accordingly.
 *
 * <p>Works with Iris to avoid overwhelming the GPU with mesh tasks
 * when the GPU is already saturated with shader work.
 */
public final class GPUSyncMonitor {
	private static volatile GPUSyncMonitor instance;

	private final AtomicLong lastGPUSubmitTime = new AtomicLong(0);
	private final AtomicLong lastGPUSyncTime = new AtomicLong(0);
	private final AtomicLong gpuFrameTimeNanos = new AtomicLong(0);

	private volatile boolean gpuBusy = false;

	private GPUSyncMonitor() {
	}

	public static GPUSyncMonitor getInstance() {
		GPUSyncMonitor result = instance;
		if (result == null) {
			synchronized (GPUSyncMonitor.class) {
				result = instance;
				if (result == null) {
					result = instance = new GPUSyncMonitor();
				}
			}
		}
		return result;
	}

	/**
	 * Record a GPU command submission (Iris render call).
	 * Tracks when GPU work is queued to detect saturation.
	 */
	public void recordGPUSubmit() {
		lastGPUSubmitTime.set(System.nanoTime());
	}

	/**
	 * Record a GPU sync point (fence or flush).
	 * Used to estimate GPU pipeline latency.
	 */
	public void recordGPUSync() {
		long now = System.nanoTime();
		long lastSubmit = lastGPUSubmitTime.get();
		if (lastSubmit > 0) {
			long frameTime = now - lastSubmit;
			gpuFrameTimeNanos.set(frameTime);

			// GPU is "busy" if last frame took >10ms
			gpuBusy = frameTime > 10_000_000L;
		}
		lastGPUSyncTime.set(now);
	}

	/**
	 * Check if GPU is currently busy/saturated.
	 * Used to apply backpressure to mesh generation.
	 */
	public boolean isGPUBusy() {
		return gpuBusy;
	}

	/**
	 * Get estimated GPU frame time in milliseconds.
	 * Higher values indicate shader complexity or overdraw.
	 */
	public double getGPUFrameTimeMs() {
		return gpuFrameTimeNanos.get() / 1_000_000.0;
	}

	/**
	 * Get time since last GPU sync in nanoseconds.
	 * Used to detect stalls or GPU blocking.
	 */
	public long getTimeSinceLastSync() {
		long lastSync = lastGPUSyncTime.get();
		if (lastSync == 0) {
			return 0;
		}
		return System.nanoTime() - lastSync;
	}

	public void reset() {
		lastGPUSubmitTime.set(0);
		lastGPUSyncTime.set(0);
		gpuFrameTimeNanos.set(0);
		gpuBusy = false;
	}
}
