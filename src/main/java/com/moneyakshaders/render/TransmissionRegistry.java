package com.moneyakshaders.render;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.PlantBlock;
import net.minecraft.block.VineBlock;

public final class TransmissionRegistry {
	public static final class Transmission {
		public final float r, g, b, strength;
		public final boolean opaque;

		Transmission(float r, float g, float b, float strength, boolean opaque) {
			this.r = r;
			this.g = g;
			this.b = b;
			this.strength = strength;
			this.opaque = opaque;
		}
	}

	public static final Transmission OPAQUE = new Transmission(0f, 0f, 0f, 0f, true);
	public static final Transmission CLEAR = new Transmission(1f, 1f, 1f, 1f, false);
	public static final Transmission LEAVES = new Transmission(0.82f, 0.94f, 0.72f, 0.48f, false);
	public static final Transmission GRASS = new Transmission(0.88f, 0.96f, 0.78f, 0.68f, false);
	public static final Transmission WATER = new Transmission(0.72f, 0.88f, 1f, 0.64f, false);
	public static final Transmission ICE = new Transmission(0.80f, 0.93f, 1f, 0.72f, false);
	private static final Transmission GLASS = new Transmission(0.95f, 0.97f, 1f, 0.90f, false);
	private static final Transmission WHITE = t(0.95f, 0.95f, 0.95f);
	private static final Transmission ORANGE = t(1f, 0.60f, 0.20f);
	private static final Transmission MAGENTA = t(0.90f, 0.35f, 0.85f);
	private static final Transmission LIGHT_BLUE = t(0.45f, 0.75f, 1f);
	private static final Transmission YELLOW = t(1f, 0.90f, 0.30f);
	private static final Transmission LIME = t(0.60f, 1f, 0.30f);
	private static final Transmission PINK = t(1f, 0.65f, 0.80f);
	private static final Transmission GRAY = t(0.45f, 0.45f, 0.45f);
	private static final Transmission LIGHT_GRAY = t(0.75f, 0.75f, 0.75f);
	private static final Transmission CYAN = t(0.30f, 0.80f, 0.85f);
	private static final Transmission PURPLE = t(0.60f, 0.30f, 0.85f);
	private static final Transmission BLUE = t(0.25f, 0.35f, 0.90f);
	private static final Transmission BROWN = t(0.55f, 0.40f, 0.25f);
	private static final Transmission GREEN = t(0.35f, 0.75f, 0.30f);
	private static final Transmission RED = t(0.90f, 0.25f, 0.20f);
	private static final Transmission BLACK = t(0.15f, 0.15f, 0.15f);

	private TransmissionRegistry() {}

	private static Transmission t(float r, float g, float b) {
		return new Transmission(r, g, b, 0.78f, false);
	}

	public static Transmission of(BlockState state) {
		if (state == null || state.isAir()) return CLEAR;
		Block b = state.getBlock();
		if (b instanceof LeavesBlock) return LEAVES;
		if (b instanceof PlantBlock || b instanceof VineBlock) return GRASS;
		if (b == Blocks.WATER) return WATER;
		if (b == Blocks.ICE || b == Blocks.PACKED_ICE || b == Blocks.BLUE_ICE || b == Blocks.FROSTED_ICE) return ICE;
		if (b == Blocks.GLASS || b == Blocks.GLASS_PANE) return GLASS;
		if (b == Blocks.TINTED_GLASS) return BLACK;
		if (b == Blocks.WHITE_STAINED_GLASS || b == Blocks.WHITE_STAINED_GLASS_PANE) return WHITE;
		if (b == Blocks.ORANGE_STAINED_GLASS || b == Blocks.ORANGE_STAINED_GLASS_PANE) return ORANGE;
		if (b == Blocks.MAGENTA_STAINED_GLASS || b == Blocks.MAGENTA_STAINED_GLASS_PANE) return MAGENTA;
		if (b == Blocks.LIGHT_BLUE_STAINED_GLASS || b == Blocks.LIGHT_BLUE_STAINED_GLASS_PANE) return LIGHT_BLUE;
		if (b == Blocks.YELLOW_STAINED_GLASS || b == Blocks.YELLOW_STAINED_GLASS_PANE) return YELLOW;
		if (b == Blocks.LIME_STAINED_GLASS || b == Blocks.LIME_STAINED_GLASS_PANE) return LIME;
		if (b == Blocks.PINK_STAINED_GLASS || b == Blocks.PINK_STAINED_GLASS_PANE) return PINK;
		if (b == Blocks.GRAY_STAINED_GLASS || b == Blocks.GRAY_STAINED_GLASS_PANE) return GRAY;
		if (b == Blocks.LIGHT_GRAY_STAINED_GLASS || b == Blocks.LIGHT_GRAY_STAINED_GLASS_PANE) return LIGHT_GRAY;
		if (b == Blocks.CYAN_STAINED_GLASS || b == Blocks.CYAN_STAINED_GLASS_PANE) return CYAN;
		if (b == Blocks.PURPLE_STAINED_GLASS || b == Blocks.PURPLE_STAINED_GLASS_PANE) return PURPLE;
		if (b == Blocks.BLUE_STAINED_GLASS || b == Blocks.BLUE_STAINED_GLASS_PANE) return BLUE;
		if (b == Blocks.BROWN_STAINED_GLASS || b == Blocks.BROWN_STAINED_GLASS_PANE) return BROWN;
		if (b == Blocks.GREEN_STAINED_GLASS || b == Blocks.GREEN_STAINED_GLASS_PANE) return GREEN;
		if (b == Blocks.RED_STAINED_GLASS || b == Blocks.RED_STAINED_GLASS_PANE) return RED;
		if (b == Blocks.BLACK_STAINED_GLASS || b == Blocks.BLACK_STAINED_GLASS_PANE) return BLACK;
		return state.isOpaqueFullCube() ? OPAQUE : CLEAR;
	}

	public static boolean receivesDirectionalShadow(BlockState state) {
		if (state == null || state.isAir()) return false;
		Block b = state.getBlock();
		return b != Blocks.BARRIER && b != Blocks.STRUCTURE_VOID;
	}

	public static boolean isFoliageLike(BlockState state) {
		if (state == null || state.isAir()) return false;
		Block b = state.getBlock();
		return b instanceof LeavesBlock || b instanceof PlantBlock || b instanceof VineBlock;
	}

	public static float foliageTransmissionStrength(BlockState state) {
		if (state == null || state.isAir()) return 0f;
		Block b = state.getBlock();
		if (b instanceof LeavesBlock) return 0.18f;
		if (b instanceof VineBlock) return 0.22f;
		if (b instanceof PlantBlock) return 0.28f;
		return 0f;
	}
}