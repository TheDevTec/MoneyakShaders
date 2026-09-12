package com.moneyakshaders.client;

/**
 * Master orchestrator for all RAM-heavy optimizations.
 * Coordinates mesh caching, visibility graphs, material properties, distances, and block states.
 *
 * <p>Total estimated RAM usage: 500 MB - 1 GB for glass-heavy scenes.
 */
public final class RAMHeavyOptimizationCoordinator {
	private static volatile RAMHeavyOptimizationCoordinator instance;

	private final MeshBufferCache meshBufferCache;
	private final VisibilityGraphCache visibilityGraphCache;
	private final MaterialPropertyCache materialPropertyCache;
	private final ChunkDistancePrecomputer distancePrecomputer;
	private final BlockStateLookupTable blockStateLookupTable;

	private RAMHeavyOptimizationCoordinator() {
		this.meshBufferCache = MeshBufferCache.getInstance();
		this.visibilityGraphCache = VisibilityGraphCache.getInstance();
		this.materialPropertyCache = MaterialPropertyCache.getInstance();
		this.distancePrecomputer = ChunkDistancePrecomputer.getInstance();
		this.blockStateLookupTable = BlockStateLookupTable.getInstance();
	}

	public static RAMHeavyOptimizationCoordinator getInstance() {
		if (instance == null) {
			synchronized (RAMHeavyOptimizationCoordinator.class) {
				if (instance == null) {
					instance = new RAMHeavyOptimizationCoordinator();
				}
			}
		}
		return instance;
	}

	/**
	 * Initialize all caches for a chunk.
	 */
	public void initializeChunkCaches(int chunkX, int chunkZ, int viewDistance, float camX, float camY, float camZ) {
		String chunkKey = chunkX + ":" + chunkZ;

		// Pre-compute visibility
		visibilityGraphCache.queueVisibilityComputation(chunkKey, viewDistance);

		// Pre-compute distances
		distancePrecomputer.queueDistanceComputation(chunkKey, chunkX, chunkZ, camX, camY, camZ);
	}

	/**
	 * Cache mesh buffer for reuse.
	 */
	public void cacheMeshBuffer(String meshKey, float[] vertices, int[] indices) {
		long estimatedSize = (vertices.length * 4L) + (indices.length * 4L); // floats + ints
		meshBufferCache.cacheMesh(meshKey, vertices, indices, estimatedSize);
	}

	/**
	 * Get cached mesh if available.
	 */
	public MeshBufferCache.CachedMeshBuffer getCachedMesh(String meshKey) {
		return meshBufferCache.getCachedMesh(meshKey);
	}

	/**
	 * Pre-compute material properties.
	 */
	public void computeMaterialProperties(int blockStateId, String blockName, int colorRGB) {
		materialPropertyCache.queueMaterialComputation(blockStateId, blockName, colorRGB);
	}

	/**
	 * Get material properties (O(1)).
	 */
	public MaterialPropertyCache.MaterialProperty getMaterial(int blockStateId) {
		return materialPropertyCache.getMaterial(blockStateId);
	}

	/**
	 * Pre-compute block states.
	 */
	public void computeBlockState(int blockStateId, String blockName, boolean hasCollision, boolean hasTransparency,
			boolean hasRenderLayer) {
		blockStateLookupTable.queueBlockStateComputation(blockStateId, blockName, hasCollision, hasTransparency,
				hasRenderLayer);
	}

	/**
	 * Get block state properties (O(1)).
	 */
	public BlockStateLookupTable.BlockStateProperties getBlockState(int blockStateId) {
		return blockStateLookupTable.getBlockState(blockStateId);
	}

	/**
	 * Get unified statistics.
	 */
	public UnifiedOptimizationStats getStats() {
		return new UnifiedOptimizationStats(
				meshBufferCache.getStats(),
				visibilityGraphCache.getStats(),
				materialPropertyCache.getStats(),
				distancePrecomputer.getStats(),
				blockStateLookupTable.getStats());
	}

	/**
	 * Clear all caches.
	 */
	public void clearAllCaches() {
		meshBufferCache.clearCache();
		visibilityGraphCache.clearCache();
		materialPropertyCache.clearCache();
		distancePrecomputer.clearCache();
		blockStateLookupTable.clearCache();
	}

	/**
	 * Unified statistics.
	 */
	public static final class UnifiedOptimizationStats {
		public final MeshBufferCache.CacheStats meshStats;
		public final VisibilityGraphCache.VisibilityStats visibilityStats;
		public final MaterialPropertyCache.MaterialStats materialStats;
		public final ChunkDistancePrecomputer.DistanceStats distanceStats;
		public final BlockStateLookupTable.BlockStateStats blockStateStats;

		public UnifiedOptimizationStats(
				MeshBufferCache.CacheStats meshStats,
				VisibilityGraphCache.VisibilityStats visibilityStats,
				MaterialPropertyCache.MaterialStats materialStats,
				ChunkDistancePrecomputer.DistanceStats distanceStats,
				BlockStateLookupTable.BlockStateStats blockStateStats) {
			this.meshStats = meshStats;
			this.visibilityStats = visibilityStats;
			this.materialStats = materialStats;
			this.distanceStats = distanceStats;
			this.blockStateStats = blockStateStats;
		}

		public String getTotalRamUsedMB() {
			long total = meshStats.ramUsedBytes + visibilityStats.estimatedRamBytes +
					materialStats.estimatedRamBytes + distanceStats.estimatedRamBytes +
					blockStateStats.estimatedRamBytes;
			return String.format("%.1f MB", total / 1024.0 / 1024.0);
		}

		public String getSummary() {
			return String.format(
					"RAM Optimization: %.1f MB total | Meshes: %d (%s) | Visibility: %d chunks (%s) | Materials: %d states (%s) | Distances: %d chunks (%s) | BlockStates: %d (%s)",
					Double.parseDouble(getTotalRamUsedMB().split(" ")[0]),
					meshStats.cachedMeshes, meshStats.getRamUsedMB(),
					visibilityStats.cachedChunks, visibilityStats.getRamUsedMB(),
					materialStats.cachedBlockStates, materialStats.getRamUsedMB(),
					distanceStats.cachedChunks, distanceStats.getRamUsedMB(),
					blockStateStats.totalBlockStates, blockStateStats.getRamUsedMB());
		}
	}
}
