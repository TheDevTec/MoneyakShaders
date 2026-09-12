package com.moneyakshaders.render;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Spec §13.4: monotonically increasing resource generation. Every subsystem that caches anything
 * derived from resource-pack data captures {@link #current()} at build time and compares back via
 * {@link #isCurrent} before reusing. Reload coordinator calls {@link #bump()} at a frame boundary
 * so every stale cache invalidates atomically. Skeleton for phase 6 wire-up.
 */
public final class ResourceGeneration {
	private static final AtomicLong GEN = new AtomicLong(1L);

	private ResourceGeneration() {
	}

	public static long current() {
		return GEN.get();
	}

	/**
	 * Atomically advances the visible resource generation and invalidates render-thread caches that
	 * store resource-derived geometry. The terrain renderer deliberately remeshes incrementally after
	 * this call, preserving its current mesh until a replacement is ready instead of freezing on a
	 * full-world rebuild.
	 */
	public static long bump() {
		long next = GEN.incrementAndGet();
		com.moneyakshaders.client.EntityTextureLodCache.clear();
		// These are resource-derived too. They used to rely on a separate resource-manager constructor
		// hook, which left a lifecycle gap for reload paths that rebuild WorldRenderer state without
		// rebuilding that manager in the same order (notably server-pack replacement).
		com.moneyakshaders.client.FastBiomeBlend.clearAll();
		com.moneyakshaders.client.etf.EtfEngine.clearCaches();
		com.moneyakshaders.client.etf.CitEngine.clearCaches();
		RenderMaterialOverrides.reload();
		BakedBlockEntities.clearAll();
		StaticEntityGeometryCache.clearAll();
		com.moneyakshaders.client.EntityLODOptimizer.getInstance().clearCache();
		ExperimentalSectionRender.invalidateResourceDependentData();
		return next;
	}

	public static boolean isCurrent(long captured) {
		return captured == GEN.get();
	}
}
