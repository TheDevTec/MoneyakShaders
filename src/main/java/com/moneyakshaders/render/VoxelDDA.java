package com.moneyakshaders.render;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockRenderView;

/**
 * Spec §9.2: voxel-DDA raycaster used by shadow and light-transport code. Walks integer block
 * cells along a ray, consulting {@link MaterialRegistry#of} at each step to accumulate
 * transmission. Terminates on opaque hit, early-out threshold, or step count. No callers yet;
 * used by upcoming phase 4 shadow-raycast wire-up.
 */
public final class VoxelDDA {
	public static final class Result {
		public final boolean hit;
		public final float r, g, b;
		public final int steps;

		Result(boolean hit, float r, float g, float b, int steps) {
			this.hit = hit; this.r = r; this.g = g; this.b = b; this.steps = steps;
		}
	}

	private static final Result CLEAR = new Result(false, 1f, 1f, 1f, 0);

	private VoxelDDA() {
	}

	public static Result cast(BlockRenderView world, double ox, double oy, double oz,
			double dx, double dy, double dz, int maxSteps, float earlyOutThreshold) {
		double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (len < 1e-6) return CLEAR;
		dx /= len; dy /= len; dz /= len;

		int x = fastFloor(ox), y = fastFloor(oy), z = fastFloor(oz);
		int stepX = dx > 0 ? 1 : -1;
		int stepY = dy > 0 ? 1 : -1;
		int stepZ = dz > 0 ? 1 : -1;

		double tDeltaX = Math.abs(dx) < 1e-9 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dx);
		double tDeltaY = Math.abs(dy) < 1e-9 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dy);
		double tDeltaZ = Math.abs(dz) < 1e-9 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dz);

		double tMaxX = firstCrossing(ox, dx, x, stepX);
		double tMaxY = firstCrossing(oy, dy, y, stepY);
		double tMaxZ = firstCrossing(oz, dz, z, stepZ);

		float r = 1f, g = 1f, bAcc = 1f;
		BlockPos.Mutable m = new BlockPos.Mutable();
		int steps = 0;
		while (steps < maxSteps) {
			m.set(x, y, z);
			MaterialRegistry.Material mat = MaterialRegistry.of(world.getBlockState(m));
			TransmissionRegistry.Transmission tx = mat.transmission;
			if (tx.opaque) {
				return new Result(true, r, g, bAcc, steps);
			}
			if (tx.strength < 0.999f) {
				float k = tx.strength;
				r *= tx.r * k + (1 - k);
				g *= tx.g * k + (1 - k);
				bAcc *= tx.b * k + (1 - k);
				if (r + g + bAcc < earlyOutThreshold) {
					return new Result(true, r, g, bAcc, steps);
				}
			}
			if (tMaxX < tMaxY && tMaxX < tMaxZ) {
				x += stepX; tMaxX += tDeltaX;
			} else if (tMaxY < tMaxZ) {
				y += stepY; tMaxY += tDeltaY;
			} else {
				z += stepZ; tMaxZ += tDeltaZ;
			}
			steps++;
		}
		return new Result(false, r, g, bAcc, steps);
	}

	private static int fastFloor(double v) {
		int i = (int) v;
		return v < i ? i - 1 : i;
	}

	private static double firstCrossing(double origin, double dir, int cell, int step) {
		if (Math.abs(dir) < 1e-9) return Double.POSITIVE_INFINITY;
		double boundary = step > 0 ? (cell + 1) : cell;
		return (boundary - origin) / dir;
	}
}
