package com.moneyakshaders.client;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Material property cache - pre-computes rendering properties for all block states.
 * Trades RAM (one entry per block state) for CPU (no runtime property lookups).
 *
 * <p>For glass: caches specularity, reflectivity, and transparency for all 16 colors.
 */
public final class MaterialPropertyCache {
	private static volatile MaterialPropertyCache instance;

	private final ConcurrentHashMap<Integer, MaterialProperty> materialCache = new ConcurrentHashMap<>();
	private final AtomicInteger totalBlockStates = new AtomicInteger(0);

	private MaterialPropertyCache() {
	}

	public static MaterialPropertyCache getInstance() {
		if (instance == null) {
			synchronized (MaterialPropertyCache.class) {
				if (instance == null) {
					instance = new MaterialPropertyCache();
				}
			}
		}
		return instance;
	}

	/**
	 * Cache material properties for a block state (worker thread).
	 */
	public void queueMaterialComputation(int blockStateId, String blockName, int colorRGB) {
		ChunkMeshExecutor.executeBackground(() -> computeMaterial(blockStateId, blockName, colorRGB));
	}

	private void computeMaterial(int blockStateId, String blockName, int colorRGB) {
		MaterialProperty props = new MaterialProperty();

		// Determine properties based on block type
		if (blockName.contains("glass") || blockName.contains("Glass")) {
			props.isTransparent = true;
			props.transparency = 0.7f; // Glass is 70% transparent
			props.specularity = 0.9f; // Very glossy
			props.reflectivity = 0.5f; // Reflects light
			props.roughness = 0.1f; // Smooth
		} else if (blockName.contains("water") || blockName.contains("Water")) {
			props.isTransparent = true;
			props.transparency = 0.8f;
			props.specularity = 0.95f;
			props.reflectivity = 0.6f;
			props.roughness = 0.2f;
		} else {
			// Opaque block
			props.isTransparent = false;
			props.transparency = 0.0f;
			props.specularity = 0.3f; // Default roughness
			props.reflectivity = 0.2f;
			props.roughness = 0.7f;
		}

		// Color properties
		props.colorRGB = colorRGB;
		props.brightness = (float) (((colorRGB >> 16) & 0xFF) + ((colorRGB >> 8) & 0xFF) + (colorRGB & 0xFF)) / 3.0f / 255.0f;

		materialCache.put(blockStateId, props);
		totalBlockStates.incrementAndGet();
	}

	/**
	 * Get cached material properties (main thread safe).
	 */
	public MaterialProperty getMaterial(int blockStateId) {
		MaterialProperty props = materialCache.get(blockStateId);
		if (props == null) {
			// Return default material if not cached
			props = new MaterialProperty();
		}
		return props;
	}

	/**
	 * Get statistics.
	 */
	public MaterialStats getStats() {
		int total = totalBlockStates.get();
		long ramUsedBytes = total * 50; // ~50 bytes per material entry

		return new MaterialStats(total, ramUsedBytes);
	}

	/**
	 * Clear cache.
	 */
	public void clearCache() {
		materialCache.clear();
		totalBlockStates.set(0);
	}

	/**
	 * Material property data.
	 */
	public static final class MaterialProperty {
		public boolean isTransparent;
		public float transparency; // 0.0 = opaque, 1.0 = fully transparent
		public float specularity; // 0.0 = matte, 1.0 = mirror
		public float reflectivity; // How much light is reflected
		public float roughness; // 0.0 = smooth, 1.0 = rough
		public int colorRGB; // Packed RGB
		public float brightness; // Normalized brightness (0.0-1.0)

		public MaterialProperty() {
			this.isTransparent = false;
			this.transparency = 0.0f;
			this.specularity = 0.5f;
			this.reflectivity = 0.3f;
			this.roughness = 0.5f;
			this.colorRGB = 0xFFFFFF;
			this.brightness = 1.0f;
		}

		public boolean isHighlyReflective() {
			return specularity > 0.7f && reflectivity > 0.4f;
		}

		public boolean isOpaque() {
			return !isTransparent && transparency == 0.0f;
		}
	}

	/**
	 * Material statistics.
	 */
	public static final class MaterialStats {
		public final int cachedBlockStates;
		public final long estimatedRamBytes;

		public MaterialStats(int cachedBlockStates, long estimatedRamBytes) {
			this.cachedBlockStates = cachedBlockStates;
			this.estimatedRamBytes = estimatedRamBytes;
		}

		public String getRamUsedMB() {
			return String.format("%.1f MB", estimatedRamBytes / 1024.0 / 1024.0);
		}
	}
}
