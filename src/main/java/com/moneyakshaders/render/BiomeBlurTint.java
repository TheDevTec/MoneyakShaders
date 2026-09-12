package com.moneyakshaders.render;

import java.util.IdentityHashMap;
import java.util.Map;

import com.moneyakshaders.MoneyakShadersConfig;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockRenderView;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.ColorResolver;
import net.minecraft.world.chunk.light.LightingProvider;

/**
 * Drop-in {@link BlockRenderView} that replaces vanilla's per-block biome-tint blend with a
 * distance-tiered one — the chunk-load lag spike traced to "biome blend on 15×15".
 *
 * <p>Vanilla {@code ClientWorld.calculateColor} box-averages {@code (2r+1)²} biome samples PER tinted
 * block (225 at radius 7) on a cold cache, so a freshly-loaded chunk pays it for every grass/leaf/water
 * block at once. Three things make that cheap here:
 *
 * <ol>
 * <li><b>Y-quart layer sharing (both tiers, exact).</b> Biomes live on a 4×4×4 lattice, so all four
 *     Y-layers of a quart resolve to the same biome. One grid per quart, not per layer: 16 grids per
 *     section become 4.</li>
 * <li><b>NEAR tier — detailed, vanilla-identical.</b> The box average is separable, so the raw
 *     per-(x,z) colour grid is built once per quart and run through a sliding-window blur: same sum,
 *     same single truncation as vanilla. On top of that the {@code getBiome} lookup is memoised per
 *     4×4 quart (it cannot vary inside one) while {@code resolver.getColor} still runs per block, so
 *     position-dependent tints (swamp grass noise) keep their detail. Biome lookups drop from
 *     {@code 225 × tintedBlocks} to ~81 per quart.</li>
 * <li><b>FAR tier — bilinear on the biome lattice.</b> Past {@link #FAR_CHUNKS} chunks a 15×15 box
 *     average is unresolvable: a whole quart is a fraction of a pixel of hue. Sample one colour per
 *     biome quart (6×6 covering the section plus a margin) and bilinearly interpolate instead. 36
 *     samples per quart rather than ~900, no window passes at all, and the gradient across a biome
 *     border comes out smoother than the box blur it replaces.</li>
 * </ol>
 *
 * <p>A section remembers which tier it was meshed with; the renderer re-meshes it once the player
 * crosses the hysteresis band, so walking towards distant terrain upgrades it to the detailed tier.
 * See {@link #isFarSection} / {@link #wantsRemesh}.
 *
 * <p>Only {@link #getColor} is special; every other call delegates to the wrapped world. Used purely
 * inside the mesher, single-threaded per instance, with shared per-thread scratch for the blur passes.
 */
public final class BiomeBlurTint implements BlockRenderView {
	/** Minimal immutable biome lookup used by SectionInputSnapshot on mesh workers. */
	interface BiomeAccess {
		Biome biomeAt(int x, int y, int z);
	}
	private static final int MAX_R = 7;
	private static final int MAX_SIZE = 16 + 2 * MAX_R; // 30

	/**
	 * Tier split, in chunks of horizontal distance from the player's chunk. {@link #FAR_CHUNKS} is
	 * where {@link #wantsRemesh} starts upgrading sections to the detailed tier and
	 * {@link #FAR_CHUNKS_OUT} where it lets them drop back — the gap is hysteresis, so a player
	 * pacing across the boundary can't re-mesh the same ring every step. A fresh mesh decides on the
	 * midpoint, so it lands inside the band and is stable whichever way the player then moves.
	 */
	private static final int FAR_CHUNKS = 6;
	private static final int FAR_CHUNKS_OUT = 8;
	private static final int FAR_CHUNKS_MESH = (FAR_CHUNKS + FAR_CHUNKS_OUT) / 2;

	// Per-thread scratch for the separable blur so meshing threads don't churn the heap.
	private static final ThreadLocal<int[]> RAW = ThreadLocal.withInitial(() -> new int[MAX_SIZE * MAX_SIZE * 3]);
	private static final ThreadLocal<int[]> HSUM = ThreadLocal.withInitial(() -> new int[16 * MAX_SIZE * 3]);
	// Quart-memoised biome lookups for the near tier: (MAX_SIZE/4 + 2)² covers any radius.
	private static final int MAX_QUARTS = MAX_SIZE / 4 + 2; // 9
	private static final ThreadLocal<Biome[]> QUART_BIOMES =
			ThreadLocal.withInitial(() -> new Biome[MAX_QUARTS * MAX_QUARTS]);
	// Far tier: the section's 4 quarts plus a one-quart margin on each side, per axis.
	private static final int FAR_LATTICE = 16 / 4 + 2; // 6
	private static final ThreadLocal<int[]> FAR_RGB =
			ThreadLocal.withInitial(() -> new int[FAR_LATTICE * FAR_LATTICE * 3]);

