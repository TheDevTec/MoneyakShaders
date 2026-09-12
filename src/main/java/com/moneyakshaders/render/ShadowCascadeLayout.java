package com.moneyakshaders.render;

import com.moneyakshaders.MoneyakShadersConfig;

final class ShadowCascadeLayout {
	static final int CASCADE_COUNT = 3;
	static final int NEAR = 0, MID = 1, FAR = 2;

	final float[] radiusBlocks = new float[CASCADE_COUNT];
	final float[] depthHalf = new float[CASCADE_COUNT];
	final int[] resolution = new int[CASCADE_COUNT];
	final float[] blendStart = new float[CASCADE_COUNT];
	final float[] blendEnd = new float[CASCADE_COUNT];
	final float[] normalBias = new float[CASCADE_COUNT];
	final float[] constantBias = new float[CASCADE_COUNT];
	final ShadowCascadeProfile[] profile = {
			ShadowCascadeProfile.NEAR_SHARP,
			ShadowCascadeProfile.MID_BALANCED,
			ShadowCascadeProfile.FAR_SOFT
	};

	void update(MoneyakShadersConfig cfg, int adaptiveLevel) {
		radiusBlocks[NEAR] = cfg.shadowNearChunks * 16f;
		radiusBlocks[MID] = Math.max(radiusBlocks[NEAR] + 16f, cfg.shadowMidChunks * 16f);
		radiusBlocks[FAR] = Math.max(radiusBlocks[MID] + 16f, cfg.shadowDistanceChunks * 16f);

		int legacy = Math.max(1024, cfg.shadowResolution);
		int nearRes = cfg.shadowNearResolution > 0 ? cfg.shadowNearResolution : legacy;
		int midRes = cfg.shadowMidResolution > 0 ? cfg.shadowMidResolution : Math.max(1024, (nearRes + cfg.shadowFarResolution) >> 1);
		int farRes = cfg.shadowFarResolution > 0 ? cfg.shadowFarResolution : Math.max(1024, nearRes >> 1);
		int floor = Math.max(512, cfg.shadowMinResolution);

		resolution[NEAR] = AdaptiveShadowQuality.resolutionAtLevel(nearRes, floor, adaptiveLevel);
		resolution[MID] = AdaptiveShadowQuality.resolutionAtLevel(midRes, floor, adaptiveLevel);
		resolution[FAR] = AdaptiveShadowQuality.resolutionAtLevel(farRes, floor, adaptiveLevel);

		float baseBias = clamp(cfg.shadowBias / 6000f, 0.00025f, 0.02f);
		for (int i = 0; i < CASCADE_COUNT; i++) {
			float texel = texelSize(i);
			depthHalf[i] = radiusBlocks[i] * 1.20f + profile[i].casterMargin;
			constantBias[i] = Math.max(baseBias, texel * 0.035f);
			normalBias[i] = Math.max(baseBias * 1.5f, texel * 0.18f * profile[i].normalBiasScale);
		}

		computeBlendRanges();
	}

	private void computeBlendRanges() {
		float nearWidth = Math.max(4f, radiusBlocks[NEAR] * 0.12f);
		float midWidth = Math.max(6f, radiusBlocks[MID] * 0.10f);

		blendStart[NEAR] = radiusBlocks[NEAR] - nearWidth;
		blendEnd[NEAR] = radiusBlocks[NEAR];
		blendStart[MID] = radiusBlocks[MID] - midWidth;
		blendEnd[MID] = radiusBlocks[MID];
		blendStart[FAR] = radiusBlocks[FAR];
		blendEnd[FAR] = radiusBlocks[FAR];
	}

	float texelSize(int idx) {
		return radiusBlocks[idx] * 2f / Math.max(1, resolution[idx]);
	}

	float transition(int idx, float distance) {
		if (idx < NEAR || idx >= FAR) return 0f;
		float start = blendStart[idx], end = blendEnd[idx];
		if (end <= start) return distance >= end ? 1f : 0f;
		float t = clamp((distance - start) / (end - start), 0f, 1f);
		return t * t * (3f - 2f * t);
	}

	private static float clamp(float value, float min, float max) {
		return Math.max(min, Math.min(max, value));
	}
}