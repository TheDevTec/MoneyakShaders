package com.moneyakshaders.client;

/**
 * Master orchestrator for entity optimizations.
 * Coordinates LOD, physics simplification, and shadow caching.
 */
public final class EntityOptimizationCoordinator {
	private static volatile EntityOptimizationCoordinator instance;

	private final EntityLODOptimizer lodOptimizer;
	private final EntityPhysicsSimplifier physicsSimplifier;
	private final EntityShadowMapCache shadowMapCache;

	private EntityOptimizationCoordinator() {
		this.lodOptimizer = EntityLODOptimizer.getInstance();
		this.physicsSimplifier = EntityPhysicsSimplifier.getInstance();
		this.shadowMapCache = EntityShadowMapCache.getInstance();
	}

	public static EntityOptimizationCoordinator getInstance() {
		if (instance == null) {
			synchronized (EntityOptimizationCoordinator.class) {
				if (instance == null) {
					instance = new EntityOptimizationCoordinator();
				}
			}
		}
		return instance;
	}

	/**
	 * Optimize entity (all systems in parallel).
	 */
	public void optimizeEntity(int entityId, float distanceToCamera, String entityType, float boundingBoxWidth,
			float boundingBoxHeight) {
		// Compute LOD
		lodOptimizer.queueEntityLODComputation(entityId, distanceToCamera);

		// Simplify physics
		physicsSimplifier.queuePhysicsSimplification(entityId, distanceToCamera, boundingBoxWidth, boundingBoxHeight,
				entityType);

		// Pre-compute shadow (per entity type, not per instance)
		shadowMapCache.queueShadowMapGeneration(entityType, boundingBoxWidth, boundingBoxHeight);
	}

	/**
	 * Get optimized entity properties.
	 */
	public OptimizedEntityData getOptimizedEntity(int entityId, String entityType) {
		EntityLODOptimizer.EntityLODLevel lod = lodOptimizer.getLODLevel(entityId);
		EntityPhysicsSimplifier.EntityPhysicsLevel physics = physicsSimplifier.getPhysicsLevel(entityId);

		return new OptimizedEntityData(lod, physics);
	}

	/**
	 * Get unified statistics.
	 */
	public EntityOptStats getStats() {
		return new EntityOptStats(
				lodOptimizer.getStats(),
				physicsSimplifier.getStats(),
				shadowMapCache.getStats());
	}

	/**
	 * Clear all caches.
	 */
	public void clearAllCaches() {
		lodOptimizer.clearCache();
		physicsSimplifier.clearCache();
		shadowMapCache.clearCache();
	}

	/**
	 * Optimized entity data.
	 */
	public static final class OptimizedEntityData {
		public final EntityLODOptimizer.EntityLODLevel lod;
		public final EntityPhysicsSimplifier.EntityPhysicsLevel physics;

		public OptimizedEntityData(EntityLODOptimizer.EntityLODLevel lod,
				EntityPhysicsSimplifier.EntityPhysicsLevel physics) {
			this.lod = lod;
			this.physics = physics;
		}

		public boolean shouldRenderEntity() {
			return lod != null && lod.lodLevel < 4; // Skip rendering for very far entities
		}

		public boolean shouldUpdatePhysics(int frameCounter) {
			return physics != null && physics.shouldUpdatePhysics(frameCounter);
		}
	}

	/**
	 * Entity optimization statistics.
	 */
	public static final class EntityOptStats {
		public final EntityLODOptimizer.EntityStats lodStats;
		public final EntityPhysicsSimplifier.PhysicsStats physicsStats;
		public final EntityShadowMapCache.ShadowStats shadowStats;

		public EntityOptStats(EntityLODOptimizer.EntityStats lodStats,
				EntityPhysicsSimplifier.PhysicsStats physicsStats, EntityShadowMapCache.ShadowStats shadowStats) {
			this.lodStats = lodStats;
			this.physicsStats = physicsStats;
			this.shadowStats = shadowStats;
		}

		public String getTotalRamUsedMB() {
			long total = Long.parseLong(lodStats.getRamUsedMB().split(" ")[0].replace(",", ".").split("M")[0])
					+ Long.parseLong(
							physicsStats.estimatedRamBytes / 1024 / 1024 + "");
			return String.format("%.1f MB", (double) total);
		}

		public String getSummary() {
			return String.format(
					"Entity Optimization: %d entities LOD'd | %s physics simplified (%d entities) | %d entity type shadows cached",
					lodStats.cachedEntities, physicsStats.getSimplificationPercent(), physicsStats.simplifiedEntities,
					shadowStats.cachedEntityTypes);
		}
	}
}
