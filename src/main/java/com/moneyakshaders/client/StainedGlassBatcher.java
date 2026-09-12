package com.moneyakshaders.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Batch stained glass blocks by color for optimized rendering.
 * Groups glass by material to reduce texture switching and blend state changes.
 *
 * <p>Color-keyed batching dramatically reduces draw calls for glass-heavy scenes.
 * Worker threads pre-sort and group glass blocks; main thread renders optimized batches.
 */
public final class StainedGlassBatcher {
	private static volatile StainedGlassBatcher instance;

	private final ConcurrentHashMap<String, GlassBatch> glassBatches = new ConcurrentHashMap<>();

	private StainedGlassBatcher() {
	}

	public static StainedGlassBatcher getInstance() {
		if (instance == null) {
			synchronized (StainedGlassBatcher.class) {
				if (instance == null) {
					instance = new StainedGlassBatcher();
				}
			}
		}
		return instance;
	}

	/**
	 * Schedule async batching of stained glass blocks.
	 */
	public void queueGlassBatching(String chunkKey, List<GlassBlock> glassBlocks) {
		ChunkMeshExecutor.executeBackground(() -> batchGlassBlocks(chunkKey, glassBlocks));
	}

	private void batchGlassBlocks(String chunkKey, List<GlassBlock> glassBlocks) {
		Map<String, List<GlassBlock>> batchesByColor = new HashMap<>();

		// Group by color/texture
		for (GlassBlock block : glassBlocks) {
			String colorKey = block.color;
			batchesByColor.computeIfAbsent(colorKey, k -> new ArrayList<>()).add(block);
		}

		// Create optimized batches
		List<GlassBatch> batches = new ArrayList<>();
		for (Map.Entry<String, List<GlassBlock>> entry : batchesByColor.entrySet()) {
			GlassBatch batch = createBatch(entry.getKey(), entry.getValue());
			batches.add(batch);
		}

		// Batch by approximate distance to camera for sorting
		batches.sort((a, b) -> Float.compare(b.avgDistanceToCamera, a.avgDistanceToCamera));

		String batchKey = chunkKey;
		glassBatches.put(batchKey, batches.get(0)); // Cache first batch for quick access
	}

	private GlassBatch createBatch(String colorKey, List<GlassBlock> blocks) {
		int totalVertices = 0;
		int totalIndices = 0;
		float avgDistance = 0;

		for (GlassBlock block : blocks) {
			totalVertices += block.vertexCount;
			totalIndices += block.indexCount;
			avgDistance += block.distanceToCamera;
		}

		avgDistance = totalVertices > 0 ? avgDistance / blocks.size() : 0;

		return new GlassBatch(colorKey, blocks, totalVertices, totalIndices, avgDistance);
	}

	/**
	 * Get batched glass data (main thread safe, cached).
	 */
	public GlassBatch getBatch(String chunkKey) {
		return glassBatches.get(chunkKey);
	}

	/**
	 * Check if glass is batched (non-blocking).
	 */
	public boolean isBatched(String chunkKey) {
		return glassBatches.containsKey(chunkKey);
	}

	/**
	 * Clear cache to free memory.
	 */
	public void clearCache() {
		glassBatches.clear();
	}

	/**
	 * Optimized glass batch (immutable).
	 */
	public static final class GlassBatch {
		public final String colorKey;
		public final List<GlassBlock> blocks;
		public final int totalVertexCount;
		public final int totalIndexCount;
		public final float avgDistanceToCamera;

		public GlassBatch(String colorKey, List<GlassBlock> blocks, int totalVertexCount, int totalIndexCount,
				float avgDistanceToCamera) {
			this.colorKey = colorKey;
			this.blocks = blocks;
			this.totalVertexCount = totalVertexCount;
			this.totalIndexCount = totalIndexCount;
			this.avgDistanceToCamera = avgDistanceToCamera;
		}

		public boolean isOptimized() {
			// Optimized if we've reduced draw calls significantly
			return blocks.size() > 1 && totalVertexCount > 64;
		}

		public float getReductionFactor() {
			// How many draw calls we saved by batching
			return totalIndexCount > 0 ? (float) blocks.size() / (totalIndexCount / 6) : 1.0f;
		}
	}

	/**
	 * Stained glass block data.
	 */
	public static final class GlassBlock {
		public final String color;
		public final int x, y, z;
		public final int vertexCount;
		public final int indexCount;
		public final float distanceToCamera;

		public GlassBlock(String color, int x, int y, int z, int vertexCount, int indexCount,
				float distanceToCamera) {
			this.color = color;
			this.x = x;
			this.y = y;
			this.z = z;
			this.vertexCount = vertexCount;
			this.indexCount = indexCount;
			this.distanceToCamera = distanceToCamera;
		}
	}
}
