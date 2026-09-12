package com.moneyakshaders.render;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;

/**
 * Plan C / Phase 2.1b — {@link BlockGrid} over a real Minecraft world section.
 *
 * <p>Maps section-local coords (incl. the -1..16 border) to world block coords
 * and reports full-opaque-cube solidity via {@code BlockState.isOpaqueFullCube}.
 * Reads through {@link BlockView#getBlockState} for correctness/simplicity; the
 * optimised version (later) will read the section's {@code PalettedContainer}
 * directly and snapshot neighbours for off-thread meshing.
 *
 * <p>Single-threaded use only (reuses one mutable position).
 */
public final class WorldSectionGrid implements BlockGrid {
	private final BlockView world;
	private final int baseX;
	private final int baseY;
	private final int baseZ;
	private final BlockPos.Mutable pos = new BlockPos.Mutable();

	public WorldSectionGrid(BlockView world, int baseX, int baseY, int baseZ) {
		this.world = world;
		this.baseX = baseX;
		this.baseY = baseY;
		this.baseZ = baseZ;
	}

	@Override
	public boolean isSolidCube(int x, int y, int z) {
		BlockState state = world.getBlockState(pos.set(baseX + x, baseY + y, baseZ + z));
		return state.isOpaqueFullCube();
	}
}
