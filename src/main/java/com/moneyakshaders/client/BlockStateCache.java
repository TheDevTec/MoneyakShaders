package com.moneyakshaders.client;

import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.util.math.ChunkPos;

/**
 * Cache pre-computed block state metadata for chunks.
 * Offloads block state queries from main render thread.
 *
 * <p>Pre-computes block flags, render layers, and culling info asynchronously.
 * Main thread queries cached data without computation overhead.
 */
public final class BlockStateCache {
	private static volatile BlockStateCache instance;

	private final ConcurrentHashMap<String, BlockStateData> stateCache = new ConcurrentHashMap<>();

	private BlockStateCache() {
	}

	public static BlockStateCache getInstance() {
		if (instance == null) {
			synchronized (BlockStateCache.class) {
				if (instance == null) {
					instance = new BlockStateCache();
				}
			}
		}
		return instance;
	}

	/**
	 * Schedule async block state precomputation for chunk.
	 */
	public void queueBlockStateComputation(ChunkPos chunkPos, byte[] blockIds) {
		ChunkMeshExecutor.executeWithBackpressure(chunkPos, () -> precomputeBlockStates(chunkPos, blockIds));
	}

	private void precomputeBlockStates(ChunkPos chunkPos, byte[] blockIds) {
		String key = chunkPos.x + ":" + chunkPos.z;
		int chunkSize = 16;
		boolean[] hasTransparent = new boolean[chunkSize * chunkSize * 256];
		boolean[] hasSolid = new boolean[chunkSize * chunkSize * 256];
		byte[] renderLayers = new byte[chunkSize * chunkSize * 256];
		boolean[] isCulled = new boolean[chunkSize * chunkSize * 256];

		// Precompute state flags for each block
		for (int x = 0; x < chunkSize; x++) {
			for (int z = 0; z < chunkSize; z++) {
				for (int y = 0; y < 256; y++) {
					int idx = (x * chunkSize * 256) + (z * 256) + y;
					if (idx < blockIds.length) {
						byte blockId = blockIds[idx];

						hasTransparent[idx] = isTransparentBlock(blockId);
						hasSolid[idx] = isSolidBlock(blockId);
						renderLayers[idx] = getRenderLayer(blockId);
						isCulled[idx] = shouldCull(blockId);
					}
				}
			}
		}

		// Count statistics
		int transparentCount = 0;
		int solidCount = 0;
		for (boolean b : hasTransparent) {
			if (b) transparentCount++;
		}
		for (boolean b : hasSolid) {
			if (b) solidCount++;
		}

		stateCache.put(key,
				new BlockStateData(hasTransparent, hasSolid, renderLayers, isCulled, transparentCount, solidCount));
	}

	private boolean isTransparentBlock(byte blockId) {
		// Simplified: blocks 0, 31, 32, 37, etc. are transparent/leaves
		return blockId == 0 || (blockId >= 31 && blockId <= 35) || (blockId >= 100 && blockId <= 105);
	}

	private boolean isSolidBlock(byte blockId) {
		// Simplified: most blocks are solid except air, liquids
		return blockId != 0 && blockId != 8 && blockId != 9;
	}

	private byte getRenderLayer(byte blockId) {
		// 0 = solid, 1 = cutout, 2 = cutout_mipped, 3 = translucent
		if (blockId == 0)
			return 0; // Air
		if (blockId >= 31 && blockId <= 35)
			return 1; // Leaves - cutout
		if (blockId == 20)
			return 2; // Glass - translucent
		return 0; // Default solid
	}

	private boolean shouldCull(byte blockId) {
		// Skip rendering culling for transparent blocks
		return !isTransparentBlock(blockId);
	}

	/**
	 * Get cached block state data (main thread safe).
	 */
	public BlockStateData getBlockStates(ChunkPos chunkPos) {
		String key = chunkPos.x + ":" + chunkPos.z;
		return stateCache.get(key);
	}

	/**
	 * Check if block states are cached (non-blocking).
	 */
	public boolean isBlockStateCached(ChunkPos chunkPos) {
		String key = chunkPos.x + ":" + chunkPos.z;
		return stateCache.containsKey(key);
	}

	/**
	 * Query single block state (main thread safe, cached).
	 */
	public boolean hasTransparency(ChunkPos chunkPos, int x, int y, int z) {
		BlockStateData data = getBlockStates(chunkPos);
		if (data == null)
			return false;
		int idx = (x & 15) * 256 + (z & 15) * 4096 + y;
		if (idx < data.hasTransparent.length) {
			return data.hasTransparent[idx];
		}
		return false;
	}

	/**
	 * Query block render layer (main thread safe, cached).
	 */
	public byte getRenderLayer(ChunkPos chunkPos, int x, int y, int z) {
		BlockStateData data = getBlockStates(chunkPos);
		if (data == null)
			return 0;
		int idx = (x & 15) * 256 + (z & 15) * 4096 + y;
		if (idx < data.renderLayers.length) {
			return data.renderLayers[idx];
		}
		return 0;
	}

	/**
	 * Clear cache to free memory.
	 */
	public void clearCache() {
		stateCache.clear();
	}

	/**
	 * Block state data for a chunk (immutable).
	 */
	public static final class BlockStateData {
		public final boolean[] hasTransparent;
		public final boolean[] hasSolid;
		public final byte[] renderLayers;
		public final boolean[] isCulled;
		public final int transparentBlockCount;
		public final int solidBlockCount;

		public BlockStateData(boolean[] hasTransparent, boolean[] hasSolid, byte[] renderLayers, boolean[] isCulled,
				int transparentBlockCount, int solidBlockCount) {
			this.hasTransparent = hasTransparent;
			this.hasSolid = hasSolid;
			this.renderLayers = renderLayers;
			this.isCulled = isCulled;
			this.transparentBlockCount = transparentBlockCount;
			this.solidBlockCount = solidBlockCount;
		}

		public float getTransparencyRatio() {
			int total = hasTransparent.length;
			return total == 0 ? 0 : (float) transparentBlockCount / total;
		}

		public float getSolidRatio() {
			int total = hasSolid.length;
			return total == 0 ? 0 : (float) solidBlockCount / total;
		}

		public boolean hasAnyTransparency() {
			return transparentBlockCount > 0;
		}

		public boolean isDenseSolid() {
			return getSolidRatio() > 0.8f;
		}
	}
}
