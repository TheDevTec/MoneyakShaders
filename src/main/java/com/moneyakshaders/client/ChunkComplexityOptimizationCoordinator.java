package com.moneyakshaders.client;

/**
 * Master coordinator for diverse-build optimizations.
 * Handles chunk complexity: block palettes, light data, shadow rays, holes, block variations.
 *
 * <p>Targeted at buildings with:
 * - Large block palette diversity (100+ different block types per chunk)
 * - Complex hole patterns (needs shadow ray optimization)
 * - Varied lighting conditions
 * - Expensive shader ray marching
 */
public final class ChunkComplexityOptimizationCoordinator {
	private static volatile ChunkComplexityOptimizationCoordinator instance;

	private final BlockPaletteOptimizer paletteOptimizer;
	private final LightDataOptimizer lightOptimizer;
	private final ShadowRayOptimizer shadowRayOptimizer;
	private final HoleDetectionOptimizer holeDetector;
	private final BlockVariationCache variationCache;

	private ChunkComplexityOptimizationCoordinator() {
		this.paletteOptimizer = BlockPaletteOptimizer.getInstance();
		this.lightOptimizer = LightDataOptimizer.getInstance();
		this.shadowRayOptimizer = ShadowRayOptimizer.getInstance();
		this.holeDetector = HoleDetectionOptimizer.getInstance();
		this.variationCache = BlockVariationCache.getInstance();
	}

	public static ChunkComplexityOptimizationCoordinator getInstance() {
		if (instance == null) {
			synchronized (ChunkComplexityOptimizationCoordinator.class) {
				if (instance == null) {
					instance = new ChunkComplexityOptimizationCoordinator();
				}
			}
		}
		return instance;
	}

	/**
	 * Optimize complex chunk with diverse builds.
	 */
	public void optimizeDiverseChunk(String chunkKey, int chunkX, int chunkZ, int[] blockStateIds, byte[] blockLightData,
			byte[] skyLightData, boolean[][][] solidMap) {

		// 1. Cache block palette (deduplication)
		paletteOptimizer.queuePaletteComputation(chunkKey, blockStateIds);

		// 2. Pre-compute light data
		lightOptimizer.queueLightComputation(chunkKey, blockLightData, skyLightData);

		// 3. Pre-compute shadow rays (for holes)
		shadowRayOptimizer.queueShadowRayComputation(chunkKey, chunkX, chunkZ);

		// 4. Detect hole patterns
		holeDetector.queueHoleDetection(chunkKey, chunkX, chunkZ, solidMap);

		// 5. Cache block variations
		for (int blockStateId : blockStateIds) {
			String blockName = getBlockNameForState(blockStateId);
			variationCache.queueBlockVariationComputation(blockStateId, blockName, new String[0]);
		}
	}

	private String getBlockNameForState(int blockStateId) {
		// Map block state ID to block name
		// Simplified: would use registry in real implementation
		return "block_" + blockStateId;
	}

	/**
	 * Get integrated complexity statistics.
	 */
	public ComplexityOptStats getStats() {
		return new ComplexityOptStats(
				paletteOptimizer.getStats(),
				lightOptimizer.getStats(),
				shadowRayOptimizer.getStats(),
				holeDetector.getStats(),
				variationCache.getStats());
	}

	/**
	 * Clear all optimizations.
	 */
	public void clearAllOptimizations() {
		paletteOptimizer.clearCache();
		lightOptimizer.clearCache();
		shadowRayOptimizer.clearCache();
		holeDetector.clearCache();
		variationCache.clearCache();
	}

	/**
	 * Complex build optimization statistics.
	 */
	public static final class ComplexityOptStats {
		public final BlockPaletteOptimizer.PaletteStats paletteStats;
		public final LightDataOptimizer.LightStats lightStats;
		public final ShadowRayOptimizer.ShadowRayStats shadowStats;
		public final HoleDetectionOptimizer.HoleStats holeStats;
		public final BlockVariationCache.BlockVariationStats variationStats;

		public ComplexityOptStats(BlockPaletteOptimizer.PaletteStats paletteStats,
				LightDataOptimizer.LightStats lightStats, ShadowRayOptimizer.ShadowRayStats shadowStats,
				HoleDetectionOptimizer.HoleStats holeStats, BlockVariationCache.BlockVariationStats variationStats) {
			this.paletteStats = paletteStats;
			this.lightStats = lightStats;
			this.shadowStats = shadowStats;
			this.holeStats = holeStats;
			this.variationStats = variationStats;
		}

		public String getTotalRamUsedMB() {
			long total = paletteStats.estimatedRamBytes + lightStats.estimatedRamBytes
					+ shadowStats.estimatedRamBytes + holeStats.estimatedRamBytes + variationStats.estimatedRamBytes;
			return String.format("%.1f MB", total / 1024.0 / 1024.0);
		}

		public String getOptimizationSummary() {
			return String.format(
					"Complex Build Optimization:\n"
							+ "  Palettes: %d unique (%.1f%% dedup) | Light: %d chunks cached | Shadow: %d rays cached\n"
							+ "  Holes: %d chunks with %d holes | Block Variations: %d states cached\n"
							+ "  Total RAM: %s",
					paletteStats.uniquePalettes, paletteStats.getDeduplicationRatio() * 100,
					lightStats.cachedChunks, shadowStats.totalRaysComputed, holeStats.chunksWithHoles,
					holeStats.totalHolesDetected, variationStats.totalBlockStates, getTotalRamUsedMB());
		}

		public ComplexityLevel getComplexityLevel() {
			int holeScore = holeStats.totalHolesDetected > 50 ? 3 : holeStats.totalHolesDetected > 10 ? 2 : 1;
			int paletteScore = variationStats.totalBlockStates > 500 ? 3 : variationStats.totalBlockStates > 100 ? 2 : 1;
			int lightScore = lightStats.cachedChunks > 100 ? 2 : 1;

			int totalScore = holeScore + paletteScore + lightScore;
			if (totalScore >= 7)
				return ComplexityLevel.VERY_HIGH;
			if (totalScore >= 5)
				return ComplexityLevel.HIGH;
			if (totalScore >= 3)
				return ComplexityLevel.MEDIUM;
			return ComplexityLevel.LOW;
		}
	}

	/**
	 * Complexity level classification.
	 */
	public enum ComplexityLevel {
		LOW, MEDIUM, HIGH, VERY_HIGH
	}
}
