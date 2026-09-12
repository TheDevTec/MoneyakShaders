package com.moneyakshaders.client;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.client.render.VertexConsumer;

/**
 * Aggressive mesh buffer caching - trades RAM for GPU bandwidth reduction.
 * Pre-caches compiled mesh buffers for fast re-rendering without rebuild.
 *
 * <p>For glass-heavy scenes: stores 1000s of pre-compiled glass mesh buffers,
 * eliminating mesh rebuild cost when chunks don't change geometry.
 */
public final class MeshBufferCache {
	private static volatile MeshBufferCache instance;

	private final ConcurrentHashMap<String, CachedMeshBuffer> meshCache = new ConcurrentHashMap<>();
	private final AtomicLong totalRamUsedBytes = new AtomicLong(0);
	private final AtomicLong cacheHits = new AtomicLong(0);
	private final AtomicLong cacheMisses = new AtomicLong(0);

	private static final long MAX_CACHE_SIZE_BYTES = 512 * 1024 * 1024; // 512 MB

	private MeshBufferCache() {
	}

	public static MeshBufferCache getInstance() {
		if (instance == null) {
			synchronized (MeshBufferCache.class) {
				if (instance == null) {
					instance = new MeshBufferCache();
				}
			}
		}
		return instance;
	}

	/**
	 * Cache compiled mesh buffer (main thread).
	 */
	public synchronized void cacheMesh(String meshKey, float[] vertices, int[] indices, long sizeEstimateBytes) {
		if (sizeEstimateBytes <= 0 || sizeEstimateBytes > MAX_CACHE_SIZE_BYTES) return;
		CachedMeshBuffer replaced = meshCache.remove(meshKey);
		if (replaced != null) totalRamUsedBytes.addAndGet(-replaced.estimatedSizeBytes);
		while (totalRamUsedBytes.get() + sizeEstimateBytes > MAX_CACHE_SIZE_BYTES && !meshCache.isEmpty()) {
			evictOldest();
		}
		// A single insertion must never exceed the advertised cap; clone only after admission so an
		// oversized mesh does not create a short-lived multi-hundred-MB allocation.
		if (totalRamUsedBytes.get() + sizeEstimateBytes > MAX_CACHE_SIZE_BYTES) return;
		CachedMeshBuffer cached = new CachedMeshBuffer(vertices.clone(), indices.clone(), sizeEstimateBytes);
		meshCache.put(meshKey, cached);
		totalRamUsedBytes.addAndGet(sizeEstimateBytes);
	}

	/**
	 * Retrieve cached mesh (main thread safe).
	 */
	public CachedMeshBuffer getCachedMesh(String meshKey) {
		CachedMeshBuffer cached = meshCache.get(meshKey);
		if (cached != null) {
			cacheHits.incrementAndGet();
			cached.lastAccessMs = System.currentTimeMillis();
			return cached;
		}
		cacheMisses.incrementAndGet();
		return null;
	}

	/**
	 * Check if mesh is cached.
	 */
	public boolean isCached(String meshKey) {
		return meshCache.containsKey(meshKey);
	}

	private void evictOldest() {
		// Evict least recently used entry
		String oldestKey = null;
		long oldestTime = Long.MAX_VALUE;

		for (String key : meshCache.keySet()) {
			CachedMeshBuffer buf = meshCache.get(key);
			if (buf != null && buf.lastAccessMs < oldestTime) {
				oldestTime = buf.lastAccessMs;
				oldestKey = key;
			}
		}

		if (oldestKey != null) {
			CachedMeshBuffer removed = meshCache.remove(oldestKey);
			if (removed != null) {
				totalRamUsedBytes.addAndGet(-removed.estimatedSizeBytes);
			}
		}
	}

	/**
	 * Get cache statistics.
	 */
	public CacheStats getStats() {
		long hits = cacheHits.get();
		long misses = cacheMisses.get();
		float hitRatio = (hits + misses) > 0 ? (float) hits / (hits + misses) : 0;

		return new CacheStats(meshCache.size(), totalRamUsedBytes.get(), hitRatio, hits, misses);
	}

	/**
	 * Clear cache to free RAM.
	 */
	public void clearCache() {
		meshCache.clear();
		totalRamUsedBytes.set(0);
		cacheHits.set(0);
		cacheMisses.set(0);
	}

	/**
	 * Cached mesh buffer (immutable).
	 */
	public static final class CachedMeshBuffer {
		public final float[] vertices;
		public final int[] indices;
		public final long estimatedSizeBytes;
		public volatile long lastAccessMs;

		public CachedMeshBuffer(float[] vertices, int[] indices, long estimatedSizeBytes) {
			this.vertices = vertices;
			this.indices = indices;
			this.estimatedSizeBytes = estimatedSizeBytes;
			this.lastAccessMs = System.currentTimeMillis();
		}

		public int getVertexCount() {
			return vertices.length / 3; // XYZ per vertex
		}

		public int getIndexCount() {
			return indices.length;
		}
	}

	/**
	 * Cache statistics.
	 */
	public static final class CacheStats {
		public final int cachedMeshes;
		public final long ramUsedBytes;
		public final float hitRatio;
		public final long totalHits;
		public final long totalMisses;

		public CacheStats(int cachedMeshes, long ramUsedBytes, float hitRatio, long totalHits, long totalMisses) {
			this.cachedMeshes = cachedMeshes;
			this.ramUsedBytes = ramUsedBytes;
			this.hitRatio = hitRatio;
			this.totalHits = totalHits;
			this.totalMisses = totalMisses;
		}

		public String getRamUsedMB() {
			return String.format("%.1f MB", ramUsedBytes / 1024.0 / 1024.0);
		}

		public String getHitRatioPercent() {
			return String.format("%.1f%%", hitRatio * 100.0);
		}
	}
}
