package com.moneyakshaders.client;

import java.util.concurrent.ConcurrentHashMap;

import com.moneyakshaders.MoneyakShadersConfig;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;

/**
 * Optimizes away unused scene render scheduling.
 *
 * <p>Skips chunk render scheduling for chunks which are not in the current camera-visible
 * set and are not adjacent to any currently visible chunk. This is especially important
 * for underground cave complexes and remote unused areas that the player cannot see.
 */
public final class UnusedSceneOptimizer {
	private static volatile UnusedSceneOptimizer instance;

	private final ConcurrentHashMap<String, Boolean> skipCache = new ConcurrentHashMap<>();

	private UnusedSceneOptimizer() {
	}

	public static UnusedSceneOptimizer getInstance() {
		if (instance == null) {
			synchronized (UnusedSceneOptimizer.class) {
				if (instance == null) {
					instance = new UnusedSceneOptimizer();
				}
			}
		}
		return instance;
	}

	/**
	 * Determine whether a chunk render request can be skipped because it belongs
	 * to an unused scene that the player cannot currently see.
	 */
	public boolean shouldSkipChunkRender(int chunkX, int chunkY, int chunkZ, Vec3d cameraPos, boolean important) {
		MoneyakShadersConfig config = MoneyakShadersConfig.get();
		if (!config.enableUnusedSceneCulling || important) {
			return false;
		}

		if (cameraPos == null) {
			return false;
		}

		String cacheKey = buildKey(chunkX, chunkY, chunkZ, cameraPos);
		Boolean cached = skipCache.get(cacheKey);
		if (cached != null) {
			return cached;
		}

		boolean shouldSkip = computeSkip(chunkX, chunkY, chunkZ, cameraPos);
		skipCache.put(cacheKey, shouldSkip);
		return shouldSkip;
	}

	private boolean computeSkip(int chunkX, int chunkY, int chunkZ, Vec3d cameraPos) {
		CullingPrecomputer culling = CullingPrecomputer.getInstance();
		CullingPrecomputer.CullingResult result = culling.getCullingResult(cameraPos);

		// If we have a cached visibility result, use it to skip chunks outside the visible set.
		if (result != null) {
			ChunkPos requested = new ChunkPos(new BlockPos(chunkX * 16, 0, chunkZ * 16));
			if (!isChunkVisible(requested, result)) {
				// Keep a small border around visible chunks so nearby indirect areas are still built.
				if (!isAdjacentToVisible(requested, result)) {
					return true;
				}
			}
		}

		// If the chunk is far below or above the camera and not explicitly visible, skip it.
		int cameraChunkY = (int) Math.floor(cameraPos.y / 16.0);
		if (Math.abs(chunkY - cameraChunkY) > 3) {
			return true;
		}

		return false;
	}

	private boolean isChunkVisible(ChunkPos candidate, CullingPrecomputer.CullingResult result) {
		for (ChunkPos visible : result.visibleChunks) {
			if (visible.x == candidate.x && visible.z == candidate.z) {
				return true;
			}
		}
		return false;
	}

	private boolean isAdjacentToVisible(ChunkPos candidate, CullingPrecomputer.CullingResult result) {
		for (ChunkPos visible : result.visibleChunks) {
			if (Math.abs(visible.x - candidate.x) <= 1 && Math.abs(visible.z - candidate.z) <= 1) {
				return true;
			}
		}
		return false;
	}

	private String buildKey(int chunkX, int chunkY, int chunkZ, Vec3d cameraPos) {
		int cameraChunkX = (int) Math.floor(cameraPos.x / 16.0);
		int cameraChunkZ = (int) Math.floor(cameraPos.z / 16.0);
		int cameraChunkY = (int) Math.floor(cameraPos.y / 16.0);
		return chunkX + ":" + chunkY + ":" + chunkZ + "@" + cameraChunkX + "," + cameraChunkY + "," + cameraChunkZ;
	}

	/**
	 * Clear cached results when the camera or world changes.
	 */
	public void clearCache() {
		skipCache.clear();
	}
}