	/** Player chunk coords, published by the render thread each frame; worker threads read them. */
	private static volatile int viewerCx;
	private static volatile int viewerCz;

	private final BlockRenderView world;
	private final BiomeAccess biomeAccess;
	private final int baseX;
	private final int baseY;
	private final int baseZ;
	private final int radius;
	private final boolean far;
	// blended 16×16 ARGB grids, lazily computed per resolver per Y-QUART (4 layers) of this section.
	private final Map<ColorResolver, int[][]> cache = new IdentityHashMap<>();
	/** Detailed grids requested by visually sensitive materials such as large continuous water. */
	private final Map<ColorResolver, int[][]> detailedCache = new IdentityHashMap<>();

	private BiomeBlurTint(BlockRenderView world, BiomeAccess biomeAccess, int baseX, int baseY, int baseZ,
			int radius, boolean far) {
		this.world = world;
		this.biomeAccess = biomeAccess;
		this.baseX = baseX;
		this.baseY = baseY;
		this.baseZ = baseZ;
		this.radius = radius;
		this.far = far;
	}

	/** Render thread: publish the player's chunk so worker meshes can pick their tier. */
	public static void setViewerChunk(int chunkX, int chunkZ) {
		viewerCx = chunkX;
		viewerCz = chunkZ;
	}

	/** Chebyshev chunk distance from the player to the section starting at these block coords. */
	private static int chunkDist(int baseX, int baseZ) {
		return Math.max(Math.abs((baseX >> 4) - viewerCx), Math.abs((baseZ >> 4) - viewerCz));
	}

	/** Which tier a mesh built right now for this section would use. */
	public static boolean isFarSection(int baseX, int baseZ) {
		// The far approximation is an explicit opt-in. The previous implementation ignored the
		// fastBiomeBlend=false setting, so near-exact and far-bilinear vertex colours met on a moving
		// chunk ring and painted a visible 16x16 grid across grass/leaves. Water already forces the
		// detailed kernel; keep every tinted material coherent when the option is disabled.
		return com.moneyakshaders.MoneyakShadersConfig.get().fastBiomeBlend
				&& chunkDist(baseX, baseZ) > FAR_CHUNKS_MESH;
	}

	/**
	 * Whether a section meshed with tier {@code wasFar} has left its band far enough that it should
	 * be rebuilt at the other tier. Inside the hysteresis band this is always false.
	 */
	public static boolean wantsRemesh(int baseX, int baseZ, boolean wasFar) {
		// With the approximation disabled every mesh is already on the exact tier. Without this guard,
		// every section beyond FAR_CHUNKS_OUT was marked again after each idle drain, rebuilt as exact,
		// then immediately marked once more because wasFar necessarily remained false.
		if (!com.moneyakshaders.MoneyakShadersConfig.get().fastBiomeBlend) return false;
		int d = chunkDist(baseX, baseZ);
		return wasFar ? d < FAR_CHUNKS : d > FAR_CHUNKS_OUT;
	}

	/** Wrap {@code world} for fast tinting, or return it unchanged if biomes aren't reachable. */
	public static BlockRenderView wrap(BlockRenderView world, int baseX, int baseY, int baseZ) {
		MoneyakShadersConfig cfg = MoneyakShadersConfig.get();
		int r = Math.max(0, Math.min(MAX_R, cfg.biomeBlendRadius));

		if (world instanceof BiomeAccess snapshot)
			return wrapSnapshot(world, snapshot, baseX, baseY, baseZ);

		if (!(world instanceof World w)) return world;

		boolean far = r > 0 && isFarSection(baseX, baseZ);
		return new BiomeBlurTint(
				world,
				(x, y, z) -> w.getBiome(new BlockPos(x, y, z)).value(),
				baseX, baseY, baseZ, r, far);
	}

	private static BlockRenderView wrapSnapshot(BlockRenderView world, BiomeAccess access,
			int baseX, int baseY, int baseZ) {

		int r = Math.max(0, Math.min(
				MAX_R,
				MoneyakShadersConfig.get().biomeBlendRadius));

		boolean far = r > 0 && isFarSection(baseX, baseZ);
		return new BiomeBlurTint(world, access, baseX, baseY, baseZ, r, far);
	}

	@Override
	public int getColor(BlockPos pos, ColorResolver resolver) {
		return cachedColor(pos, resolver, far, cache);
	}

