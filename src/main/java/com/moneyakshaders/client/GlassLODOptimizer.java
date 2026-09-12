package com.moneyakshaders.client;

import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.util.math.Vec3d;

/**
 * Distance-based Level of Detail (LOD) for transparent materials.
 * Reduces mesh complexity for distant glass to improve performance.
 *
 * <p>Far glass renders at lower tessellation; near glass at full detail.
 * Worker threads compute LOD levels based on camera distance.
 */
public final class GlassLODOptimizer {
	private static volatile GlassLODOptimizer instance;

	private final ConcurrentHashMap<String, LODLevel> lodCache = new ConcurrentHashMap<>();

	private static final float[] LOD_DISTANCES = { 16.0f, 32.0f, 64.0f, 128.0f };
	private static final int[] LOD_TESSELLATION = { 0, 1, 2, 3 }; // 0 = none, 3 = max

	private GlassLODOptimizer() {
	}

	public static GlassLODOptimizer getInstance() {
		if (instance == null) {
			synchronized (GlassLODOptimizer.class) {
				if (instance == null) {
					instance = new GlassLODOptimizer();
				}
			}
		}
		return instance;
	}

	/**
	 * Schedule async LOD computation for glass blocks.
	 */
	public void queueLODComputation(String meshId, int x, int y, int z, Vec3d cameraPos) {
		ChunkMeshExecutor.getOrCreate()
				.execute(() -> computeLOD(meshId, x, y, z, cameraPos.x, cameraPos.y, cameraPos.z));
	}

	private void computeLOD(String meshId, int x, int y, int z, double camX, double camY, double camZ) {
		// Compute distance to block center
		double dx = x + 0.5 - camX;
		double dy = y + 0.5 - camY;
		double dz = z + 0.5 - camZ;
		float distance = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);

		// Determine LOD level
		int lodLevel = 0;
		for (int i = 0; i < LOD_DISTANCES.length; i++) {
			if (distance >= LOD_DISTANCES[i]) {
				lodLevel = i;
			}
		}

		int tessellation = LOD_TESSELLATION[Math.min(lodLevel, LOD_TESSELLATION.length - 1)];
		int vertexReduction = (int) (100.0f * (1.0f - tessellation / 3.0f)); // % reduction

		lodCache.put(meshId, new LODLevel(distance, lodLevel, tessellation, vertexReduction));
	}

	/**
	 * Get LOD level (main thread safe, cached).
	 */
	public LODLevel getLODLevel(String meshId) {
		return lodCache.get(meshId);
	}

	/**
	 * Check if LOD is computed (non-blocking).
	 */
	public boolean isLODComputed(String meshId) {
		return lodCache.containsKey(meshId);
	}

	/**
	 * Get tessellation factor for LOD level (main thread safe).
	 */
	public int getTessellationFactor(String meshId) {
		LODLevel lod = getLODLevel(meshId);
		if (lod == null) {
			return 3; // Full detail by default
		}
		return lod.tessellationLevel;
	}

	/**
	 * Get vertex reduction percentage for LOD level.
	 */
	public int getVertexReductionPercent(String meshId) {
		LODLevel lod = getLODLevel(meshId);
		if (lod == null) {
			return 0;
		}
		return lod.vertexReductionPercent;
	}

	/**
	 * Clear cache to free memory.
	 */
	public void clearCache() {
		lodCache.clear();
	}

	/**
	 * LOD level result (immutable).
	 */
	public static final class LODLevel {
		public final float distanceToCamera;
		public final int level; // 0-3
		public final int tessellationLevel; // 0 = minimal, 3 = maximum
		public final int vertexReductionPercent;

		public LODLevel(float distanceToCamera, int level, int tessellationLevel, int vertexReductionPercent) {
			this.distanceToCamera = distanceToCamera;
			this.level = level;
			this.tessellationLevel = tessellationLevel;
			this.vertexReductionPercent = vertexReductionPercent;
		}

		public boolean isFullDetail() {
			return tessellationLevel == 3;
		}

		public boolean isMinimalDetail() {
			return tessellationLevel == 0;
		}

		public float getDetailRatio() {
			return (float) tessellationLevel / 3.0f;
		}
	}
}
