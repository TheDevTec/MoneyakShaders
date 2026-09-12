package com.moneyakshaders.render;

import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockRenderView;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.ColorResolver;
import net.minecraft.world.chunk.light.LightingProvider;

/**
 * Immutable worker input for one section mesh. Extraction happens incrementally on the client
 * thread; meshing never reads a mutable {@code ClientWorld}. The block halo covers AO, fluids,
 * leaf look-ahead and plant roots. Biomes use their own wider horizontal halo for the blend kernel.
 */
final class SectionInputSnapshot implements BlockRenderView, BiomeBlurTint.BiomeAccess {
	private static final ConcurrentLinkedQueue<SectionInputSnapshot> POOL = new ConcurrentLinkedQueue<>();
	private static final AtomicInteger POOLED = new AtomicInteger();
	static final int HORIZONTAL_HALO = 2;
	// Meshing needs one cell below for faces/AO; the remaining three preserve short plant-root sway.
	// A 16-cell tail only improved the cosmetic anchor of unusually tall plants while adding 4,800
	// block/light captures to every multiplayer section snapshot.
	static final int BELOW_HALO = 4;
	static final int ABOVE_HALO = 4;
	static final int BLOCK_WIDTH = 16 + HORIZONTAL_HALO * 2;
	static final int BLOCK_HEIGHT = 16 + BELOW_HALO + ABOVE_HALO;
	static final int BIOME_HALO = 7;
	/** Number of quart-biome cells intersecting [base-7, base+22]. The previous snapshot stored one
	 * biome reference per BLOCK coordinate (30x30x5), although the chunk palette changes only every
	 * four blocks. Keeping the native quart grid preserves the exact blend halo with 320 instead of
	 * 4,500 references and palette reads per terrain section. */
	static final int BIOME_QUART_WIDTH = 8;
	// Vanilla colours the UPPER half of tall grass/large fern from pos.down(). When that plant crosses
	// a 16-block section boundary, the lookup belongs to the quart immediately below this snapshot.
	// Capture that one extra quart instead of returning white or reading mutable ClientWorld on a worker.
	static final int BIOME_QUART_BELOW = 1;
	static final int BIOME_QUARTS = 5;
	private static final int INITIAL_LIGHT_SOURCES = 128;

	int baseX, baseY, baseZ;
	int bottomY, height;
	/** Custom-renderer light revision captured with this immutable input. */
	long renderLightGeneration;
	/** 3×3 bitset of chunk columns absent when this input captured its block/biome halo. */
	int missingColumnMask;
	final BlockState[] states = new BlockState[BLOCK_WIDTH * BLOCK_WIDTH * BLOCK_HEIGHT];
	final int[] light = new int[states.length];
	final Biome[] biomes = new Biome[BIOME_QUART_WIDTH * BIOME_QUART_WIDTH * BIOME_QUARTS];
	float[] lightSources = new float[INITIAL_LIGHT_SOURCES * 7];
	int lightSourceCount;
	private final AtomicBoolean leased = new AtomicBoolean(true);

	SectionInputSnapshot(int baseX, int baseY, int baseZ, int bottomY, int height) {
		reset(baseX, baseY, baseZ, bottomY, height);
	}

	static SectionInputSnapshot acquire(int baseX, int baseY, int baseZ, int bottomY, int height) {
		SectionInputSnapshot snapshot = POOL.poll();
		if (snapshot != null) {
			POOLED.decrementAndGet();
			snapshot.leased.set(true);
			snapshot.reset(baseX, baseY, baseZ, bottomY, height);
			return snapshot;
		}
		return new SectionInputSnapshot(baseX, baseY, baseZ, bottomY, height);
	}

	static int pooledCount() {
		return POOLED.get();
	}

	void recycle() {
		if (!leased.compareAndSet(true, false)) return;
		Arrays.fill(states, null);
		Arrays.fill(biomes, null);
		lightSourceCount = 0;
		int limit = Math.max(0, com.moneyakshaders.MoneyakShadersConfig.get().maxPooledSectionSnapshots);
		if (POOLED.getAndIncrement() < limit) {
			POOL.offer(this);
		} else {
			POOLED.decrementAndGet();
		}
	}

