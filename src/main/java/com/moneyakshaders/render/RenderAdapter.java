package com.moneyakshaders.render;

/**
 * Spec §14: SPI for mod-compatibility adapters. Tiers: NATIVE (take mod's own mesh/pose),
 * CAPTURE (intercept vertex stream), PROXY (AABB/OBB/capsule fallback). Coordinator picks the
 * highest-fidelity adapter that returns true from {@link #supports(Object)}. Skeleton for
 * phase 6.
 */
public interface RenderAdapter {
	enum Tier { NATIVE, CAPTURE, PROXY }

	Tier tier();

	boolean supports(Object rendererOrEntity);

	RenderableInstance extract(Object source, float partialTicks);
}
