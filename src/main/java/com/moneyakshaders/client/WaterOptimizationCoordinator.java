package com.moneyakshaders.client;

/**
 * Master orchestrator for water optimizations.
 * Coordinates wave pre-computation, caustics caching, and particle pooling.
 */
public final class WaterOptimizationCoordinator {
	private static volatile WaterOptimizationCoordinator instance;

	private final WaterWavePrecomputer wavePrecomputer;
	private final WaterCausticsCache causticsCache;
	private final WaterParticlePool particlePool;

	private WaterOptimizationCoordinator() {
		this.wavePrecomputer = WaterWavePrecomputer.getInstance();
		this.causticsCache = WaterCausticsCache.getInstance();
		this.particlePool = WaterParticlePool.getInstance();
	}

	public static WaterOptimizationCoordinator getInstance() {
		if (instance == null) {
			synchronized (WaterOptimizationCoordinator.class) {
				if (instance == null) {
					instance = new WaterOptimizationCoordinator();
				}
			}
		}
		return instance;
	}

	/**
	 * Initialize water optimization for region.
	 */
	public void initializeWaterRegion(String waterKey, int centerX, int centerZ, int causticsTextureId) {
		// Pre-compute waves
		wavePrecomputer.queueWavePrecomputation(waterKey, centerX, centerZ);

		// Generate caustics
		causticsCache.queueCausticsGeneration(causticsTextureId, waterKey.hashCode());
	}

	/**
	 * Acquire water particle for effect.
	 */
	public WaterParticlePool.WaterParticle createWaterParticle(float x, float y, float z,
			WaterParticlePool.ParticleType type) {
		return particlePool.acquireParticle(x, y, z, type);
	}

	/**
	 * Return water particle to pool.
	 */
	public void returnWaterParticle(WaterParticlePool.WaterParticle particle) {
		particlePool.releaseParticle(particle);
	}

	/**
	 * Get wave height for rendering.
	 */
	public float getWaveHeight(String waterKey, int x, int z, int frameCounter) {
		return wavePrecomputer.getWaveHeight(waterKey, x, z, frameCounter);
	}

	/**
	 * Get caustics texture for frame.
	 */
	public int[] getCausticsTexture(int textureId, int frameCounter) {
		return causticsCache.getCausticsFrame(textureId, frameCounter);
	}

	/**
	 * Get unified statistics.
	 */
	public WaterOptStats getStats() {
		return new WaterOptStats(
				wavePrecomputer.getStats(),
				causticsCache.getStats(),
				particlePool.getStats());
	}

	/**
	 * Clear all caches.
	 */
	public void clearAllCaches() {
		wavePrecomputer.clearCache();
		causticsCache.clearCache();
		particlePool.clearPool();
	}

	/**
	 * Water optimization statistics.
	 */
	public static final class WaterOptStats {
		public final WaterWavePrecomputer.WaveStats waveStats;
		public final WaterCausticsCache.CausticsStats causticsStats;
		public final WaterParticlePool.PoolStats particleStats;

		public WaterOptStats(WaterWavePrecomputer.WaveStats waveStats, WaterCausticsCache.CausticsStats causticsStats,
				WaterParticlePool.PoolStats particleStats) {
			this.waveStats = waveStats;
			this.causticsStats = causticsStats;
			this.particleStats = particleStats;
		}

		public String getTotalRamUsedMB() {
			long total = Long.parseLong(waveStats.getRamUsedMB().split(" ")[0].replace(",", "."))
					+ Long.parseLong(causticsStats.getRamUsedMB().split(" ")[0].replace(",", "."))
					+ Long.parseLong(particleStats.getRamUsedMB().split(" ")[0].replace(",", "."));
			return String.format("%.1f MB", (double) total);
		}

		public String getSummary() {
			return String.format(
					"Water Optimization: %d water regions with waves | %d caustics textures cached | %s particle pool (%d total)",
					waveStats.cachedWaterRegions, causticsStats.cachedTextures, particleStats.getPoolUtilization(),
					particleStats.totalAllocated);
		}
	}
}
