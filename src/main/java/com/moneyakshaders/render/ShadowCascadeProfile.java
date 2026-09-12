package com.moneyakshaders.render;

enum ShadowCascadeProfile {
	NEAR_SHARP(4, 0.65f, 0.70f, 12f),
	MID_BALANCED(8, 1.15f, 1.00f, 24f),
	FAR_SOFT(12, 1.85f, 1.25f, 48f);

	final int pcfSamples;
	final float filterRadius;
	final float normalBiasScale;
	final float casterMargin;

	ShadowCascadeProfile(int pcfSamples, float filterRadius, float normalBiasScale, float casterMargin) {
		this.pcfSamples = pcfSamples;
		this.filterRadius = filterRadius;
		this.normalBiasScale = normalBiasScale;
		this.casterMargin = casterMargin;
	}
}