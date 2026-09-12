package com.moneyakshaders.client;

import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

/**
 * Pre-compute lighting and ambient occlusion in worker threads.
 * Offloads expensive light propagation calculations from main thread.
 *
 * <p>Caches block-level and vertex-level lighting data computed asynchronously.
 * Main thread uses pre-computed lighting without waiting.
 */
public final class LightingPrecomputer {
	private static volatile LightingPrecomputer instance;

	private final ConcurrentHashMap<String, LightingData> lightingCache = new ConcurrentHashMap<>();

	private LightingPrecomputer() {
	}

	public static LightingPrecomputer getInstance() {
		if (instance == null) {
			synchronized (LightingPrecomputer.class) {
				if (instance == null) {
					instance = new LightingPrecomputer();
				}
			}
		}
		return instance;
	}

	/**
	 * Schedule async lighting computation for chunk. Delegate to the
	 * `LightDataOptimizer` which uses the async lighting engine and
	 * cross-chunk propagation.
	 */
	public void queueLightingComputation(ChunkPos chunkPos, byte[][] lightmapData) {
		String key = chunkPos.x + ":" + chunkPos.z;
		// Convert packed per-column lightmap into full block/sky arrays (best-effort)
		int chunkSize = 16;
		int total = chunkSize * chunkSize * 256;
		byte[] blockLight = new byte[total];
		byte[] skyLight = new byte[total];

		for (int x = 0; x < chunkSize; x++) {
			for (int z = 0; z < chunkSize; z++) {
				byte packed = 0;
				try {
					packed = lightmapData[x][z];
				} catch (Throwable ignored) {
				}
				int blockLevel = packed & 0x0F;
				int skyLevel = (packed >> 4) & 0x0F;
				for (int y = 0; y < 256; y++) {
					int idx = (y * chunkSize * chunkSize) + (z * chunkSize) + x;
					blockLight[idx] = (byte) blockLevel;
					skyLight[idx] = (byte) skyLevel;
				}
			}
		}

		// Delegate to optimized async lighting optimizer
		LightDataOptimizer.getInstance().queueLightComputation(key, blockLight, skyLight);
	}

	private void computeChunkLighting(String chunkKey, byte[][] lightmapData) {
		// kept for backward compatibility but no longer used by queue method
		int chunkSize = 16;
		byte[] blockLight = new byte[chunkSize * chunkSize * 256];
		byte[] skyLight = new byte[chunkSize * chunkSize * 256];
		float[] ambientOcclusion = new float[chunkSize * chunkSize * 256];

		// Basic fallback: same as previous behavior
		for (int x = 0; x < chunkSize; x++) {
			for (int z = 0; z < chunkSize; z++) {
				for (int y = 0; y < 256; y++) {
					int idx = (x * chunkSize * 256) + (z * 256) + y;
					byte packed = lightmapData[x][z];
					blockLight[idx] = (byte) ((packed & 0x0F) << 4);
					skyLight[idx] = (byte) ((packed & 0xF0));
					ambientOcclusion[idx] = computeAOFactor(x, z, y);
				}
			}
		}

		lightingCache.put(chunkKey, new LightingData(blockLight, skyLight, ambientOcclusion));
	}

	private float computeAOFactor(int x, int z, int y) {
		// Simplified AO: 1.0 = full light, 0.0 = full shadow
		// In real implementation, would sample neighbor blocks
		return 0.8f; // Placeholder
	}

	/**
	 * Get pre-computed lighting for chunk (main thread safe, cached).
	 */
	public LightingData getLighting(ChunkPos chunkPos) {
		String key = chunkPos.x + ":" + chunkPos.z;
		return lightingCache.get(key);
	}

	/**
	 * Check if lighting is ready (non-blocking).
	 */
	public boolean isLightingReady(ChunkPos chunkPos) {
		String key = chunkPos.x + ":" + chunkPos.z;
		return lightingCache.containsKey(key);
	}

	/**
	 * Get block light level (main thread safe, 0-15).
	 */
	public byte getBlockLight(ChunkPos chunkPos, BlockPos blockPos) {
		LightingData data = getLighting(chunkPos);
		if (data == null) {
			return 0;
		}
		int idx = (blockPos.getX() & 15) * 256 + (blockPos.getZ() & 15) * 4096 + blockPos.getY();
		if (idx < data.blockLight.length) {
			return data.blockLight[idx];
		}
		return 0;
	}

	/**
	 * Get sky light level (main thread safe, 0-15).
	 */
	public byte getSkyLight(ChunkPos chunkPos, BlockPos blockPos) {
		LightingData data = getLighting(chunkPos);
		if (data == null) {
			return 15;
		}
		int idx = (blockPos.getX() & 15) * 256 + (blockPos.getZ() & 15) * 4096 + blockPos.getY();
		if (idx < data.skyLight.length) {
			return data.skyLight[idx];
		}
		return 15;
	}

	/**
	 * Get ambient occlusion (main thread safe, 0.0-1.0).
	 */
	public float getAmbientOcclusion(ChunkPos chunkPos, BlockPos blockPos) {
		LightingData data = getLighting(chunkPos);
		if (data == null) {
			return 1.0f;
		}
		int idx = (blockPos.getX() & 15) * 256 + (blockPos.getZ() & 15) * 4096 + blockPos.getY();
		if (idx < data.ambientOcclusion.length) {
			return data.ambientOcclusion[idx];
		}
		return 1.0f;
	}

	/**
	 * Clear lighting cache to free memory.
	 */
	public void clearCache() {
		lightingCache.clear();
	}

	/**
	 * Lighting data for a chunk (immutable).
	 */
	public static final class LightingData {
		public final byte[] blockLight;
		public final byte[] skyLight;
		public final float[] ambientOcclusion;

		public LightingData(byte[] blockLight, byte[] skyLight, float[] ambientOcclusion) {
			this.blockLight = blockLight;
			this.skyLight = skyLight;
			this.ambientOcclusion = ambientOcclusion;
		}
	}
}
