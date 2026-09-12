package com.moneyakshaders.client;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Block state lookup table - pre-caches all 1000s of block state properties.
 * Replaces expensive property lookups with O(1) hash table access.
 *
 * <p>RAM trade-off: ~200 bytes per block state (13,000 states × 200 = ~2.6 MB),
 * but eliminates all runtime property reflection.
 */
public final class BlockStateLookupTable {
	private static volatile BlockStateLookupTable instance;

	private final ConcurrentHashMap<Integer, BlockStateProperties> stateTable = new ConcurrentHashMap<>();
	private final AtomicInteger totalStates = new AtomicInteger(0);

	private BlockStateLookupTable() {
	}

	public static BlockStateLookupTable getInstance() {
		if (instance == null) {
			synchronized (BlockStateLookupTable.class) {
				if (instance == null) {
					instance = new BlockStateLookupTable();
				}
			}
		}
		return instance;
	}

	/**
	 * Pre-compute block state properties (worker thread).
	 */
	public void queueBlockStateComputation(int blockStateId, String blockName, boolean hasCollision,
			boolean hasTransparency, boolean hasRenderLayer) {
		ChunkMeshExecutor.getOrCreate()
				.execute(() -> computeBlockState(blockStateId, blockName, hasCollision, hasTransparency, hasRenderLayer));
	}

	private void computeBlockState(int blockStateId, String blockName, boolean hasCollision, boolean hasTransparency,
			boolean hasRenderLayer) {
		BlockStateProperties props = new BlockStateProperties();

		// Set basic properties
		props.blockStateId = blockStateId;
		props.blockName = blockName;
		props.hasCollision = hasCollision;
		props.hasTransparency = hasTransparency;
		props.hasRenderLayer = hasRenderLayer;

		// Classify block type
		if (blockName.contains("glass") || blockName.contains("Glass")) {
			props.blockType = BlockType.GLASS;
			props.isSolid = false;
			props.isFullCube = false;
			props.canOptimize = true;
		} else if (blockName.contains("water") || blockName.contains("Water")) {
			props.blockType = BlockType.LIQUID;
			props.isSolid = false;
			props.isFullCube = false;
			props.canOptimize = true;
		} else if (blockName.contains("air") || blockName.contains("Air")) {
			props.blockType = BlockType.AIR;
			props.isSolid = false;
			props.isFullCube = false;
			props.canOptimize = false;
		} else if (hasTransparency) {
			props.blockType = BlockType.TRANSPARENT;
			props.isSolid = false;
			props.isFullCube = false;
			props.canOptimize = true;
		} else {
			props.blockType = BlockType.SOLID;
			props.isSolid = true;
			props.isFullCube = true;
			props.canOptimize = false;
		}

		// Determine culling flags
		props.cullFaceNorth = props.isSolid;
		props.cullFaceSouth = props.isSolid;
		props.cullFaceEast = props.isSolid;
		props.cullFaceWest = props.isSolid;
		props.cullFaceUp = props.isSolid;
		props.cullFaceDown = props.isSolid;

		stateTable.put(blockStateId, props);
		totalStates.incrementAndGet();
	}

	/**
	 * Get cached block state properties (main thread safe, O(1)).
	 */
	public BlockStateProperties getBlockState(int blockStateId) {
		BlockStateProperties props = stateTable.get(blockStateId);
		if (props == null) {
			// Return default if not cached
			props = new BlockStateProperties();
		}
		return props;
	}

	/**
	 * Quick check: is block optimizable?
	 */
	public boolean canOptimize(int blockStateId) {
		BlockStateProperties props = stateTable.get(blockStateId);
		return props != null && props.canOptimize;
	}

	/**
	 * Quick check: is block solid?
	 */
	public boolean isSolid(int blockStateId) {
		BlockStateProperties props = stateTable.get(blockStateId);
		return props != null && props.isSolid;
	}

	/**
	 * Quick check: has transparency?
	 */
	public boolean hasTransparency(int blockStateId) {
		BlockStateProperties props = stateTable.get(blockStateId);
		return props != null && props.hasTransparency;
	}

	/**
	 * Get statistics.
	 */
	public BlockStateStats getStats() {
		int total = totalStates.get();
		long ramUsedBytes = total * 200; // ~200 bytes per entry

		return new BlockStateStats(total, ramUsedBytes);
	}

	/**
	 * Clear cache.
	 */
	public void clearCache() {
		stateTable.clear();
		totalStates.set(0);
	}

	/**
	 * Block type enum.
	 */
	public enum BlockType {
		SOLID, GLASS, LIQUID, TRANSPARENT, AIR
	}

	/**
	 * Block state properties (cached).
	 */
	public static final class BlockStateProperties {
		public int blockStateId;
		public String blockName;
		public boolean hasCollision;
		public boolean hasTransparency;
		public boolean hasRenderLayer;

		public BlockType blockType;
		public boolean isSolid;
		public boolean isFullCube;
		public boolean canOptimize;

		// Culling flags
		public boolean cullFaceNorth;
		public boolean cullFaceSouth;
		public boolean cullFaceEast;
		public boolean cullFaceWest;
		public boolean cullFaceUp;
		public boolean cullFaceDown;

		public BlockStateProperties() {
			this.blockStateId = 0;
			this.blockName = "unknown";
			this.hasCollision = true;
			this.hasTransparency = false;
			this.hasRenderLayer = false;
			this.blockType = BlockType.SOLID;
			this.isSolid = true;
			this.isFullCube = true;
			this.canOptimize = false;
			this.cullFaceNorth = true;
			this.cullFaceSouth = true;
			this.cullFaceEast = true;
			this.cullFaceWest = true;
			this.cullFaceUp = true;
			this.cullFaceDown = true;
		}

		public boolean canCullFace(int face) {
			return switch (face) {
				case 0 -> cullFaceNorth;
				case 1 -> cullFaceSouth;
				case 2 -> cullFaceEast;
				case 3 -> cullFaceWest;
				case 4 -> cullFaceUp;
				case 5 -> cullFaceDown;
				default -> false;
			};
		}
	}

	/**
	 * Block state statistics.
	 */
	public static final class BlockStateStats {
		public final int totalBlockStates;
		public final long estimatedRamBytes;

		public BlockStateStats(int totalBlockStates, long estimatedRamBytes) {
			this.totalBlockStates = totalBlockStates;
			this.estimatedRamBytes = estimatedRamBytes;
		}

		public String getRamUsedMB() {
			return String.format("%.1f MB", estimatedRamBytes / 1024.0 / 1024.0);
		}
	}
}
