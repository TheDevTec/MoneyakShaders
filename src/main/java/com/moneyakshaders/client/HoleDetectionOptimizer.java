package com.moneyakshaders.client;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hole detection optimizer - identify holes in structures and optimize their rendering.
 * Holes are problematic: they require expensive light calculations and transparency sorting.
 *
 * <p>Pre-calculates which faces are "holes" (open to void), enabling targeted optimization.
 */
public final class HoleDetectionOptimizer {
	private static volatile HoleDetectionOptimizer instance;

	private final ConcurrentHashMap<String, HolePatternData> holePatterns = new ConcurrentHashMap<>();
	private final AtomicInteger chunksWithHoles = new AtomicInteger(0);
	private final AtomicInteger totalHolesDetected = new AtomicInteger(0);

	private static final int CHUNK_SIZE = 16;

	private HoleDetectionOptimizer() {
	}

	public static HoleDetectionOptimizer getInstance() {
		if (instance == null) {
			synchronized (HoleDetectionOptimizer.class) {
				if (instance == null) {
					instance = new HoleDetectionOptimizer();
				}
			}
		}
		return instance;
	}

	/**
	 * Detect holes in chunk (worker thread).
	 */
	public void queueHoleDetection(String chunkKey, int chunkX, int chunkZ, boolean[][][] solidMap) {
		ChunkMeshExecutor.executeBackground(() -> detectHoles(chunkKey, chunkX, chunkZ, solidMap));
	}

	private void detectHoles(String chunkKey, int chunkX, int chunkZ, boolean[][][] solidMap) {
		HolePatternData holeData = new HolePatternData(chunkX, chunkZ);

		// Scan for holes: air blocks adjacent to sky
		for (int x = 0; x < CHUNK_SIZE; x++) {
			for (int z = 0; z < CHUNK_SIZE; z++) {
				for (int y = 0; y < 256; y++) {
					// Check if this is an air block
					boolean isSolid = solidMap[x][y][z];
					if (isSolid)
						continue;

					// Check if adjacent to solid block (potential hole edge)
					boolean hasAdjacentSolid = false;
					if (x > 0 && solidMap[x - 1][y][z])
						hasAdjacentSolid = true;
					if (x < CHUNK_SIZE - 1 && solidMap[x + 1][y][z])
						hasAdjacentSolid = true;
					if (z > 0 && solidMap[x][y][z - 1])
						hasAdjacentSolid = true;
					if (z < CHUNK_SIZE - 1 && solidMap[x][y][z + 1])
						hasAdjacentSolid = true;

					if (hasAdjacentSolid) {
						holeData.addHolePosition(x, y, z);
					}
				}
			}
		}

		if (holeData.holeCount > 0) {
			holePatterns.put(chunkKey, holeData);
			chunksWithHoles.incrementAndGet();
			totalHolesDetected.addAndGet(holeData.holeCount);
		}
	}

	/**
	 * Get hole data for chunk (main thread safe).
	 */
	public HolePatternData getHolePattern(String chunkKey) {
		return holePatterns.get(chunkKey);
	}

	/**
	 * Check if position is near hole (needs expensive calculation).
	 */
	public boolean isNearHole(String chunkKey, int x, int y, int z, int radius) {
		HolePatternData holeData = holePatterns.get(chunkKey);
		if (holeData == null)
			return false;

		for (HolePatternData.HolePosition hole : holeData.holes) {
			int dx = hole.x - x;
			int dy = hole.y - y;
			int dz = hole.z - z;
			int distSq = dx * dx + dy * dy + dz * dz;
			if (distSq <= radius * radius) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Get statistics.
	 */
	public HoleStats getStats() {
		int chunksHoles = chunksWithHoles.get();
		int totalHoles = totalHolesDetected.get();
		long ramUsedBytes = chunksHoles * 1024L; // ~1 KB per chunk with holes

		return new HoleStats(chunksHoles, totalHoles, ramUsedBytes);
	}

	/**
	 * Clear cache.
	 */
	public void clearCache() {
		holePatterns.clear();
		chunksWithHoles.set(0);
		totalHolesDetected.set(0);
	}

	/**
	 * Hole pattern data.
	 */
	public static final class HolePatternData {
		public final int chunkX;
		public final int chunkZ;
		public final java.util.List<HolePosition> holes = new java.util.ArrayList<>();
		public int holeCount = 0;

		public HolePatternData(int chunkX, int chunkZ) {
			this.chunkX = chunkX;
			this.chunkZ = chunkZ;
		}

		public void addHolePosition(int x, int y, int z) {
			holes.add(new HolePosition(x, y, z));
			holeCount++;
		}

		public int getComplexityScore() {
			// Measure how complex the hole pattern is
			if (holeCount == 0)
				return 0;

			// 1-5 holes: low complexity
			if (holeCount <= 5)
				return 1;
			// 6-20 holes: medium
			if (holeCount <= 20)
				return 2;
			// 21-50 holes: high
			if (holeCount <= 50)
				return 3;
			// 50+ holes: very high
			return 4;
		}

		/**
		 * Hole position.
		 */
		public static final class HolePosition {
			public final int x;
			public final int y;
			public final int z;

			public HolePosition(int x, int y, int z) {
				this.x = x;
				this.y = y;
				this.z = z;
			}
		}
	}

	/**
	 * Hole statistics.
	 */
	public static final class HoleStats {
		public final int chunksWithHoles;
		public final int totalHolesDetected;
		public final long estimatedRamBytes;

		public HoleStats(int chunksWithHoles, int totalHolesDetected, long estimatedRamBytes) {
			this.chunksWithHoles = chunksWithHoles;
			this.totalHolesDetected = totalHolesDetected;
			this.estimatedRamBytes = estimatedRamBytes;
		}

		public String getRamUsedMB() {
			return String.format("%.2f MB", estimatedRamBytes / 1024.0 / 1024.0);
		}

		public int getAverageHolesPerChunk() {
			return chunksWithHoles > 0 ? totalHolesDetected / chunksWithHoles : 0;
		}
	}
}
