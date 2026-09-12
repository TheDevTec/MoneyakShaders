package com.moneyakshaders.client;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Chunk distance pre-computation cache.
 * Pre-calculates distances from chunks to camera to avoid repeated sqrt() calls.
 *
 * <p>RAM trade-off: ~16 bytes per chunk, but eliminates 1000s of distance calculations/frame.
 */
public final class ChunkDistancePrecomputer {
	private static volatile ChunkDistancePrecomputer instance;

	private final ConcurrentHashMap<String, ChunkDistance> distanceCache = new ConcurrentHashMap<>();
	private final AtomicInteger totalCached = new AtomicInteger(0);

	private ChunkDistancePrecomputer() {
	}

	public static ChunkDistancePrecomputer getInstance() {
		if (instance == null) {
			synchronized (ChunkDistancePrecomputer.class) {
				if (instance == null) {
					instance = new ChunkDistancePrecomputer();
				}
			}
		}
		return instance;
	}

	/**
	 * Pre-compute chunk distance (worker thread).
	 */
	public void queueDistanceComputation(String chunkKey, int chunkX, int chunkZ, float camX, float camY, float camZ) {
		ChunkMeshExecutor.getOrCreate()
				.execute(() -> computeDistance(chunkKey, chunkX, chunkZ, camX, camY, camZ));
	}

	private void computeDistance(String chunkKey, int chunkX, int chunkZ, float camX, float camY, float camZ) {
		// Calculate chunk center
		float chunkCenterX = chunkX * 16.0f + 8.0f;
		float chunkCenterZ = chunkZ * 16.0f + 8.0f;
		float chunkCenterY = 64.0f; // Mid-height

		// Calculate distance
		float dx = chunkCenterX - camX;
		float dy = chunkCenterY - camY;
		float dz = chunkCenterZ - camZ;

		float distanceSquared = dx * dx + dy * dy + dz * dz;
		float distance = (float) Math.sqrt(distanceSquared);

		ChunkDistance dist = new ChunkDistance(distance, distanceSquared, chunkX, chunkZ);
		distanceCache.put(chunkKey, dist);
		totalCached.incrementAndGet();
	}

	/**
	 * Get cached distance (main thread safe).
	 */
	public ChunkDistance getDistance(String chunkKey) {
		return distanceCache.get(chunkKey);
	}

	/**
	 * Get distance for fast comparisons (squared).
	 */
	public float getDistanceSquared(String chunkKey) {
		ChunkDistance dist = distanceCache.get(chunkKey);
		return dist != null ? dist.distanceSquared : Float.MAX_VALUE;
	}

	/**
	 * Check if cached.
	 */
	public boolean isCached(String chunkKey) {
		return distanceCache.containsKey(chunkKey);
	}

	/**
	 * Get statistics.
	 */
	public DistanceStats getStats() {
		int total = totalCached.get();
		long ramUsedBytes = total * 24; // ~24 bytes per entry

		return new DistanceStats(total, ramUsedBytes);
	}

	/**
	 * Clear cache.
	 */
	public void clearCache() {
		distanceCache.clear();
		totalCached.set(0);
	}

	/**
	 * Chunk distance data (immutable).
	 */
	public static final class ChunkDistance {
		public final float distance;
		public final float distanceSquared;
		public final int chunkX;
		public final int chunkZ;

		public ChunkDistance(float distance, float distanceSquared, int chunkX, int chunkZ) {
			this.distance = distance;
			this.distanceSquared = distanceSquared;
			this.chunkX = chunkX;
			this.chunkZ = chunkZ;
		}

		public boolean isWithinDistance(float maxDistance) {
			return distanceSquared <= maxDistance * maxDistance;
		}

		public int getLODLevel(float[] lodThresholds) {
			for (int i = 0; i < lodThresholds.length; i++) {
				if (distance <= lodThresholds[i]) {
					return i;
				}
			}
			return lodThresholds.length - 1;
		}
	}

	/**
	 * Distance statistics.
	 */
	public static final class DistanceStats {
		public final int cachedChunks;
		public final long estimatedRamBytes;

		public DistanceStats(int cachedChunks, long estimatedRamBytes) {
			this.cachedChunks = cachedChunks;
			this.estimatedRamBytes = estimatedRamBytes;
		}

		public String getRamUsedMB() {
			return String.format("%.1f MB", estimatedRamBytes / 1024.0 / 1024.0);
		}
	}
}
