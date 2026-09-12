package com.moneyakshaders.render;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;

public final class PointLightRegistry {
	private static final float[] TORCH_WARM = { 1.00f, 0.66f, 0.30f };
	private static final float[] LANTERN_WARM = { 1.00f, 0.74f, 0.40f };
	private static final float[] CAMPFIRE_WARM = { 1.00f, 0.52f, 0.20f };
	private static final float[] MAGMA_WARM = { 1.00f, 0.42f, 0.15f };
	private static final float[] SOUL = { 0.34f, 0.68f, 1.00f };
	private static final float[] RED = { 1.00f, 0.18f, 0.10f };
	private static final float[] WHITE = { 0.94f, 0.96f, 1.00f };
	private static final float[] AQUA = { 0.40f, 0.82f, 1.00f };
	private static final float[] BEACON = { 0.38f, 0.68f, 1.00f };
	private static final float[] GOLDEN = { 1.00f, 0.72f, 0.34f };
	private static final float[] GREEN = { 0.55f, 1.00f, 0.58f };
	private static final float[] PINK = { 1.00f, 0.68f, 0.92f };
	private static final float[] LAVA = { 1.00f, 0.43f, 0.12f };
	private static final float[] DEFAULT_WARM = { 1.00f, 0.72f, 0.38f };

	private PointLightRegistry() {}

	public static float[] rgb(Block b) {
		if (b == Blocks.TORCH || b == Blocks.WALL_TORCH) return TORCH_WARM;
		if (b == Blocks.LANTERN) return LANTERN_WARM;
		if (b == Blocks.CAMPFIRE || b == Blocks.FIRE) return CAMPFIRE_WARM;
		if (b == Blocks.MAGMA_BLOCK) return MAGMA_WARM;
		if (b == Blocks.SOUL_TORCH || b == Blocks.SOUL_WALL_TORCH || b == Blocks.SOUL_LANTERN || b == Blocks.SOUL_CAMPFIRE || b == Blocks.SOUL_FIRE) return SOUL;
		if (b == Blocks.REDSTONE_TORCH || b == Blocks.REDSTONE_WALL_TORCH) return RED;
		if (b == Blocks.SEA_LANTERN || b == Blocks.SEA_PICKLE || b == Blocks.CONDUIT) return AQUA;
		if (b == Blocks.BEACON) return BEACON;
		if (b == Blocks.GLOWSTONE) return GOLDEN;
		if (b == Blocks.END_ROD) return WHITE;
		if (b == Blocks.SHROOMLIGHT) return LANTERN_WARM;
		if (b == Blocks.VERDANT_FROGLIGHT) return GREEN;
		if (b == Blocks.PEARLESCENT_FROGLIGHT) return PINK;
		if (b == Blocks.OCHRE_FROGLIGHT) return GOLDEN;
		if (b == Blocks.LAVA) return LAVA;
		return DEFAULT_WARM;
	}

	public static int intensity(Block b) {
		return Math.max(0, Math.min(15, b.getDefaultState().getLuminance()));
	}

	public static float intensityLinear(Block b) {
		float x = intensity(b) / 15f;
		return x * x;
	}

	public static int radius(Block b) {
		return Math.round(shadowRadius(b));
	}

	public static float shadowRadius(Block b) {
		int light = intensity(b);
		if (light <= 0) return 0f;
		float radius = 3.5f + light * 0.90f;
		if (b == Blocks.TORCH || b == Blocks.WALL_TORCH) radius *= 0.90f;
		else if (b == Blocks.LANTERN) radius *= 1.05f;
		else if (b == Blocks.CAMPFIRE || b == Blocks.SOUL_CAMPFIRE) radius *= 1.20f;
		else if (b == Blocks.LAVA || b == Blocks.GLOWSTONE || b == Blocks.SEA_LANTERN || b == Blocks.SHROOMLIGHT) radius *= 1.12f;
		else if (b == Blocks.BEACON) radius *= 1.35f;
		return Math.min(radius, 24f);
	}

	public static float specularContribution(Block b) {
		if (b == Blocks.LAVA || b == Blocks.FIRE || b == Blocks.SOUL_FIRE || b == Blocks.CAMPFIRE || b == Blocks.SOUL_CAMPFIRE) return 0.35f;
		if (b == Blocks.TORCH || b == Blocks.WALL_TORCH || b == Blocks.SOUL_TORCH || b == Blocks.SOUL_WALL_TORCH) return 0.55f;
		if (b == Blocks.LANTERN || b == Blocks.SOUL_LANTERN) return 0.70f;
		if (b == Blocks.SEA_LANTERN || b == Blocks.GLOWSTONE || b == Blocks.SHROOMLIGHT || b == Blocks.END_ROD) return 0.85f;
		if (b == Blocks.BEACON) return 1f;
		return 0.65f;
	}
}