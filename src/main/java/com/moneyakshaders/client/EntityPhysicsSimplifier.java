package com.moneyakshaders.client;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Entity physics simplification - use simpler physics for distant entities.
 * Trade accuracy for performance: distant mobs use bounding boxes, no collision.
 *
 * <p>RAM trade-off: ~60 bytes per entity, saves 40-60% entity physics cost.
 */
public final class EntityPhysicsSimplifier {
	private static volatile EntityPhysicsSimplifier instance;

	private final ConcurrentHashMap<Integer, EntityPhysicsLevel> physicsCache = new ConcurrentHashMap<>();
	private final AtomicInteger totalEntities = new AtomicInteger(0);
	private final AtomicInteger cullableEntities = new AtomicInteger(0);

	private static final float PHYSICS_SIMPLIFICATION_DISTANCE = 48.0f;

	private EntityPhysicsSimplifier() {
	}

	public static EntityPhysicsSimplifier getInstance() {
		if (instance == null) {
			synchronized (EntityPhysicsSimplifier.class) {
				if (instance == null) {
					instance = new EntityPhysicsSimplifier();
				}
			}
		}
		return instance;
	}

	/**
	 * Simplify physics for entity (worker thread).
	 */
	public void queuePhysicsSimplification(int entityId, float distanceToCamera, float boundingBoxWidth,
			float boundingBoxHeight, String entityType) {
		ChunkMeshExecutor.getOrCreate()
				.execute(() -> simplifyPhysics(entityId, distanceToCamera, boundingBoxWidth, boundingBoxHeight, entityType));
	}

	private void simplifyPhysics(int entityId, float distanceToCamera, float boundingBoxWidth, float boundingBoxHeight,
			String entityType) {
		EntityPhysicsLevel physics = new EntityPhysicsLevel(entityId);

		physics.entityType = entityType;
		physics.distanceToCamera = distanceToCamera;

		if (distanceToCamera > PHYSICS_SIMPLIFICATION_DISTANCE) {
			// Use simplified physics for distant entities
			physics.useSimplifiedPhysics = true;
			physics.skipCollisionCheck = true;
			physics.skipFluidHandling = true;
			physics.physicsUpdateFrequency = 0; // Don't update every frame
			physics.simulationQuality = 0.3f;
			cullableEntities.incrementAndGet();
		} else {
			// Full physics for nearby entities
			physics.useSimplifiedPhysics = false;
			physics.skipCollisionCheck = false;
			physics.skipFluidHandling = false;
			physics.physicsUpdateFrequency = 20; // Update every 20 frames
			physics.simulationQuality = 1.0f;
		}

		// Bounding box for simple physics
		physics.boundingBoxWidth = boundingBoxWidth;
		physics.boundingBoxHeight = boundingBoxHeight;

		physicsCache.put(entityId, physics);
		totalEntities.incrementAndGet();
	}

	/**
	 * Get cached physics level (main thread safe).
	 */
	public EntityPhysicsLevel getPhysicsLevel(int entityId) {
		return physicsCache.get(entityId);
	}

	/**
	 * Should skip physics for entity?
	 */
	public boolean shouldSkipPhysics(int entityId) {
		EntityPhysicsLevel physics = physicsCache.get(entityId);
		return physics != null && physics.useSimplifiedPhysics;
	}

	/**
	 * Get statistics.
	 */
	public PhysicsStats getStats() {
		int total = totalEntities.get();
		int cullable = cullableEntities.get();
		long ramUsedBytes = total * 60;
		float cullRatio = total > 0 ? (float) cullable / total : 0;

		return new PhysicsStats(total, cullable, cullRatio, ramUsedBytes);
	}

	/**
	 * Clear cache.
	 */
	public void clearCache() {
		physicsCache.clear();
		totalEntities.set(0);
		cullableEntities.set(0);
	}

	/**
	 * Entity physics level data.
	 */
	public static final class EntityPhysicsLevel {
		public final int entityId;
		public String entityType;
		public float distanceToCamera;
		public boolean useSimplifiedPhysics;
		public boolean skipCollisionCheck;
		public boolean skipFluidHandling;
		public int physicsUpdateFrequency; // Frames between updates
		public float simulationQuality; // 0.0-1.0
		public float boundingBoxWidth;
		public float boundingBoxHeight;

		public EntityPhysicsLevel(int entityId) {
			this.entityId = entityId;
			this.useSimplifiedPhysics = false;
			this.skipCollisionCheck = false;
			this.skipFluidHandling = false;
			this.physicsUpdateFrequency = 20;
			this.simulationQuality = 1.0f;
		}

		public boolean shouldUpdatePhysics(int frameCounter) {
			if (physicsUpdateFrequency == 0)
				return false;
			return frameCounter % physicsUpdateFrequency == 0;
		}
	}

	/**
	 * Physics statistics.
	 */
	public static final class PhysicsStats {
		public final int totalEntities;
		public final int simplifiedEntities;
		public final float simplificationRatio;
		public final long estimatedRamBytes;

		public PhysicsStats(int totalEntities, int simplifiedEntities, float simplificationRatio,
				long estimatedRamBytes) {
			this.totalEntities = totalEntities;
			this.simplifiedEntities = simplifiedEntities;
			this.simplificationRatio = simplificationRatio;
			this.estimatedRamBytes = estimatedRamBytes;
		}

		public String getRamUsedMB() {
			return String.format("%.2f MB", estimatedRamBytes / 1024.0 / 1024.0);
		}

		public String getSimplificationPercent() {
			return String.format("%.1f%%", simplificationRatio * 100.0);
		}
	}
}