	/**
	 * Water must not switch between two different baked colour kernels on a viewer-centred ring.
	 * Keep the cheap far tier for grass/leaves, but let the fluid mesher request the exact near kernel
	 * at every distance.  A separate cache preserves the one-grid-per-quart cost on worker threads.
	 */
	static int getDetailedColor(BlockRenderView view, BlockPos pos, ColorResolver resolver) {
		if (view instanceof BiomeBlurTint tint) {
			return tint.cachedColor(pos, resolver, false, tint.detailedCache);
		}
		return view.getColor(pos, resolver);
	}

	private int cachedColor(BlockPos pos, ColorResolver resolver, boolean useFar,
			Map<ColorResolver, int[][]> targetCache) {

		int lx = pos.getX() - baseX;
		int ly = pos.getY() - baseY;
		int lz = pos.getZ() - baseZ;

		if (lx < 0 || lx > 15 || lz < 0 || lz > 15 || ly < 0 || ly > 15)
			return gradeBiomeColor(world.getColor(pos, resolver));

		int quart = ly >> 2;
		int[][] perQuart = targetCache.computeIfAbsent(resolver, ignored -> new int[4][]);

		int[] grid = perQuart[quart];
		if (grid == null) {
			int worldY = baseY + (quart << 2);
			grid = useFar ? computeGridFar(resolver, worldY) : computeGridNear(resolver, worldY);
			perQuart[quart] = grid;
		}

		return gradeBiomeColor(grid[lz * 16 + lx]);
	}

	private static int gradeBiomeColor(int rgb) {
		float strength = Math.max(0.70f, Math.min(
				1.35f,
				MoneyakShadersConfig.get().biomeTintVibrance / 100.0f));

		float r = ((rgb >> 16) & 0xFF) / 255.0f;
		float g = ((rgb >> 8) & 0xFF) / 255.0f;
		float b = (rgb & 0xFF) / 255.0f;

		float l = r * 0.2126f + g * 0.7152f + b * 0.0722f;

		r = l + (r - l) * strength;
		g = l + (g - l) * strength;
		b = l + (b - l) * strength;

		// Grass/leaves should stay rich, not neon. Compress only extreme tint peaks.
		float peak = Math.max(r, Math.max(g, b));
		if (peak > 0.92f) {
			float compress = 0.92f / peak;
			r = l + (r - l) * compress;
			g = l + (g - l) * compress;
			b = l + (b - l) * compress;
		}

		int ri = Math.max(0, Math.min(255, Math.round(r * 255.0f)));
		int gi = Math.max(0, Math.min(255, Math.round(g * 255.0f)));
		int bi = Math.max(0, Math.min(255, Math.round(b * 255.0f)));

		return ri << 16 | gi << 8 | bi;
	}

