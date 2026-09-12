package com.moneyakshaders.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.moneyakshaders.MoneyakShadersConfig;

import net.minecraft.client.texture.TextureManager;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;

/**
 * Coordinate all multi-threaded optimizations into a unified framework.
 * Manages parallel preprocessing and caching across all optimization components.
 *
 * <p>Prioritizes work based on camera distance, manages thread pool load,
 * and coordinates cache invalidation across all optimizers.
 */
public final class MultiThreadedOptimizationCoordinator {
	private static volatile MultiThreadedOptimizationCoordinator instance;

	private final TextureAtlasOptimizer textureAtlas;
	private final NormalMapPrecomputer normalMaps;
	private final LightingPrecomputer lighting;
	private final VertexDataStreamer vertexStreamer;
	private final MeshOptimizer meshOptimizer;
	private final CullingPrecomputer culling;
	private final ShaderCompilationCache shaderCache;
	private final BlockStateCache blockStateCache;

	private final Map<String, Long> chunkProcessingTimes = new HashMap<>();

	private MultiThreadedOptimizationCoordinator(TextureManager textureManager) {
		this.textureAtlas = TextureAtlasOptimizer.getInstance(textureManager);
		this.normalMaps = NormalMapPrecomputer.getInstance();
		this.lighting = LightingPrecomputer.getInstance();
		this.vertexStreamer = VertexDataStreamer.getInstance();
		this.meshOptimizer = MeshOptimizer.getInstance();
		this.culling = CullingPrecomputer.getInstance();
		this.shaderCache = ShaderCompilationCache.getInstance();
		this.blockStateCache = BlockStateCache.getInstance();
	}

	public static MultiThreadedOptimizationCoordinator getInstance(TextureManager textureManager) {
		if (instance == null) {
			synchronized (MultiThreadedOptimizationCoordinator.class) {
				if (instance == null) {
					instance = new MultiThreadedOptimizationCoordinator(textureManager);
				}
			}
		}
		return instance;
	}

	/**
	 * Queue all preprocessing tasks for a chunk (parallel execution).
	 */
	public void preprocessChunk(ChunkPos chunkPos, ChunkData chunkData) {
		long startTime = System.currentTimeMillis();

		// All tasks execute in parallel on worker threads
		blockStateCache.queueBlockStateComputation(chunkPos, chunkData.blockIds);
		vertexStreamer.queueVertexDataPreparation(chunkPos, chunkData.vertices, chunkData.indices);
		meshOptimizer.queueMeshOptimization(chunkPos, chunkData.vertices, chunkData.indices);
		normalMaps.queueNormalMapComputation(chunkPos.toString(), chunkData.normalHeightmap);
		lighting.queueLightingComputation(chunkPos, chunkData.lightmapData);

		chunkProcessingTimes.put(chunkPos.toString(), startTime);
	}

	/**
	 * Perform view-dependent culling for camera position (parallel).
	 */
	public void cullVisibleChunks(Vec3d cameraPos, CullingPrecomputer.FrustumPlanes frustum, List<ChunkPos> chunks) {
		culling.queueCullingPass(cameraPos, frustum, chunks);
	}

	/**
	 * Pre-compile shader variants asynchronously.
	 */
	public void precompileShaderVariants(String baseId, List<ShaderCompilationCache.ShaderVariant> variants) {
		shaderCache.precompileVariants(baseId, variants);
	}

	/**
	 * Get unified optimization stats for debugging/profiling.
	 */
	public OptimizationStats getOptimizationStats() {
		return new OptimizationStats(ChunkMeshExecutor.getQueueSize(),
				vertexStreamer.getPendingOperations(), shaderCache.getStats());
	}

	/**
	 * Clear all caches (call on world change or memory pressure).
	 */
	public void clearAllCaches() {
		textureAtlas.clearCache();
		normalMaps.clearCache();
		lighting.clearCache();
		vertexStreamer.clearCache();
		meshOptimizer.clearCache();
		culling.clearCache();
		shaderCache.clearCache();
		blockStateCache.clearCache();
		chunkProcessingTimes.clear();
	}

	/**
	 * Get processing time for a chunk (for profiling).
	 */
	public long getChunkProcessingTime(ChunkPos chunkPos) {
		Long start = chunkProcessingTimes.get(chunkPos.toString());
		if (start == null) {
			return -1;
		}
		return System.currentTimeMillis() - start;
	}

	/**
	 * Check overall preprocessing health.
	 */
	public boolean isHealthy() {
		// Consider unhealthy if queue is backing up
		int queueLimit = MoneyakShadersConfig.get().chunkBuilderQueueLimit;
		return ChunkMeshExecutor.getQueueSize() < queueLimit / 2;
	}

	/**
	 * Unified optimization statistics.
	 */
	public static final class OptimizationStats {
		public final int meshQueueSize;
		public final int pendingVertexOps;
		public final ShaderCompilationCache.CompilationStats shaderStats;

		public OptimizationStats(int meshQueueSize, int pendingVertexOps, ShaderCompilationCache.CompilationStats shaderStats) {
			this.meshQueueSize = meshQueueSize;
			this.pendingVertexOps = pendingVertexOps;
			this.shaderStats = shaderStats;
		}

		public int getTotalPendingOps() {
			return meshQueueSize + pendingVertexOps + shaderStats.pendingCount;
		}

		public boolean isOverloaded() {
			return getTotalPendingOps() > 512;
		}
	}

	/**
	 * Chunk data for preprocessing (immutable).
	 */
	public static final class ChunkData {
		public final float[] vertices;
		public final int[] indices;
		public final byte[] blockIds;
		public final float[][] normalHeightmap;
		public final byte[][] lightmapData;

		public ChunkData(float[] vertices, int[] indices, byte[] blockIds, float[][] normalHeightmap, byte[][] lightmapData) {
			this.vertices = vertices;
			this.indices = indices;
			this.blockIds = blockIds;
			this.normalHeightmap = normalHeightmap;
			this.lightmapData = lightmapData;
		}
	}
}
