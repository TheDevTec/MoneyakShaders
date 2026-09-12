package com.moneyakshaders.client;

import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.ColorResolver;

/**
 * Global replacement for vanilla's biome-tint blend ({@code ClientWorld.calculateColor}). Vanilla
 * box-averages {@code (2r+1)²} biome samples PER position (225 at radius 7) on every colour-cache
 * miss — the confirmed chunk-load spike. A box average is separable, so this computes a whole 16×16
 * chunk layer with one sliding-window blur (bit-identical result: same sum, same single truncation)
 * and caches the layer, cutting biome lookups per layer from ~57k to ~900.
 *
 * <p>Uses the vanilla biome-blend-radius option value; vanilla's own algorithm simply never runs
 * (mixin cancels {@code calculateColor}). Layers are invalidated with the same granularity vanilla
 * invalidates its colour cache ({@code resetChunkColor} / {@code reloadColor}), plus the chunk's
 * neighbours since the blur window crosses chunk borders. Thread-safe (mesh workers + render thread).
 */
public final class FastBiomeBlend {
	private static final int MAX_R = 7;
	// (chunkX,chunkZ,y,resolverId) → blended 16×16 ARGB layer. Bounded by CLEAR_AT (safety valve).
	private static final ConcurrentHashMap<Long, int[]> LAYERS = new ConcurrentHashMap<>();
	private static final int CLEAR_AT = 40_000; // ~40k layers ≈ a few hundred MB worst case guard
	// Per-thread scratch for the separable blur (mesh workers + render thread share the class).
	private static final ThreadLocal<int[]> RAW = ThreadLocal.withInitial(() -> new int[(16 + 2 * MAX_R) * (16 + 2 * MAX_R) * 3]);
	private static final ThreadLocal<int[]> HSUM = ThreadLocal.withInitial(() -> new int[16 * (16 + 2 * MAX_R) * 3]);

	private FastBiomeBlend() {
	}

	public static int getColor(ClientWorld world, BlockPos pos, ColorResolver resolver) {
		int r = Math.max(0, Math.min(MAX_R,
				MinecraftClient.getInstance().options.getBiomeBlendRadius().getValue()));
		if (r == 0) {
			return resolver.getColor(world.getBiome(pos).value(), pos.getX(), pos.getZ());
		}
		int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
		long key = key(cx, cz, pos.getY(), resolver);
		int[] grid = LAYERS.get(key);
		if (grid == null) {
			if (LAYERS.size() > CLEAR_AT) {
				LAYERS.clear();
			}
			grid = computeLayer(world, resolver, cx << 4, pos.getY(), cz << 4, r);
			LAYERS.put(key, grid);
		}
		return grid[((pos.getZ() & 15) << 4) | (pos.getX() & 15)];
	}

	/** Vanilla invalidated this chunk's colours — drop our layers for it AND its neighbours (the blur
	 *  window reads across chunk borders, so a biome change here affects their blended colours too). */
	public static void invalidateChunk(int chunkX, int chunkZ) {
		if (LAYERS.isEmpty()) {
			return;
		}
		LAYERS.keySet().removeIf(k -> {
			int kx = (int) (k >> 40);
			int kz = (int) ((k << 24) >> 40); // sign-extend the middle 24 bits
			return Math.abs(kx - chunkX) <= 1 && Math.abs(kz - chunkZ) <= 1;
		});
	}

	public static void clearAll() {
		LAYERS.clear();
	}

	private static long key(int cx, int cz, int y, ColorResolver resolver) {
		// resolver id: the 3 vanilla resolvers are singletons — identityHashCode folded to 6 bits is
		// stable per run and collision-safe enough combined with the coordinate bits.
		long rid = System.identityHashCode(resolver) & 0x3F;
		return ((long) (cx & 0xFFFFFF) << 40) | ((long) (cz & 0xFFFFFF) << 16) | ((long) (y & 0x3FF) << 6) | rid;
	}

	/** One 16×16 blended layer via the separable sliding box blur — matches vanilla's average exactly. */
	private static int[] computeLayer(ClientWorld world, ColorResolver resolver, int baseX, int y, int baseZ, int r) {
		int size = 16 + 2 * r;
		int win = (2 * r + 1) * (2 * r + 1);
		int span = 2 * r;
		int[] raw = RAW.get();
		int[] h = HSUM.get();
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (int gz = 0; gz < size; gz++) {
			int wz = baseZ - r + gz;
			for (int gx = 0; gx < size; gx++) {
				int wx = baseX - r + gx;
				int n = resolver.getColor(world.getBiome(m.set(wx, y, wz)).value(), wx, wz);
				int idx = (gz * size + gx) * 3;
				raw[idx] = (n >> 16) & 0xFF;
				raw[idx + 1] = (n >> 8) & 0xFF;
				raw[idx + 2] = n & 0xFF;
			}
		}
		// Always blur heterogeneous windows. The old custom-map shortcut returned raw local
		// colours whenever it found more than one biome colour, effectively disabling biome blend
		// exactly at authored biome borders and producing chunk-shaped tint plates.
		for (int gz = 0; gz < size; gz++) {
			int rowBase = gz * size * 3;
			int sR = 0, sG = 0, sB = 0;
			for (int gx = 0; gx <= span; gx++) {
				int idx = rowBase + gx * 3;
				sR += raw[idx]; sG += raw[idx + 1]; sB += raw[idx + 2];
			}
			int ho = (gz * 16) * 3;
			h[ho] = sR; h[ho + 1] = sG; h[ho + 2] = sB;
			for (int lx = 1; lx < 16; lx++) {
				int rem = rowBase + (lx - 1) * 3;
				int add = rowBase + (lx + span) * 3;
				sR += raw[add] - raw[rem];
				sG += raw[add + 1] - raw[rem + 1];
				sB += raw[add + 2] - raw[rem + 2];
				int o = (gz * 16 + lx) * 3;
				h[o] = sR; h[o + 1] = sG; h[o + 2] = sB;
			}
		}
		int[] out = new int[256];
		for (int lx = 0; lx < 16; lx++) {
			int sR = 0, sG = 0, sB = 0;
			for (int gz = 0; gz <= span; gz++) {
				int idx = (gz * 16 + lx) * 3;
				sR += h[idx]; sG += h[idx + 1]; sB += h[idx + 2];
			}
			out[lx] = ((sR / win) << 16) | ((sG / win) << 8) | (sB / win);
			for (int lz = 1; lz < 16; lz++) {
				int rem = ((lz - 1) * 16 + lx) * 3;
				int add = ((lz + span) * 16 + lx) * 3;
				sR += h[add] - h[rem];
				sG += h[add + 1] - h[rem + 1];
				sB += h[add + 2] - h[rem + 2];
				out[(lz << 4) | lx] = ((sR / win) << 16) | ((sG / win) << 8) | (sB / win);
			}
		}
		return out;
	}
}