	/**
	 * NEAR tier: vanilla's box average, computed as a separable sliding-window blur over a raw grid
	 * whose {@code getBiome} calls are memoised per 4×4 quart. Bit-identical to vanilla's result.
	 */
	private int[] computeGridNear(ColorResolver resolver, int worldY) {
		int r = radius;
		int size = 16 + 2 * r;
		int win = (2 * r + 1) * (2 * r + 1);
		int[] raw = RAW.get();      // [idx*3 + c]
		int[] h = HSUM.get();       // [(gz*16 + lx)*3 + c]
		Biome[] biomes = QUART_BIOMES.get();
		BlockPos.Mutable m = new BlockPos.Mutable();

		// 1. raw biome colour at each cell of the (size×size) grid around the section. The biome is
		//    constant inside a 4×4 quart, so look it up once per quart; the resolver still runs per
		//    block so position-dependent tints (swamp grass noise) keep their detail.
		int originX = baseX - r;
		int originZ = baseZ - r;
		int qx0 = originX >> 2;
		int qz0 = originZ >> 2;
		int qCols = ((originX + size - 1) >> 2) - qx0 + 1;
		java.util.Arrays.fill(biomes, null);
		for (int gz = 0; gz < size; gz++) {
			int wz = originZ + gz;
			int qRow = ((wz >> 2) - qz0) * qCols;
			int rowBase = gz * size * 3;
			for (int gx = 0; gx < size; gx++) {
				int wx = originX + gx;
				int qi = qRow + ((wx >> 2) - qx0);
				Biome biome = biomes[qi];
				if (biome == null) {
					biome = biomeAccess.biomeAt(wx, worldY, wz);
					if (biome == null) return new int[256];
					biomes[qi] = biome;
				}
				int n = resolver.getColor(biome, wx, wz);
				int idx = rowBase + gx * 3;
				raw[idx] = (n >> 16) & 0xFF;
				raw[idx + 1] = (n >> 8) & 0xFF;
				raw[idx + 2] = n & 0xFF;
			}
		}

		// 2. horizontal sliding sum over the x-window → h[gz][lx] for lx 0..15.
		int span = 2 * r;
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

		// 3. vertical sliding sum over the z-window → final averaged grid.
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
				out[lz * 16 + lx] = ((sR / win) << 16) | ((sG / win) << 8) | (sB / win);
			}
		}
		return out;
	}

	/**
	 * FAR tier: one colour per biome quart on a 6×6 lattice (the section's 4 quarts plus a one-quart
	 * margin each side), then bilinear interpolation per block. 36 biome lookups for the whole quart
	 * instead of ~900, and no window passes — at this distance the exact averaging kernel is
	 * unresolvable; only the smooth gradient across a border reads.
	 */
	private int[] computeGridFar(ColorResolver resolver, int worldY) {
		int[] rgb = FAR_RGB.get();
		BlockPos.Mutable m = new BlockPos.Mutable();
		// Lattice origin: the quart holding block (baseX - 2), i.e. one quart before the section, so
		// every block in 0..15 has a sample on both sides of it.
		int qx0 = (baseX - 2) >> 2;
		int qz0 = (baseZ - 2) >> 2;
		for (int j = 0; j < FAR_LATTICE; j++) {
			int wz = ((qz0 + j) << 2) + 2; // quart centre
			for (int i = 0; i < FAR_LATTICE; i++) {
				int wx = ((qx0 + i) << 2) + 2;
				Biome biome = biomeAccess.biomeAt(wx, worldY, wz);
				if (biome == null) return new int[256];
				int n = resolver.getColor(biome, wx, wz);
				int idx = (j * FAR_LATTICE + i) * 3;
				rgb[idx] = (n >> 16) & 0xFF;
				rgb[idx + 1] = (n >> 8) & 0xFF;
				rgb[idx + 2] = n & 0xFF;
			}
		}

		// Per-axis lattice cell + weight. Quart q's sample sits at 4q+2, so block w interpolates
		// between quarts floor((w-2)/4) and that +1, with weight ((w-2) & 3) in quarters.
		int[] cellX = new int[16], wgtX = new int[16], cellZ = new int[16], wgtZ = new int[16];
		for (int l = 0; l < 16; l++) {
			int wx = baseX + l - 2;
			cellX[l] = (wx >> 2) - qx0;
			wgtX[l] = wx & 3;
			int wz = baseZ + l - 2;
			cellZ[l] = (wz >> 2) - qz0;
			wgtZ[l] = wz & 3;
		}

		int[] out = new int[256];
		for (int lz = 0; lz < 16; lz++) {
			int cz = cellZ[lz], fz = wgtZ[lz];
			for (int lx = 0; lx < 16; lx++) {
				int cx = cellX[lx], fx = wgtX[lx];
				int i00 = ((cz * FAR_LATTICE) + cx) * 3;
				int i10 = i00 + 3;
				int i01 = i00 + FAR_LATTICE * 3;
				int i11 = i01 + 3;
				// Weights are sixteenths (4×4); the +8 rounds instead of truncating.
				int w00 = (4 - fx) * (4 - fz), w10 = fx * (4 - fz);
				int w01 = (4 - fx) * fz, w11 = fx * fz;
				int cr = (rgb[i00] * w00 + rgb[i10] * w10 + rgb[i01] * w01 + rgb[i11] * w11 + 8) >> 4;
				int cg = (rgb[i00 + 1] * w00 + rgb[i10 + 1] * w10 + rgb[i01 + 1] * w01 + rgb[i11 + 1] * w11 + 8) >> 4;
				int cb = (rgb[i00 + 2] * w00 + rgb[i10 + 2] * w10 + rgb[i01 + 2] * w01 + rgb[i11 + 2] * w11 + 8) >> 4;
				out[lz * 16 + lx] = (cr << 16) | (cg << 8) | cb;
			}
		}
		return out;
	}

	// --- everything else delegates straight through ---
	@Override public float getBrightness(Direction direction, boolean shaded) { return world.getBrightness(direction, shaded); }
	@Override public LightingProvider getLightingProvider() { return world.getLightingProvider(); }
	@Override public BlockEntity getBlockEntity(BlockPos pos) { return world.getBlockEntity(pos); }
	@Override public BlockState getBlockState(BlockPos pos) { return world.getBlockState(pos); }
	@Override public FluidState getFluidState(BlockPos pos) { return world.getFluidState(pos); }
	@Override public int getHeight() { return world.getHeight(); }
	@Override public int getBottomY() { return world.getBottomY(); }
}