	private void reset(int baseX, int baseY, int baseZ, int bottomY, int height) {
		this.baseX = baseX; this.baseY = baseY; this.baseZ = baseZ;
		this.bottomY = bottomY; this.height = height; this.lightSourceCount = 0;
		this.renderLightGeneration = -1L;
		this.missingColumnMask = 0;
	}

	int blockCount() { return states.length; }

	void setBlock(int index, BlockState state, int packedLight) {
		states[index] = state;
		light[index] = packedLight;
	}

	void setBiome(int quartX, int quartY, int quartZ, Biome biome) {
		biomes[(quartY * BIOME_QUART_WIDTH + quartZ) * BIOME_QUART_WIDTH + quartX] = biome;
	}

	void ensureLightSourceCapacity(int sourceCount) {
		int floats = sourceCount * 7;
		if (floats <= lightSources.length) return;
		int grown = Math.max(floats, lightSources.length + (lightSources.length >> 1));
		lightSources = Arrays.copyOf(lightSources, grown);
	}

	int packedLight(BlockPos pos) {
		int index = blockIndex(pos.getX(), pos.getY(), pos.getZ());
		return index < 0 ? 0 : light[index];
	}

	/**
	 * Whether the chunk column containing this world-space X/Z was absent when the immutable halo
	 * was captured.  Meshing unknown fluid neighbours as AIR creates a temporary vertical water face
	 * and a lowered corner exactly on a 16-block boundary; both become bright/dark one-pixel lines
	 * until the normal missing-halo remesh runs after that column arrives.
	 */
	boolean isColumnMissing(int worldX, int worldZ) {
		int dx = (worldX >> 4) - (baseX >> 4) + 1;
		int dz = (worldZ >> 4) - (baseZ >> 4) + 1;
		return dx >= 0 && dx <= 2 && dz >= 0 && dz <= 2
				&& (missingColumnMask & (1 << (dx * 3 + dz))) != 0;
	}

	@Override
	public BlockState getBlockState(BlockPos pos) {
		int index = blockIndex(pos.getX(), pos.getY(), pos.getZ());
		BlockState state = index < 0 ? null : states[index];
		return state == null ? Blocks.AIR.getDefaultState() : state;
	}

	@Override
	public FluidState getFluidState(BlockPos pos) {
		return getBlockState(pos).getFluidState();
	}

	@Override
	public int getColor(BlockPos pos, ColorResolver resolver) {
		Biome biome = biomeAt(pos.getX(), pos.getY(), pos.getZ());
		return biome == null ? 0xFFFFFF : resolver.getColor(biome, pos.getX(), pos.getZ());
	}

	@Override
	public Biome biomeAt(int x, int y, int z) {
		int quartX = (x >> 2) - ((baseX >> 2) - 2);
		int quartZ = (z >> 2) - ((baseZ >> 2) - 2);
		int quartY = (y >> 2) - ((baseY >> 2) - BIOME_QUART_BELOW);
		if (quartX < 0 || quartX >= BIOME_QUART_WIDTH
				|| quartZ < 0 || quartZ >= BIOME_QUART_WIDTH
				|| quartY < 0 || quartY >= BIOME_QUARTS) return null;
		return biomes[(quartY * BIOME_QUART_WIDTH + quartZ) * BIOME_QUART_WIDTH + quartX];
	}

	@Override public float getBrightness(Direction direction, boolean shaded) { return 1.0f; }
	@Override public LightingProvider getLightingProvider() { return null; }
	@Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
	@Override public int getHeight() { return height; }
	@Override public int getBottomY() { return bottomY; }

	private int blockIndex(int x, int y, int z) {
		int lx = x - (baseX - HORIZONTAL_HALO);
		int ly = y - (baseY - BELOW_HALO);
		int lz = z - (baseZ - HORIZONTAL_HALO);
		if (lx < 0 || lx >= BLOCK_WIDTH || ly < 0 || ly >= BLOCK_HEIGHT || lz < 0 || lz >= BLOCK_WIDTH) return -1;
		return (ly * BLOCK_WIDTH + lz) * BLOCK_WIDTH + lx;
	}
}
