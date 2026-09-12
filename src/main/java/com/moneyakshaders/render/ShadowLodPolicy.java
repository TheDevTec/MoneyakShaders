package com.moneyakshaders.render;

/**
 * Spec §11.3: pick an entity's shadow LOD from screen-space importance (bounds radius over
 * distance) rather than plain distance. Classifier only; consumed by upcoming phase 5 entity
 * shadow selection.
 */
public final class ShadowLodPolicy {
	public static final int LOD_NEAR = 0;
	public static final int LOD_MID  = 1;
	public static final int LOD_FAR  = 2;
	public static final int LOD_NONE = 3;

	// Full posed model geometry must cover normal interaction/viewing distance. The former 0.35 NEAR
	// threshold began deleting small cuboids beyond ~2 blocks for a regular 0.75-block entity, so an
	// armor stand beside the player already cast its reduced LOD. Keep every model part to ~19 blocks,
	// then shed only sub-pixel parts through ~50 blocks; FAR remains available to the explicit 128-block
	// shadow cap without replacing resource-pack silhouettes by an AABB.
	private static final float T_NEAR = 0.04f;
	private static final float T_MID  = 0.015f;
	private static final float T_FAR  = 0.006f;

	private ShadowLodPolicy() {
	}

	public static int classify(float distanceBlocks, float boundsRadius) {
		if (distanceBlocks <= 0.001f) return LOD_NEAR;
		float apparent = boundsRadius / distanceBlocks;
		if (apparent >= T_NEAR) return LOD_NEAR;
		if (apparent >= T_MID)  return LOD_MID;
		if (apparent >= T_FAR)  return LOD_FAR;
		return LOD_NONE;
	}

	/**
	 * Allocation-free/sqrt-free form for render hot paths that already have a squared distance.
	 * It deliberately compares the same apparent-size thresholds as {@link #classify(float, float)}.
	 */
	public static int classifySquared(double distanceSq, float boundsRadius) {
		if (distanceSq <= 0.000001d) return LOD_NEAR;
		double radiusSq = (double) boundsRadius * boundsRadius;
		if (distanceSq <= radiusSq / ((double) T_NEAR * T_NEAR)) return LOD_NEAR;
		if (distanceSq <= radiusSq / ((double) T_MID * T_MID)) return LOD_MID;
		if (distanceSq <= radiusSq / ((double) T_FAR * T_FAR)) return LOD_FAR;
		return LOD_NONE;
	}

	public static int classify(float dx, float dy, float dz, float boundsRadius) {
		return classifySquared((double) dx * dx + (double) dy * dy + (double) dz * dz, boundsRadius);
	}
}
