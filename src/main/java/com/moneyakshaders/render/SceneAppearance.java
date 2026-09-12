package com.moneyakshaders.render;

/** Fade-in helper for newly resident terrain. Does not represent fog or atmospheric perspective. */
final class SceneAppearance {
	static final long APPEAR_DURATION_NS = 180_000_000L;
	static final float NEAR_FULL_VISIBILITY_DISTANCE = 32f;
	static final float FADE_DISTANCE = 32f;

	private SceneAppearance() {}

	static float visibility(long ageNs, double distanceSquared) {
		if (ageNs >= APPEAR_DURATION_NS || distanceSquared <= NEAR_FULL_VISIBILITY_DISTANCE * NEAR_FULL_VISIBILITY_DISTANCE) return 1f;

		double distance = Math.sqrt(Math.max(0.0, distanceSquared));
		float distanceFade = smoothstep((float)((distance - NEAR_FULL_VISIBILITY_DISTANCE) / FADE_DISTANCE));
		float ageFade = smoothstep((float)(Math.max(0L, ageNs) / (double)APPEAR_DURATION_NS));
		return 1f - distanceFade * (1f - ageFade);
	}

	private static float smoothstep(float value) {
		float t = clamp01(value);
		return t * t * (3f - 2f * t);
	}

	private static float clamp01(float value) {
		return Math.max(0f, Math.min(1f, value));
	}
}