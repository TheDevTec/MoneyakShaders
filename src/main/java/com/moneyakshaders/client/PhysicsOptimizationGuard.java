package com.moneyakshaders.client;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Guard that prevents optimizations from interfering with physics and collision.
 * Ensures LOD, culling, and batching never remove collision geometry.
 *
 * <p>Critical safety layer for glass-heavy scenes where occlusion might hide
 * important collision meshes.
 */
public final class PhysicsOptimizationGuard {
	private static volatile PhysicsOptimizationGuard instance;

	private final OcclusionQueryCache occlusionCache;
	private final ConcurrentHashMap<String, Boolean> physicsSafeBlocks = new ConcurrentHashMap<>();
	private final AtomicBoolean isEnabled = new AtomicBoolean(true);

	private PhysicsOptimizationGuard() {
		this.occlusionCache = OcclusionQueryCache.getInstance();
	}

	public static PhysicsOptimizationGuard getInstance() {
		if (instance == null) {
			synchronized (PhysicsOptimizationGuard.class) {
				if (instance == null) {
					instance = new PhysicsOptimizationGuard();
				}
			}
		}
		return instance;
	}

	/**
	 * Check if block geometry can be safely optimized for rendering.
	 * Physics blocks are NEVER culled or LODded.
	 */
	public boolean canOptimize(String blockKey) {
		if (!isEnabled.get()) {
			return false;
		}

		// Check if this block affects physics
		Boolean isPhysicsBlock = physicsSafeBlocks.get(blockKey);
		if (isPhysicsBlock == null) {
			// Conservative: assume physics-relevant until proven otherwise
			isPhysicsBlock = true;
			physicsSafeBlocks.put(blockKey, isPhysicsBlock);
		}

		// Never optimize physics-affecting blocks
		return !isPhysicsBlock;
	}

	/**
	 * Register a block as physics-safe (can be optimized).
	 */
	public void markPhysicsSafe(String blockKey) {
		physicsSafeBlocks.put(blockKey, false);
	}

	/**
	 * Register a block as physics-critical (must not be optimized).
	 */
	public void markPhysicsCritical(String blockKey) {
		physicsSafeBlocks.put(blockKey, true);
	}

	/**
	 * Safety check: prevent occlusion culling for collision-relevant blocks.
	 */
	public boolean shouldRenderForPhysics(String blockKey) {
		if (!isEnabled.get()) {
			return true;
		}

		// Even if occlusion cache says culled, render if physics-critical
		Boolean isPhysicsBlock = physicsSafeBlocks.getOrDefault(blockKey, true);
		if (isPhysicsBlock) {
			// Force render for physics
			return true;
		}

		// Safe to cull if not physics-related
		return !occlusionCache.isOccluded(blockKey);
	}

	/**
	 * Emergency disable: if anomalies detected, disable all aggressive optimizations.
	 */
	public void disableAggressiveOptimizations() {
		isEnabled.set(false);
		occlusionCache.clearCache();
	}

	/**
	 * Re-enable optimizations after anomaly fix.
	 */
	public void enableOptimizations() {
		isEnabled.set(true);
		physicsSafeBlocks.clear();
	}

	/**
	 * Check if optimizations are currently active.
	 */
	public boolean isOptimizationEnabled() {
		return isEnabled.get();
	}

	/**
	 * Clear state (on world change).
	 */
	public void clearState() {
		physicsSafeBlocks.clear();
		isEnabled.set(true);
	}

	/**
	 * Get safety status for diagnostics.
	 */
	public SafetyStatus getStatus() {
		return new SafetyStatus(isEnabled.get(), physicsSafeBlocks.size());
	}

	/**
	 * Safety status report.
	 */
	public static final class SafetyStatus {
		public final boolean isOptimizationEnabled;
		public final int physicsBlocksTracked;

		public SafetyStatus(boolean isOptimizationEnabled, int physicsBlocksTracked) {
			this.isOptimizationEnabled = isOptimizationEnabled;
			this.physicsBlocksTracked = physicsBlocksTracked;
		}

		public boolean isSafe() {
			return isOptimizationEnabled && physicsBlocksTracked > 0;
		}

		public String getStatusMessage() {
			if (!isOptimizationEnabled) {
				return "DISABLED: Anomaly detected, physics protection active";
			}
			return String.format("SAFE: Tracking %d physics blocks", physicsBlocksTracked);
		}
	}
}
