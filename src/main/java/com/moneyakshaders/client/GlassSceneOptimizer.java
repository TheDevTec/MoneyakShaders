package com.moneyakshaders.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;

/**
 * Master orchestrator for all glass-heavy scene optimizations.
 * Coordinates batching, sorting, LOD, occlusion, and shader optimization.
 *
 * <p>Designed for scenes with massive amounts of stained glass (e.g., lobby).
 * Reduces draw calls, fragment shader work, and texture switching simultaneously.
 */
public final class GlassSceneOptimizer {
	private static volatile GlassSceneOptimizer instance;

	private final StainedGlassBatcher glassBatcher;
	private final TransparencyOptimizer transparencyOptimizer;
	private final ColorLookupOptimizer colorLookupOptimizer;
	private final FragmentShaderOptimizer fragmentShaderOptimizer;
	private final GlassLODOptimizer glassLODOptimizer;
	private final OcclusionQueryCache occlusionCache;

	private GlassSceneOptimizer() {
		this.glassBatcher = StainedGlassBatcher.getInstance();
		this.transparencyOptimizer = TransparencyOptimizer.getInstance();
		this.colorLookupOptimizer = ColorLookupOptimizer.getInstance();
		this.fragmentShaderOptimizer = FragmentShaderOptimizer.getInstance();
		this.glassLODOptimizer = GlassLODOptimizer.getInstance();
		this.occlusionCache = OcclusionQueryCache.getInstance();
	}

	public static GlassSceneOptimizer getInstance() {
		if (instance == null) {
			synchronized (GlassSceneOptimizer.class) {
				if (instance == null) {
					instance = new GlassSceneOptimizer();
				}
			}
		}
		return instance;
	}

	/**
	 * Optimize a chunk with heavy glass content.
	 * All tasks execute in parallel on worker threads.
	 */
	public void optimizeGlassyChunk(ChunkPos chunkPos, ChunkGlassData glassData, Vec3d cameraPos) {
		String chunkKey = chunkPos.x + ":" + chunkPos.z;

		// 1. Batch glass by color (reduces draw calls)
		glassBatcher.queueGlassBatching(chunkKey, glassData.glassBlocks);

		// 2. Sort transparency by depth (fixes z-fighting)
		if (glassData.hasTransparencyData) {
			transparencyOptimizer.queueTransparencySorting(chunkKey, glassData.vertices, glassData.indices,
					(float) cameraPos.x, (float) cameraPos.y, (float) cameraPos.z);
		}

		// 3. Build color LUT (reduces texture unit switching)
		colorLookupOptimizer.queueLUTBuilding("lut_" + chunkKey, glassData.uniqueColors);

		// 4. Generate optimized glass shaders
		String glassShader = fragmentShaderOptimizer.generateGlassShader(true, true);
		fragmentShaderOptimizer.queueShaderOptimization("glass_" + chunkKey, glassShader);

		// 5. Compute LOD levels for distant glass (reduces vertex load)
		for (StainedGlassBatcher.GlassBlock block : glassData.glassBlocks) {
			String blockKey = chunkKey + "_" + block.x + "_" + block.y + "_" + block.z;
			glassLODOptimizer.queueLODComputation(blockKey, block.x, block.y, block.z, cameraPos);
		}

		// 6. Cache occlusion queries (skip hidden glass)
		for (StainedGlassBatcher.GlassBlock block : glassData.glassBlocks) {
			String blockKey = chunkKey + "_" + block.x + "_" + block.y + "_" + block.z;
			// Simplified: assume opaque neighbors if dense cluster
			boolean hasOpaqueNeighbors = glassData.glassBlocks.size() > 50;
			occlusionCache.queueOcclusionQuery(blockKey, block.x, block.y, block.z, hasOpaqueNeighbors);
		}
	}

	/**
	 * Get unified optimization statistics.
	 */
	public GlassOptimizationStats getOptimizationStats() {
		return new GlassOptimizationStats(occlusionCache.getStats());
	}

	/**
	 * Clear all glass optimizations (on world change).
	 */
	public void clearAllOptimizations() {
		glassBatcher.clearCache();
		transparencyOptimizer.clearCache();
		colorLookupOptimizer.clearCache();
		fragmentShaderOptimizer.clearCache();
		glassLODOptimizer.clearCache();
		occlusionCache.clearCache();
	}

	/**
	 * Glass scene optimization statistics.
	 */
	public static final class GlassOptimizationStats {
		public final OcclusionQueryCache.OcclusionStats occlusionStats;

		public GlassOptimizationStats(OcclusionQueryCache.OcclusionStats occlusionStats) {
			this.occlusionStats = occlusionStats;
		}

		public int getTotalOptimizationsApplied() {
			// Count active optimizations
			return 6; // color batching, transparency sort, LUT, shader, LOD, occlusion
		}

		public boolean isHealthy() {
			// System is healthy if culling ratio is reasonable (>10% but <80%)
			return occlusionStats.cullRatio > 0.1f && occlusionStats.cullRatio < 0.8f;
		}

		public String getOptimizationSummary() {
			return String.format("Glass Optimizations: Culled %d/%d blocks (%.1f%% efficiency)", occlusionStats.culledQueries,
					occlusionStats.totalQueries, occlusionStats.getEfficiency());
		}
	}

	/**
	 * Glass chunk data for optimization (immutable).
	 */
	public static final class ChunkGlassData {
		public final List<StainedGlassBatcher.GlassBlock> glassBlocks;
		public final List<ColorLookupOptimizer.GlassColor> uniqueColors;
		public final float[] vertices;
		public final int[] indices;
		public final boolean hasTransparencyData;

		public ChunkGlassData(List<StainedGlassBatcher.GlassBlock> glassBlocks,
				List<ColorLookupOptimizer.GlassColor> uniqueColors, float[] vertices, int[] indices,
				boolean hasTransparencyData) {
			this.glassBlocks = glassBlocks;
			this.uniqueColors = uniqueColors;
			this.vertices = vertices;
			this.indices = indices;
			this.hasTransparencyData = hasTransparencyData;
		}

		public boolean isGlassHeavy() {
			return glassBlocks.size() > 32; // More than 32 glass blocks = heavy
		}

		public int getTotalVertices() {
			return glassBlocks.stream().mapToInt(b -> b.vertexCount).sum();
		}

		public int getColorDiversity() {
			return uniqueColors.size();
		}
	}
}
