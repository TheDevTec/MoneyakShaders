package com.moneyakshaders.render;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;

/**
 * Spec §6: single material lookup that unifies every per-block-state classification the renderer
 * needs. Callers ask one question ({@link #of(BlockState)}) and get an immutable {@link Material}
 * with the alpha mode, shadow policy, emission colour and transmission colour, so meshers,
 * shadow passes, cluster assignment and post-processing all agree.
 *
 * <p>Facade over {@link PointLightRegistry} (emission RGB + intensity) and
 * {@link TransmissionRegistry} (glass transmission). Shadow policy is derived from vanilla
 * BlockState flags. No callers yet; consumed by upcoming phase 4 wire-ups.
 */
public final class MaterialRegistry {
	public enum AlphaMode { OPAQUE, CUTOUT, TRANSLUCENT, EMISSIVE, EMPTY }

	public static final class Material {
		public final AlphaMode alpha;
		public final boolean castsSunShadow;
		public final boolean castsLocalShadow;
		public final boolean receivesShadow;
		public final float[] emission;
		public final int intensity;
		public final TransmissionRegistry.Transmission transmission;

		Material(AlphaMode alpha, boolean castsSun, boolean castsLocal, boolean receives,
				float[] emission, int intensity, TransmissionRegistry.Transmission tx) {
			this.alpha = alpha;
			this.castsSunShadow = castsSun;
			this.castsLocalShadow = castsLocal;
			this.receivesShadow = receives;
			this.emission = emission;
			this.intensity = intensity;
			this.transmission = tx;
		}

		public boolean emits() { return intensity > 0 && emission != null; }
	}

	private static final Material AIR = new Material(AlphaMode.EMPTY, false, false, false,
			null, 0, TransmissionRegistry.CLEAR);

	private MaterialRegistry() {
	}

	public static Material of(BlockState state) {
		if (state == null || state.isAir()) return AIR;
		Block b = state.getBlock();

		int lum = state.getLuminance();
		float[] emission = lum > 0 ? PointLightRegistry.rgb(b) : null;
		TransmissionRegistry.Transmission tx = TransmissionRegistry.of(state);

		AlphaMode alpha;
		if (tx.opaque)                          alpha = lum > 0 ? AlphaMode.EMISSIVE : AlphaMode.OPAQUE;
		else if (!tx.opaque && tx.strength < 1) alpha = AlphaMode.TRANSLUCENT;
		else                                     alpha = AlphaMode.CUTOUT;

		boolean castsSun   = tx.opaque || alpha == AlphaMode.CUTOUT || alpha == AlphaMode.EMISSIVE;
		boolean castsLocal = castsSun;
		boolean receives   = alpha != AlphaMode.EMPTY;

		if (alpha == AlphaMode.CUTOUT && !state.isOpaqueFullCube()) {
			castsSun = castsLocal = false;
		}

		if (b == Blocks.WATER) {
			castsSun = castsLocal = false;
			alpha = AlphaMode.TRANSLUCENT;
		}

		Material base = new Material(alpha, castsSun, castsLocal, receives, emission, lum, tx);
		return RenderMaterialOverrides.apply(Registries.BLOCK.getId(b), base);
	}
}
