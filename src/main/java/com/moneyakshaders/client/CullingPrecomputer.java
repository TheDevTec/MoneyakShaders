package com.moneyakshaders.client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;

/**
 * Pre-compute view frustum culling in worker threads.
 * Determine visibility of chunks before main render pass.
 *
 * <p>Builds frustum planes and performs culling tests asynchronously.
 * Main thread gets cached visibility results without computation overhead.
 */
public final class CullingPrecomputer {
	private static volatile CullingPrecomputer instance;

	private final ConcurrentHashMap<String, CullingResult> cullingCache = new ConcurrentHashMap<>();

	private CullingPrecomputer() {
	}

	public static CullingPrecomputer getInstance() {
		if (instance == null) {
			synchronized (CullingPrecomputer.class) {
				if (instance == null) {
					instance = new CullingPrecomputer();
				}
			}
		}
		return instance;
	}

	/**
	 * Schedule async culling computation for chunks.
	 */
	public void queueCullingPass(Vec3d cameraPos, FrustumPlanes frustum, List<ChunkPos> chunks) {
		ChunkMeshExecutor.executeWithBackpressure(() -> performCullingPass(cameraPos, frustum, chunks));
	}

	private void performCullingPass(Vec3d cameraPos, FrustumPlanes frustum, List<ChunkPos> chunks) {
		List<ChunkPos> visible = new ArrayList<>();
		List<Integer> distances = new ArrayList<>();

		for (ChunkPos chunkPos : chunks) {
			Box chunkBounds = new Box(chunkPos.getStartX(), 0, chunkPos.getStartZ(), chunkPos.getEndX(), 256,
					chunkPos.getEndZ());

			if (isBoxInFrustum(chunkBounds, frustum)) {
				visible.add(chunkPos);
				int distance = (int) Math.sqrt(cameraPos.squaredDistanceTo(chunkPos.getCenterX(), 128,
						chunkPos.getCenterZ()));
				distances.add(distance);
			}
		}

		// Cache result
		String cacheKey = generateCacheKey(cameraPos);
		cullingCache.put(cacheKey, new CullingResult(visible, distances));
	}

	private boolean isBoxInFrustum(Box box, FrustumPlanes frustum) {
		// AABB vs Frustum intersection test
		for (Plane plane : frustum.planes) {
			float distance = plane.distance(box);
			if (distance < 0) {
				return false; // Box is completely outside this plane
			}
		}
		return true;
	}

	/**
	 * Get cached culling result (main thread safe).
	 */
	public CullingResult getCullingResult(Vec3d cameraPos) {
		String key = generateCacheKey(cameraPos);
		return cullingCache.get(key);
	}

	/**
	 * Check if culling result is cached (non-blocking).
	 */
	public boolean isCullingResultReady(Vec3d cameraPos) {
		String key = generateCacheKey(cameraPos);
		return cullingCache.containsKey(key);
	}

	private String generateCacheKey(Vec3d pos) {
		// Quantize camera position to reduce cache misses
		int x = (int) Math.floor(pos.x / 16);
		int z = (int) Math.floor(pos.z / 16);
		return x + ":" + z;
	}

	/**
	 * Clear culling cache to free memory.
	 */
	public void clearCache() {
		cullingCache.clear();
	}

	/**
	 * Culling result (immutable).
	 */
	public static final class CullingResult {
		public final List<ChunkPos> visibleChunks;
		public final List<Integer> distances;

		public CullingResult(List<ChunkPos> visibleChunks, List<Integer> distances) {
			this.visibleChunks = visibleChunks;
			this.distances = distances;
		}

		public int getVisibleChunkCount() {
			return visibleChunks.size();
		}

		public int getClosestChunkDistance() {
			if (distances.isEmpty()) {
				return Integer.MAX_VALUE;
			}
			return distances.stream().mapToInt(Integer::intValue).min().orElse(Integer.MAX_VALUE);
		}

		public int getFarthestChunkDistance() {
			if (distances.isEmpty()) {
				return 0;
			}
			return distances.stream().mapToInt(Integer::intValue).max().orElse(0);
		}
	}

	/**
	 * Frustum plane definition.
	 */
	public static final class FrustumPlanes {
		public final Plane[] planes;

		public FrustumPlanes(Plane[] planes) {
			this.planes = planes;
		}
	}

	/**
	 * 3D plane definition (ax + by + cz + d = 0).
	 */
	public static final class Plane {
		public final float a, b, c, d;

		public Plane(float a, float b, float c, float d) {
			this.a = a;
			this.b = b;
			this.c = c;
			this.d = d;
		}

		public float distance(Box box) {
			// Find closest point on AABB to plane
			float x = a > 0 ? (float) box.minX : (float) box.maxX;
			float y = b > 0 ? (float) box.minY : (float) box.maxY;
			float z = c > 0 ? (float) box.minZ : (float) box.maxZ;
			return a * x + b * y + c * z + d;
		}
	}
}
