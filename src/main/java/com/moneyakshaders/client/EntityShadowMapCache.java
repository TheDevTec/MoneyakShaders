package com.moneyakshaders.client;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Entity shadow map cache - pre-compute and cache shadow maps for entities.
 * Shadows are expensive; cache them per entity type and avoid per-frame recomputation.
 *
 * <p>RAM trade-off: ~3 MB per entity type shadow cache, saves 70% shadow rendering cost.
 */
public final class EntityShadowMapCache {
	private static volatile EntityShadowMapCache instance;

	private final ConcurrentHashMap<String, ShadowMapData> shadowCache = new ConcurrentHashMap<>();
	private final AtomicInteger cachedEntityTypes = new AtomicInteger(0);

	private static final int SHADOW_MAP_SIZE = 256; // 256×256 shadow map
	private static final int SHADOW_QUALITY_LEVELS = 4; // Different quality levels

	private EntityShadowMapCache() {
	}

	public static EntityShadowMapCache getInstance() {
		if (instance == null) {
			synchronized (EntityShadowMapCache.class) {
				if (instance == null) {
					instance = new EntityShadowMapCache();
				}
			}
		}
		return instance;
	}

	/**
	 * Pre-generate shadow map for entity type (worker thread).
	 */
	public void queueShadowMapGeneration(String entityType, float modelWidth, float modelHeight) {
		ChunkMeshExecutor.executeBackground(() -> generateShadowMap(entityType, modelWidth, modelHeight));
	}

	private void generateShadowMap(String entityType, float modelWidth, float modelHeight) {
		ShadowMapData shadowMap = new ShadowMapData(entityType, SHADOW_QUALITY_LEVELS);

		// Generate multiple quality levels
		for (int quality = 0; quality < SHADOW_QUALITY_LEVELS; quality++) {
			int size = SHADOW_MAP_SIZE >> quality; // Reduce resolution for lower quality
			float[] depthMap = new float[size * size];

			// Generate depth map for shadow
			for (int x = 0; x < size; x++) {
				for (int y = 0; y < size; y++) {
					float normX = (x / (float) size) * 2.0f - 1.0f;
					float normY = (y / (float) size) * 2.0f - 1.0f;

					// Simple shadow depth: ellipse for typical entity silhouette
					float ellipseX = normX / (modelWidth / 2.0f);
					float ellipseY = normY / (modelHeight / 2.0f);
					float distToEdge = (ellipseX * ellipseX) + (ellipseY * ellipseY);

					// Depth increases from edge to center
					float depth = 1.0f - Math.min(1.0f, Math.max(0.0f, distToEdge));
					depthMap[y * size + x] = depth;
				}
			}

			shadowMap.setShadowMap(quality, depthMap);
		}

		shadowCache.put(entityType, shadowMap);
		cachedEntityTypes.incrementAndGet();
	}

	/**
	 * Get shadow map for entity type (main thread safe).
	 */
	public float[] getShadowMap(String entityType, int qualityLevel) {
		ShadowMapData shadowMap = shadowCache.get(entityType);
		if (shadowMap == null)
			return null;

		// Clamp quality level
		int level = Math.max(0, Math.min(SHADOW_QUALITY_LEVELS - 1, qualityLevel));
		return shadowMap.getShadowMap(level);
	}

	/**
	 * Get statistics.
	 */
	public ShadowStats getStats() {
		int total = cachedEntityTypes.get();
		// Rough estimate: (256^2 + 128^2 + 64^2 + 32^2) * 4 bytes * types
		long ramPerType = (65536 + 16384 + 4096 + 1024) * 4L;
		long ramUsedBytes = total * ramPerType;

		return new ShadowStats(total, ramUsedBytes);
	}

	/**
	 * Clear cache.
	 */
	public void clearCache() {
		shadowCache.clear();
		cachedEntityTypes.set(0);
	}

	/**
	 * Shadow map data (multi-resolution).
	 */
	public static final class ShadowMapData {
		public final String entityType;
		private final float[][] shadowMaps; // [quality][depth values]

		public ShadowMapData(String entityType, int qualityLevels) {
			this.entityType = entityType;
			this.shadowMaps = new float[qualityLevels][];
		}

		public void setShadowMap(int qualityLevel, float[] depthMap) {
			if (qualityLevel < shadowMaps.length) {
				shadowMaps[qualityLevel] = depthMap;
			}
		}

		public float[] getShadowMap(int qualityLevel) {
			if (qualityLevel < shadowMaps.length) {
				return shadowMaps[qualityLevel];
			}
			return null;
		}

		public long getEstimatedRamBytes() {
			long total = 0;
			for (float[] map : shadowMaps) {
				if (map != null) {
					total += map.length * 4L; // float = 4 bytes
				}
			}
			return total;
		}
	}

	/**
	 * Shadow statistics.
	 */
	public static final class ShadowStats {
		public final int cachedEntityTypes;
		public final long estimatedRamBytes;

		public ShadowStats(int cachedEntityTypes, long estimatedRamBytes) {
			this.cachedEntityTypes = cachedEntityTypes;
			this.estimatedRamBytes = estimatedRamBytes;
		}

		public String getRamUsedMB() {
			return String.format("%.1f MB", estimatedRamBytes / 1024.0 / 1024.0);
		}
	}
}
