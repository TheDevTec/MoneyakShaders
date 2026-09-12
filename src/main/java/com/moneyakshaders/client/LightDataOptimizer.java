package com.moneyakshaders.client;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Light data cache - pre-cache light levels for chunks to avoid runtime light propagation.
 * Critical for performance in complex buildings with many holes and shadow rays.
 *
 * <p>Stores block light (torches, lava), sky light (sun), and combined lighting.
 * Eliminates expensive light propagation calculations per frame.
 */
public final class LightDataOptimizer {
	private static volatile LightDataOptimizer instance;

	private final ConcurrentHashMap<String, ChunkLightData> lightCache = new ConcurrentHashMap<>();
	private final AtomicInteger cachedChunks = new AtomicInteger(0);

	private static final int CHUNK_SIZE = 16;
	private static final int CHUNK_HEIGHT = 256;
	private static final int LIGHT_LEVELS = 16;

	private LightDataOptimizer() {
	}

	public static LightDataOptimizer getInstance() {
		if (instance == null) {
			synchronized (LightDataOptimizer.class) {
				if (instance == null) {
					instance = new LightDataOptimizer();
				}
			}
		}
		return instance;
	}

	/**
	 * Pre-compute light data for chunk (worker thread).
	 */
	public void queueLightComputation(String chunkKey, byte[] blockLightData, byte[] skyLightData) {
		try {
			// submit to the async lighting engine for more controlled scheduling
			com.moneyakshaders.engine.AsyncLightingEngine.getInstance().submitLightingTask(null, () -> computeLightData(chunkKey, blockLightData, skyLightData));
		} catch (Throwable t) {
			ChunkMeshExecutor.executeBackground(() -> computeLightData(chunkKey, blockLightData, skyLightData));
		}
	}

	private void computeLightData(String chunkKey, byte[] blockLightData, byte[] skyLightData) {
		ChunkLightData lightData = new ChunkLightData(CHUNK_SIZE, CHUNK_HEIGHT);

		int total = CHUNK_SIZE * CHUNK_HEIGHT * CHUNK_SIZE;
		byte[] block = blockLightData != null ? blockLightData.clone() : new byte[total];
		byte[] sky = skyLightData != null ? skyLightData.clone() : new byte[total];

		// Initialize combined with sources (max of block/sky)
		byte[] combined = lightData.combinedLight;
		for (int i = 0; i < total; i++) {
			int b = block[i] & 0xF;
			int s = sky[i] & 0xF;
			combined[i] = (byte) Math.max(b, s);
		}

		// Simple BFS flood-fill propagation within the chunk.
		// Start from all source cells and propagate to neighbors (6-connectivity), decreasing by 1 each step.
		int sizeX = CHUNK_SIZE;
		int sizeY = CHUNK_HEIGHT;
		int sizeZ = CHUNK_SIZE;

		int[] queue = new int[total];
		int qh = 0, qt = 0;

		for (int idx = 0; idx < total; idx++) {
			if ((combined[idx] & 0xF) > 0) {
				queue[qt++] = idx;
			}
		}

		while (qh < qt) {
			int idx = queue[qh++];
			int cur = combined[idx] & 0xF;
			if (cur <= 1) continue; // nothing to spread

			// compute x,y,z
			int y = idx / (sizeX * sizeZ);
			int rem = idx - y * sizeX * sizeZ;
			int z = rem / sizeX;
			int x = rem - z * sizeX;

			int[][] neigh = new int[][]{
				{x+1,y,z},{x-1,y,z},{x,y+1,z},{x,y-1,z},{x,y,z+1},{x,y,z-1}
			};

			for (int[] n : neigh) {
				int nx = n[0], ny = n[1], nz = n[2];
				if (nx < 0 || nx >= sizeX || ny < 0 || ny >= sizeY || nz < 0 || nz >= sizeZ) continue;
				int nidx = (ny * sizeX * sizeZ) + (nz * sizeX) + nx;
				int prev = combined[nidx] & 0xF;
				int want = cur - 1;
				if (prev < want) {
					combined[nidx] = (byte) want;
					queue[qt++] = nidx;
				}
			}
		}

		// Store raw arrays (combined array is already part of lightData)
		lightData.blockLight = block;
		lightData.skyLight = sky;

		lightCache.put(chunkKey, lightData);
		cachedChunks.incrementAndGet();

		// Schedule neighbor propagation: extract border light and submit to adjacent chunks
		try {
			String[] parts = chunkKey.split(":", 2);
			if (parts.length == 2) {
				int cx = Integer.parseInt(parts[0]);
				int cz = Integer.parseInt(parts[1]);
				scheduleNeighborPropagation(cx, cz, combined);
			}
		} catch (Throwable ignored) {
		}
	}

