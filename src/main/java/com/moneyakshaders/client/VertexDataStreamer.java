package com.moneyakshaders.client;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.util.math.ChunkPos;

/**
 * Asynchronously load and prepare vertex data for chunks.
 * Streams vertex data preparation off the main thread.
 *
 * <p>Pre-allocates vertex buffers, loads mesh data, and prepares for GPU upload
 * without blocking render thread. Main thread only binds pre-prepared data.
 */
public final class VertexDataStreamer {
	private static volatile VertexDataStreamer instance;

	private final ConcurrentHashMap<String, VertexBuffer> vertexBufferCache = new ConcurrentHashMap<>();
	private final AtomicInteger pendingOperations = new AtomicInteger(0);

	private VertexDataStreamer() {
	}

	public static VertexDataStreamer getInstance() {
		if (instance == null) {
			synchronized (VertexDataStreamer.class) {
				if (instance == null) {
					instance = new VertexDataStreamer();
				}
			}
		}
		return instance;
	}

	/**
	 * Schedule async vertex buffer preparation for chunk.
	 */
	public void queueVertexDataPreparation(ChunkPos chunkPos, float[] vertices, int[] indices) {
		pendingOperations.incrementAndGet();
		if (!ChunkMeshExecutor.executeWithBackpressure(chunkPos, () -> {
			try {
				prepareVertexData(chunkPos, vertices, indices);
			} finally {
				pendingOperations.decrementAndGet();
			}
		})) {
			pendingOperations.decrementAndGet();
		}
	}

	private void prepareVertexData(ChunkPos chunkPos, float[] vertices, int[] indices) {
		String key = chunkPos.x + ":" + chunkPos.z;

		// Pre-compute vertex buffer metadata
		int vertexCount = vertices.length / 3; // Assume 3 floats per vertex
		int indexCount = indices.length;
		int vertexBufferSize = vertexCount * 32; // 32 bytes per vertex (position + UV + normal + color)
		int indexBufferSize = indexCount * 4;

		// Create and stage buffer
		VertexBuffer buffer = new VertexBuffer(vertexBufferSize, indexBufferSize, vertices, indices);
		vertexBufferCache.put(key, buffer);
	}

	/**
	 * Get prepared vertex buffer (main thread safe, cached).
	 */
	public VertexBuffer getVertexBuffer(ChunkPos chunkPos) {
		String key = chunkPos.x + ":" + chunkPos.z;
		return vertexBufferCache.get(key);
	}

	/**
	 * Check if vertex buffer is ready (non-blocking).
	 */
	public boolean isVertexBufferReady(ChunkPos chunkPos) {
		String key = chunkPos.x + ":" + chunkPos.z;
		return vertexBufferCache.containsKey(key);
	}

	/**
	 * Get count of pending vertex operations.
	 */
	public int getPendingOperations() {
		return pendingOperations.get();
	}

	/**
	 * Wait for all pending operations to complete.
	 * Use cautiously on main thread.
	 */
	public void waitForCompletion(long timeoutMs) {
		long start = System.currentTimeMillis();
		while (pendingOperations.get() > 0) {
			if (System.currentTimeMillis() - start > timeoutMs) {
				break;
			}
			try {
				Thread.sleep(1);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
	}

	/**
	 * Clear vertex buffer cache to free memory.
	 */
	public void clearCache() {
		vertexBufferCache.clear();
	}

	/**
	 * Pre-prepared vertex buffer (immutable once prepared).
	 */
	public static final class VertexBuffer {
		public final int vertexBufferSize;
		public final int indexBufferSize;
		public final float[] vertices;
		public final int[] indices;
		public final long preparedTime;

		public VertexBuffer(int vertexBufferSize, int indexBufferSize, float[] vertices, int[] indices) {
			this.vertexBufferSize = vertexBufferSize;
			this.indexBufferSize = indexBufferSize;
			this.vertices = vertices;
			this.indices = indices;
			this.preparedTime = System.currentTimeMillis();
		}

		public int getVertexCount() {
			return vertices.length / 3;
		}

		public int getIndexCount() {
			return indices.length;
		}

		public long getAgeMs() {
			return System.currentTimeMillis() - preparedTime;
		}
	}
}
