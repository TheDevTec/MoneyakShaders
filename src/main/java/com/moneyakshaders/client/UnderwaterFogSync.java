package com.moneyakshaders.client;

/**
 * Bridges the experimental renderer's underwater fog to VANILLA's fog pipeline. Terrain uses our own
 * fog (clear up close, ramping to ~96 blocks as eyes adjust), but entities + block entities are still
 * fogged by vanilla's dense water fog (end ≈ 15-30 blocks, dark blue) — so a cow or chest a few
 * blocks away turned into a dark-blue silhouette/box while the terrain behind it stayed clear.
 * The renderer publishes its current underwater fog here each frame; a WaterFogModifier mixin makes
 * vanilla use the SAME start/end/colour, so everything under water is fogged consistently.
 */
public final class UnderwaterFogSync {
	private static volatile boolean active;
	private static volatile float start, end;
	private static volatile float r, g, b;

	private UnderwaterFogSync() {
	}

	public static void set(float fogStart, float fogEnd, float cr, float cg, float cb) {
		start = fogStart;
		end = fogEnd;
		r = cr;
		g = cg;
		b = cb;
		active = true;
	}

	public static void clear() {
		active = false;
	}

	public static boolean isActive() {
		return active;
	}

	public static float start() {
		return start;
	}

	public static float end() {
		return end;
	}

	public static float red() { return r; }
	public static float green() { return g; }
	public static float blue() { return b; }

	/** Packed ARGB of the terrain's underwater fog colour. */
	public static int colorArgb() {
		int ri = Math.min(255, (int) (r * 255f + 0.5f));
		int gi = Math.min(255, (int) (g * 255f + 0.5f));
		int bi = Math.min(255, (int) (b * 255f + 0.5f));
		return 0xFF000000 | (ri << 16) | (gi << 8) | bi;
	}
}
