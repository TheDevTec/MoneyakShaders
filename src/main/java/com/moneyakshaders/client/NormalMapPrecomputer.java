package com.moneyakshaders.client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pre-compute normal maps from height maps in worker threads.
 * Eliminates real-time normal map computation during render passes.
 *
 * <p>Converts heightmaps to normal maps asynchronously using Sobel filters.
 * Main thread queries cached normal data without blocking.
 */
public final class NormalMapPrecomputer {
	private static volatile NormalMapPrecomputer instance;

	private final ConcurrentHashMap<String, NormalMapData> normalCache = new ConcurrentHashMap<>();

	private NormalMapPrecomputer() {
	}

	public static NormalMapPrecomputer getInstance() {
		if (instance == null) {
			synchronized (NormalMapPrecomputer.class) {
				if (instance == null) {
					instance = new NormalMapPrecomputer();
				}
			}
		}
		return instance;
	}

	/**
	 * Schedule async normal map computation from heightmap.
	 */
	public void queueNormalMapComputation(String heightmapId, float[][] heightmap) {
		ChunkMeshExecutor.executeBackground(() -> computeNormalMap(heightmapId, heightmap));
	}

	private void computeNormalMap(String heightmapId, float[][] heightmap) {
		int width = heightmap.length;
		int height = heightmap[0].length;

		float[][][] normals = new float[width][height][3];

		// Sobel filter for normal computation
		for (int x = 1; x < width - 1; x++) {
			for (int y = 1; y < height - 1; y++) {
				float sobX = (-heightmap[x - 1][y - 1] - 2 * heightmap[x - 1][y] - heightmap[x - 1][y + 1])
						+ (heightmap[x + 1][y - 1] + 2 * heightmap[x + 1][y] + heightmap[x + 1][y + 1]);

				float sobY = (-heightmap[x - 1][y - 1] - 2 * heightmap[x][y - 1] - heightmap[x + 1][y - 1])
						+ (heightmap[x - 1][y + 1] + 2 * heightmap[x][y + 1] + heightmap[x + 1][y + 1]);

				float sobZ = 1.0f;

				// Normalize
				float length = (float) Math.sqrt(sobX * sobX + sobY * sobY + sobZ * sobZ);
				normals[x][y][0] = sobX / length;
				normals[x][y][1] = sobY / length;
				normals[x][y][2] = sobZ / length;
			}
		}

		normalCache.put(heightmapId, new NormalMapData(normals, width, height));
	}

	/**
	 * Get pre-computed normal data (main thread safe, cached).
	 */
	public NormalMapData getNormalMap(String heightmapId) {
		return normalCache.get(heightmapId);
	}

	/**
	 * Check if normal map is ready (non-blocking).
	 */
	public boolean isNormalMapReady(String heightmapId) {
		return normalCache.containsKey(heightmapId);
	}

	/**
	 * Pre-compute normals for a batch of heightmaps (parallel).
	 */
	public void precomputeNormalBatch(List<HeightmapEntry> entries) {
		entries.forEach(entry -> queueNormalMapComputation(entry.id, entry.heightmap));
	}

	/**
	 * Clear cached normal maps to free memory.
	 */
	public void clearCache() {
		normalCache.clear();
	}

	/**
	 * Normal map data (immutable).
	 */
	public static final class NormalMapData {
		public final float[][][] normals;
		public final int width;
		public final int height;

		public NormalMapData(float[][][] normals, int width, int height) {
			this.normals = normals;
			this.width = width;
			this.height = height;
		}

		public float[] getNormal(int x, int y) {
			if (x < 0 || x >= width || y < 0 || y >= height) {
				return new float[] { 0.0f, 0.0f, 1.0f }; // Default normal
			}
			return normals[x][y];
		}
	}

	/**
	 * Entry for batch precomputation.
	 */
	public static final class HeightmapEntry {
		public final String id;
		public final float[][] heightmap;

		public HeightmapEntry(String id, float[][] heightmap) {
			this.id = id;
			this.heightmap = heightmap;
		}
	}
}
