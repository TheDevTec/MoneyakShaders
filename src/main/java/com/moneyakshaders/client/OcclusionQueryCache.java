package com.moneyakshaders.client;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Occlusion query cache for glass visibility prediction.
 * Skip rendering glass that's completely occluded behind opaque geometry.
 *
 * <p>Pre-computes visibility flags in worker threads; main thread uses cached results
 * to cull rendering of invisible glass blocks, saving fragment shader work.
 */
public final class OcclusionQueryCache {
	private static volatile OcclusionQueryCache instance;

	private final ConcurrentHashMap<String, OcclusionResult> occlusionCache = new ConcurrentHashMap<>();
	private final AtomicInteger totalQueries = new AtomicInteger(0);
	private final AtomicInteger culledQueries = new AtomicInteger(0);

	private OcclusionQueryCache() {
	}

	public static OcclusionQueryCache getInstance() {
		if (instance == null) {
			synchronized (OcclusionQueryCache.class) {
				if (instance == null) {
					instance = new OcclusionQueryCache();
				}
			}
		}
		return instance;
	}

	/**
	 * Schedule async occlusion query for glass block.
	 */
	public void queueOcclusionQuery(String blockKey, int x, int y, int z, boolean hasOpaqueNeighbors) {
		totalQueries.incrementAndGet();
		ChunkMeshExecutor.getOrCreate()
				.execute(() -> queryOcclusion(blockKey, x, y, z, hasOpaqueNeighbors));
	}

	private void queryOcclusion(String blockKey, int x, int y, int z, boolean hasOpaqueNeighbors) {
		// Determine occlusion status based on neighboring blocks
		boolean isOccluded = computeOcclusion(x, y, z, hasOpaqueNeighbors);

		if (isOccluded) {
			culledQueries.incrementAndGet();
		}

		occlusionCache.put(blockKey, new OcclusionResult(isOccluded, hasOpaqueNeighbors));
	}

	private boolean computeOcclusion(int x, int y, int z, boolean hasOpaqueNeighbors) {
		// If block has opaque neighbors in all directions, it's completely occluded
		// (in reality would check actual neighbor blocks from world)
		if (hasOpaqueNeighbors) {
			// Check six faces (north, south, east, west, up, down)
			// Simplified: assume if surrounded by opaque, then hidden
			return true;
		}

		// Also check if it's inside a dense cluster of glass
		// (would reduce occlusion benefit but still worth querying)
		return false;
	}

	/**
	 * Check if glass block is occluded (main thread safe, cached).
	 * IMPORTANT: Respects physics guard - physics-critical blocks are never culled.
	 */
	public boolean isOccluded(String blockKey) {
		// Check physics guard - never cull physics-critical blocks
		PhysicsOptimizationGuard guard = PhysicsOptimizationGuard.getInstance();
		if (!guard.canOptimize(blockKey)) {
			return false; // Force render for physics blocks
		}

		OcclusionResult result = occlusionCache.get(blockKey);
		if (result == null) {
			return false; // Assume visible if not cached
		}
		return result.isOccluded;
	}

	/**
	 * Check if occlusion is cached (non-blocking).
	 */
	public boolean isCached(String blockKey) {
		return occlusionCache.containsKey(blockKey);
	}

	/**
	 * Get occlusion statistics for monitoring.
	 */
	public OcclusionStats getStats() {
		int total = totalQueries.get();
		int culled = culledQueries.get();
		float cullRatio = total > 0 ? (float) culled / total : 0;

		return new OcclusionStats(total, culled, cullRatio);
	}

	/**
	 * Clear cache to free memory.
	 */
	public void clearCache() {
		occlusionCache.clear();
		totalQueries.set(0);
		culledQueries.set(0);
	}

	/**
	 * Occlusion query result (immutable).
	 */
	public static final class OcclusionResult {
		public final boolean isOccluded;
		public final boolean hasOpaqueNeighbors;

		public OcclusionResult(boolean isOccluded, boolean hasOpaqueNeighbors) {
			this.isOccluded = isOccluded;
			this.hasOpaqueNeighbors = hasOpaqueNeighbors;
		}
	}

	/**
	 * Occlusion statistics (immutable).
	 */
	public static final class OcclusionStats {
		public final int totalQueries;
		public final int culledQueries;
		public final float cullRatio;

		public OcclusionStats(int totalQueries, int culledQueries, float cullRatio) {
			this.totalQueries = totalQueries;
			this.culledQueries = culledQueries;
			this.cullRatio = cullRatio;
		}

		public int getSavedDrawCalls() {
			return culledQueries;
		}

		public float getEfficiency() {
			return cullRatio * 100.0f; // Percentage of blocks culled
		}
	}
}
