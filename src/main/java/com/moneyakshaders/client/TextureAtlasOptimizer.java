package com.moneyakshaders.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.texture.TextureManager;
import net.minecraft.util.Identifier;

/**
 * Pre-compute texture atlas regions and metadata in worker threads.
 * Reduces main thread overhead during render pass initialization.
 *
 * <p>Manages texture atlas packing, coordinate calculation, and region metadata
 * generation off the main thread. Main thread only receives pre-computed results.
 */
public final class TextureAtlasOptimizer {
	private static volatile TextureAtlasOptimizer instance;

	private final Map<String, AtlasRegion> atlasCache = new ConcurrentHashMap<>();
	private final Map<String, List<AtlasRegion>> atlasGroups = new ConcurrentHashMap<>();
	private final TextureManager textureManager;

	private TextureAtlasOptimizer(TextureManager textureManager) {
		this.textureManager = textureManager;
	}

	public static TextureAtlasOptimizer getInstance(TextureManager textureManager) {
		if (instance == null) {
			synchronized (TextureAtlasOptimizer.class) {
				if (instance == null) {
					instance = new TextureAtlasOptimizer(textureManager);
				}
			}
		}
		return instance;
	}

	/**
	 * Schedule async texture atlas region computation.
	 * Returns immediately; result cached for main thread access.
	 */
	public void queueAtlasRegionComputation(String atlasName, List<Identifier> textureIds) {
		if (TaskThrottler.allowAnimatedTask()) {
			ChunkMeshExecutor.executeBackground(() -> computeAtlasRegions(atlasName, textureIds));
		}
	}

	private void computeAtlasRegions(String atlasName, List<Identifier> textureIds) {
		List<AtlasRegion> regions = new ArrayList<>();
		int atlasX = 0;
		int atlasY = 0;
		final int maxWidth = 4096;
		final int textureSize = 16;

		for (Identifier textureId : textureIds) {
			if (atlasX + textureSize > maxWidth) {
				atlasX = 0;
				atlasY += textureSize;
			}

			AtlasRegion region = new AtlasRegion(textureId, atlasX, atlasY, textureSize, textureSize);
			regions.add(region);
			atlasCache.put(textureId.toString(), region);

			atlasX += textureSize;
		}

		atlasGroups.put(atlasName, regions);
	}

	/**
	 * Get pre-computed atlas region for texture (main thread safe, cached).
	 */
	public AtlasRegion getAtlasRegion(Identifier textureId) {
		return atlasCache.getOrDefault(textureId.toString(), new AtlasRegion(textureId, 0, 0, 16, 16));
	}

	/**
	 * Get all regions for an atlas (main thread safe, cached).
	 */
	public List<AtlasRegion> getAtlasGroup(String atlasName) {
		return atlasGroups.getOrDefault(atlasName, new ArrayList<>());
	}

	/**
	 * Pre-compute all atlas metadata in parallel.
	 * Call once during client startup to warm cache.
	 */
	public void precomputeAllAtlases(Map<String, List<Identifier>> atlases) {
		atlases.forEach(this::queueAtlasRegionComputation);
	}

	/**
	 * Clear cache to free memory.
	 */
	public void clearCache() {
		atlasCache.clear();
		atlasGroups.clear();
	}

	/**
	 * Texture atlas region metadata (immutable).
	 */
	public static final class AtlasRegion {
		public final Identifier textureId;
		public final int x;
		public final int y;
		public final int width;
		public final int height;

		public AtlasRegion(Identifier textureId, int x, int y, int width, int height) {
			this.textureId = textureId;
			this.x = x;
			this.y = y;
			this.width = width;
			this.height = height;
		}

		public float getMinU() {
			return (float) x / 4096.0f;
		}

		public float getMaxU() {
			return (float) (x + width) / 4096.0f;
		}

		public float getMinV() {
			return (float) y / 4096.0f;
		}

		public float getMaxV() {
			return (float) (y + height) / 4096.0f;
		}
	}
}