	/**
	 * Extract border columns and submit lightweight merge tasks for adjacent chunks.
	 */
	private void scheduleNeighborPropagation(int cx, int cz, byte[] combined) {
		final int sizeX = CHUNK_SIZE, sizeZ = CHUNK_SIZE, sizeY = CHUNK_HEIGHT;

		// Helper to build a small face map and submit
		// face: 0=+X (east), 1=-X (west), 2=+Z (south), 3=-Z (north)
		for (int face = 0; face < 4; face++) {
			int nx = cx + (face == 0 ? 1 : face == 1 ? -1 : 0);
			int nz = cz + (face == 2 ? 1 : face == 3 ? -1 : 0);

			final byte[] faceSources = new byte[sizeY * sizeZ * 1]; // store column (x fixed)
			int idx = 0;
			if (face == 0) { // +X, take x=15 column
				int x = sizeX - 1;
				for (int y = 0; y < sizeY; y++) {
					for (int z = 0; z < sizeZ; z++) {
						int i = (y * sizeX * sizeZ) + (z * sizeX) + x;
						int v = (combined[i] & 0xF) - 1; // decay across chunk boundary
						faceSources[idx++] = (byte) Math.max(0, v);
					}
				}
			} else if (face == 1) { // -X, x=0
				int x = 0;
				for (int y = 0; y < sizeY; y++) {
					for (int z = 0; z < sizeZ; z++) {
						int i = (y * sizeX * sizeZ) + (z * sizeX) + x;
						int v = (combined[i] & 0xF) - 1;
						faceSources[idx++] = (byte) Math.max(0, v);
					}
				}
			} else if (face == 2) { // +Z, z=15
				int z = sizeZ - 1;
				for (int y = 0; y < sizeY; y++) {
					for (int x = 0; x < sizeX; x++) {
						int i = (y * sizeX * sizeZ) + (z * sizeX) + x;
						int v = (combined[i] & 0xF) - 1;
						faceSources[idx++] = (byte) Math.max(0, v);
					}
				}
			} else { // -Z, z=0
				int z = 0;
				for (int y = 0; y < sizeY; y++) {
					for (int x = 0; x < sizeX; x++) {
						int i = (y * sizeX * sizeZ) + (z * sizeX) + x;
						int v = (combined[i] & 0xF) - 1;
						faceSources[idx++] = (byte) Math.max(0, v);
					}
				}
			}

			// skip if all zeros
			boolean any = false;
			for (byte b : faceSources) if ((b & 0xF) > 0) { any = true; break; }
			if (!any) continue;

			final int submitX = nx, submitZ = nz, submitFace = face;
			com.moneyakshaders.engine.AsyncLightingEngine.getInstance().submitLightingTask(new net.minecraft.util.math.ChunkPos(submitX, submitZ), () -> {
				try {
					String nkey = submitX + ":" + submitZ;
					ChunkLightData neighbor = lightCache.get(nkey);
					if (neighbor == null) {
						neighbor = new ChunkLightData(CHUNK_SIZE, CHUNK_HEIGHT);
						neighbor.blockLight = new byte[CHUNK_SIZE * CHUNK_HEIGHT * CHUNK_SIZE];
						neighbor.skyLight = new byte[CHUNK_SIZE * CHUNK_HEIGHT * CHUNK_SIZE];
						// combined is zeroed by constructor
						ChunkLightData existed = lightCache.putIfAbsent(nkey, neighbor);
						if (existed != null) neighbor = existed;
					}

					// Merge incoming faceSources into neighbor.combined and run BFS from updated cells.
					synchronized (neighbor) {
						int sizeX2 = CHUNK_SIZE, sizeZ2 = CHUNK_SIZE, sizeY2 = CHUNK_HEIGHT;
						int total = sizeX2 * sizeY2 * sizeZ2;
						int[] queue = new int[total];
						int qh = 0, qt = 0;

						if (submitFace == 0) { // +X neighbor: incoming at x=0
							int x = 0;
							int srcIdx = 0;
							for (int y = 0; y < sizeY2; y++) {
								for (int z = 0; z < sizeZ2; z++) {
									int dest = (y * sizeX2 * sizeZ2) + (z * sizeX2) + x;
									int v = faceSources[srcIdx++] & 0xF;
									if ((neighbor.combinedLight[dest] & 0xF) < v) {
										neighbor.combinedLight[dest] = (byte) v;
										queue[qt++] = dest;
									}
								}
							}
						} else if (submitFace == 1) { // -X neighbor: incoming at x=15
							int x = sizeX2 - 1;
							int srcIdx = 0;
							for (int y = 0; y < sizeY2; y++) {
								for (int z = 0; z < sizeZ2; z++) {
									int dest = (y * sizeX2 * sizeZ2) + (z * sizeX2) + x;
									int v = faceSources[srcIdx++] & 0xF;
									if ((neighbor.combinedLight[dest] & 0xF) < v) {
										neighbor.combinedLight[dest] = (byte) v;
										queue[qt++] = dest;
									}
								}
							}
						} else if (submitFace == 2) { // +Z neighbor incoming at z=0
							int z = 0;
							int srcIdx = 0;
							for (int y = 0; y < sizeY2; y++) {
								for (int x = 0; x < sizeX2; x++) {
									int dest = (y * sizeX2 * sizeZ2) + (z * sizeX2) + x;
									int v = faceSources[srcIdx++] & 0xF;
									if ((neighbor.combinedLight[dest] & 0xF) < v) {
										neighbor.combinedLight[dest] = (byte) v;
										queue[qt++] = dest;
									}
								}
							}
						} else { // -Z neighbor incoming at z=15
							int z = sizeZ2 - 1;
							int srcIdx = 0;
							for (int y = 0; y < sizeY2; y++) {
								for (int x = 0; x < sizeX2; x++) {
									int dest = (y * sizeX2 * sizeZ2) + (z * sizeX2) + x;
									int v = faceSources[srcIdx++] & 0xF;
									if ((neighbor.combinedLight[dest] & 0xF) < v) {
										neighbor.combinedLight[dest] = (byte) v;
										queue[qt++] = dest;
									}
								}
							}
						}

						// BFS inside neighbor
						while (qh < qt) {
							int id = queue[qh++];
							int cur = neighbor.combinedLight[id] & 0xF;
							if (cur <= 1) continue;
							int yy = id / (sizeX2 * sizeZ2);
							int rem = id - yy * sizeX2 * sizeZ2;
							int zz = rem / sizeX2;
							int xx = rem - zz * sizeX2;

							int[][] neigh2 = new int[][]{
								{xx+1,yy,zz},{xx-1,yy,zz},{xx,yy+1,zz},{xx,yy-1,zz},{xx,yy,zz+1},{xx,yy,zz-1}
							};
							for (int[] n2 : neigh2) {
								int nx2 = n2[0], ny2 = n2[1], nz2 = n2[2];
								if (nx2 < 0 || nx2 >= sizeX2 || ny2 < 0 || ny2 >= sizeY2 || nz2 < 0 || nz2 >= sizeZ2) continue;
								int nidx = (ny2 * sizeX2 * sizeZ2) + (nz2 * sizeX2) + nx2;
								int prev = neighbor.combinedLight[nidx] & 0xF;
								int want = cur - 1;
								if (prev < want) {
									neighbor.combinedLight[nidx] = (byte) want;
									queue[qt++] = nidx;
								}
							}
						}
					}
				} catch (Throwable ignored) {
				}
			});
		}
	}

