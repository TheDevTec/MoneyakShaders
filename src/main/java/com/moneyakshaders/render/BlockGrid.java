package com.moneyakshaders.render;

/**
 * Plan C / Phase 2 — the seam between the mesher and Minecraft.
 *
 * <p>Abstracts read access to a 16³ chunk section plus its 1-block neighbour
 * border, so the meshing/culling algorithm ({@link SectionMesher}) can be
 * written and unit-tested without any Minecraft classes. The real
 * implementation (next step) wraps a {@code ClientWorld} section + the baked
 * model registry; tests use a synthetic grid.
 *
 * <p>Coordinates are section-local; valid range is {@code [-1, 16]} on each
 * axis (the section is {@code [0,16)}, plus one block of neighbour context for
 * face culling). Out-of-range positions a caller asks about are treated by the
 * implementation as whatever the neighbour section holds (air at the world
 * edge).
 */
public interface BlockGrid {
	int SIZE = 16;

	/** @return true if the block at this position is a full opaque cube (its touching neighbour faces are hidden). */
	boolean isSolidCube(int x, int y, int z);
}