	/**
	 * Get cached light data (main thread safe).
	 */
	public ChunkLightData getLightData(String chunkKey) {
		return lightCache.get(chunkKey);
	}

	/**
	 * Get light level at position (O(1) lookup).
	 */
	public int getLightLevel(String chunkKey, int x, int y, int z) {
		ChunkLightData lightData = lightCache.get(chunkKey);
		if (lightData == null)
			return 15; // Default: fully lit

		int index = (y * CHUNK_SIZE * CHUNK_SIZE) + (z * CHUNK_SIZE) + x;
		if (index >= 0 && index < lightData.combinedLight.length) {
			return lightData.combinedLight[index] & 0xF;
		}
		return 15;
	}

	/**
	 * Get statistics.
	 */
	public LightStats getStats() {
		int total = cachedChunks.get();
		long ramUsedBytes = total * (CHUNK_SIZE * CHUNK_HEIGHT * CHUNK_SIZE * 3); // 3 arrays per chunk

		return new LightStats(total, ramUsedBytes);
	}

	/**
	 * Clear cache.
	 */
	public void clearCache() {
		lightCache.clear();
		cachedChunks.set(0);
	}

	/**
	 * Chunk light data.
	 */
	public static final class ChunkLightData {
		public final int chunkSize;
		public final int chunkHeight;
		public byte[] blockLight; // Block light values (0-15)
		public byte[] skyLight; // Sky light values (0-15)
		public final byte[] combinedLight; // Max of block and sky light

		public ChunkLightData(int chunkSize, int chunkHeight) {
			this.chunkSize = chunkSize;
			this.chunkHeight = chunkHeight;
			this.combinedLight = new byte[chunkSize * chunkHeight * chunkSize];
		}

		public boolean isDarkArea() {
			// Check if majority of chunk is dark (light < 8)
			int darkCount = 0;
			for (byte light : combinedLight) {
				if ((light & 0xF) < 8)
					darkCount++;
			}
			return darkCount > combinedLight.length / 2;
		}

		public int getAverageLightLevel() {
			int sum = 0;
			for (byte light : combinedLight) {
				sum += (light & 0xF);
			}
			return sum / combinedLight.length;
		}
	}

	/**
	 * Light statistics.
	 */
	public static final class LightStats {
		public final int cachedChunks;
		public final long estimatedRamBytes;

		public LightStats(int cachedChunks, long estimatedRamBytes) {
			this.cachedChunks = cachedChunks;
			this.estimatedRamBytes = estimatedRamBytes;
		}

		public String getRamUsedMB() {
			return String.format("%.1f MB", estimatedRamBytes / 1024.0 / 1024.0);
		}
	}
}
