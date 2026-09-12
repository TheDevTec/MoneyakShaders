package com.moneyakshaders.render;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.moneyakshaders.MoneyakShadersConfig;
import com.moneyakshaders.client.ClientLightDispatcher;
import com.moneyakshaders.client.DynamicLightSources;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkNibbleArray;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.light.ChunkLightingView;

/**
 * Render-thread producer for {@link SectionInputSnapshot}. Work is explicitly sliceable: a chunk
 * burst cannot turn immutable extraction into a single long frame. Once {@link #snapshot()} becomes
 * non-null it contains no world reference and is safe to hand to a mesh worker.
 */
final class SectionSnapshotBuilder {
	/** A first mesh must not remain invisible forever under a continuous server relight burst. */
	private static final long FIRST_MESH_LIGHT_WAIT_MS = 200L;
	/*
	 * A snapshot samples up to 27 neighbouring sections for terrain point lights.  The old path
	 * walked all 4,096 blocks of each non-empty source section for every target section.  During
	 * streaming a single lamp section is shared by many target meshes, so that repeated scan was
	 * often the actual delay between a received chunk and its first visible mesh.  Keep only the
	 * local coordinates of luminous blocks; the live state is still read when the snapshot is made.
	 * This cache is render-thread-owned, bounded, and invalidated before a changed column is used.
	 */
	private static final int LIGHT_SOURCE_CACHE_LIMIT = 512;
	private static final int[] NO_LIGHT_SOURCES = new int[0];
	private static final Map<Long, SourceCacheEntry> LIGHT_SOURCE_POSITIONS =
			new LinkedHashMap<>(LIGHT_SOURCE_CACHE_LIMIT, 0.75f, true);
	private static final ConcurrentLinkedQueue<Long> INVALIDATED_SOURCE_SECTIONS = new ConcurrentLinkedQueue<>();
	private static final ConcurrentLinkedQueue<Long> INVALIDATED_SOURCE_COLUMNS = new ConcurrentLinkedQueue<>();
	private final ClientWorld world;
	private final SectionInputSnapshot snapshot;
	/** 3×3 neighbouring chunk columns × three vertical sections covering the block halo. */
	private final ChunkSection[] stateSections = new ChunkSection[27];
	/** True only when the corresponding state palette actually contains a non-air value. */
	private final boolean[] stateSectionHasBlocks = new boolean[27];
	/**
	 * Matching per-section light nibbles.  Looking these up once is substantially cheaper than
	 * asking the lighting provider twice for every one of the 9,600 block samples in a snapshot.
	 * The arrays are refreshed under the light read lock whenever the captured light generation moves.
	 */
	private final ChunkNibbleArray[] blockLightSections = new ChunkNibbleArray[27];
	private final ChunkNibbleArray[] skyLightSections = new ChunkNibbleArray[27];
	private boolean lightSectionsCached;
	private final int stateChunkX, stateChunkZ, stateSectionY;
	private final List<SourceSection> sourceSections = new ArrayList<>();
	private final BlockPos.Mutable mutablePos = new BlockPos.Mutable();
	private int blockCursor;
	private int biomeCursor;
	private int sourceSectionCursor;
	private int sourceLightCursor;
	/** -1 means the initially captured packed-light values are current; otherwise re-copy from here. */
	private int relightCursor = -1;
	/** Local 3x3x3 light revision; unrelated chunks must not restart this snapshot. */
	private long renderLightRevision;
	private final boolean collectLightSources;
	private final boolean allowProvisionalLight;
	private final long provisionalLightDeadlineMs;
	private boolean provisionalLight;

	SectionSnapshotBuilder(ClientWorld world, int baseX, int baseY, int baseZ, boolean collectLightSources,
			boolean allowProvisionalLight) {
		drainLightSourceInvalidations();
		this.world = world;
		this.collectLightSources = collectLightSources;
		this.allowProvisionalLight = allowProvisionalLight;
		this.provisionalLightDeadlineMs = System.currentTimeMillis() + FIRST_MESH_LIGHT_WAIT_MS;
		this.snapshot = SectionInputSnapshot.acquire(baseX, baseY, baseZ, world.getBottomY(), world.getHeight());
		this.stateChunkX = baseX >> 4;
		this.stateChunkZ = baseZ >> 4;
		this.stateSectionY = baseY >> 4;
		this.renderLightRevision = ClientLightDispatcher.renderLightRevisionSignature(baseX >> 4, baseY >> 4, baseZ >> 4);
		cacheStateSections();
		if (collectLightSources && MoneyakShadersConfig.get().terrainPointLights) {
			collectSourceSections(baseX, baseY, baseZ);
		}
	}

	/** Copies at most {@code budget} source cells; returns the consumed amount. Render thread only. */
	int step(int budget) {
		long currentRenderRevision = ClientLightDispatcher.renderLightRevisionSignature(
				snapshot.baseX >> 4, snapshot.baseY >> 4, snapshot.baseZ >> 4);
		if (renderLightRevision != currentRenderRevision) {
			// Light propagation never changes geometry, biomes or the lamp-source index. Restarting
			// all of those made a first surface mesh lose several frames of progress per packet and
			// starve forever. Preserve them and re-copy only packed light after the local halo settles.
			relightCursor = 0;
			renderLightRevision = currentRenderRevision;
			lightSectionsCached = false;
		}
		boolean readLocked = ClientLightDispatcher.lockSnapshotRead();
		try {
			if (!lightSectionsCached) {
				cacheLightSections();
				lightSectionsCached = true;
			}
		int used = 0;
		while (used < budget && blockCursor < snapshot.blockCount()) {
			int i = blockCursor++;
			int lx = i % SectionInputSnapshot.BLOCK_WIDTH;
			int plane = i / SectionInputSnapshot.BLOCK_WIDTH;
			int lz = plane % SectionInputSnapshot.BLOCK_WIDTH;
			int ly = plane / SectionInputSnapshot.BLOCK_WIDTH;
			int x = snapshot.baseX - SectionInputSnapshot.HORIZONTAL_HALO + lx;
			int y = snapshot.baseY - SectionInputSnapshot.BELOW_HALO + ly;
			int z = snapshot.baseZ - SectionInputSnapshot.HORIZONTAL_HALO + lz;
			// Both state and light now come from section-local caches.  Falling back to WorldRenderer
			// only for an unavailable halo section keeps missing-column behaviour byte-for-byte vanilla.
			BlockState state = cachedBlockState(x, y, z);
			snapshot.setBlock(i, state, cachedPackedLight(state, x, y, z));
			used++;
		}
		while (used < budget && blockCursor == snapshot.blockCount()
				&& biomeCursor < SectionInputSnapshot.BIOME_QUART_WIDTH * SectionInputSnapshot.BIOME_QUART_WIDTH
						* SectionInputSnapshot.BIOME_QUARTS) {
			int i = biomeCursor++;
			int quartX = i % SectionInputSnapshot.BIOME_QUART_WIDTH;
			int plane = i / SectionInputSnapshot.BIOME_QUART_WIDTH;
			int quartZ = plane % SectionInputSnapshot.BIOME_QUART_WIDTH;
			int quartY = plane / SectionInputSnapshot.BIOME_QUART_WIDTH;
			int x = ((snapshot.baseX >> 2) - 2 + quartX) << 2;
			int y = ((snapshot.baseY >> 2) - SectionInputSnapshot.BIOME_QUART_BELOW + quartY) << 2;
			int z = ((snapshot.baseZ >> 2) - 2 + quartZ) << 2;
			// Store the palette at its native 4x4x4 resolution. BiomeBlurTint still asks in block
			// coordinates; SectionInputSnapshot maps those back to this exact quart cell.
			snapshot.setBiome(quartX, quartY, quartZ, cachedBiome(x, y, z));
			used++;
		}
		while (used < budget && blockCursor == snapshot.blockCount()
				&& biomeCursor == SectionInputSnapshot.BIOME_QUART_WIDTH * SectionInputSnapshot.BIOME_QUART_WIDTH
						* SectionInputSnapshot.BIOME_QUARTS
				&& sourceSectionCursor < sourceSections.size()) {
			SourceSection source = sourceSections.get(sourceSectionCursor);
			int[] sourcePositions = source.lightPositions();
			if (sourceLightCursor == sourcePositions.length) {
				sourceLightCursor = 0;
				sourceSectionCursor++;
				continue;
			}
			int local = sourcePositions[sourceLightCursor++];
			BlockState state = source.section.getBlockState(local & 15,
					(local >>> 4) & 15, (local >>> 8) & 15);
			int luminance = state.getLuminance();
			if (luminance > 0) {
				float[] rgb = DynamicLightSources.blockColor(state.getBlock());
				float max = Math.max(rgb[0], Math.max(rgb[1], rgb[2]));
				if (max > 0.001f) {
					float x = source.baseX + (local & 15) + 0.5f;
					float y = source.baseY + ((local >>> 4) & 15) + 0.5f;
					float z = source.baseZ + ((local >>> 8) & 15) + 0.5f;
					float rangeSq = (float) luminance * luminance;
					if (sectionLightInfluence(x, y, z, rangeSq) > 0f) {
						int slot = snapshot.lightSourceCount++;
						snapshot.ensureLightSourceCapacity(snapshot.lightSourceCount);
						int out = slot * 7;
						snapshot.lightSources[out] = x;
						snapshot.lightSources[out + 1] = y;
						snapshot.lightSources[out + 2] = z;
						snapshot.lightSources[out + 3] = rangeSq;
						snapshot.lightSources[out + 4] = rgb[0] / max;
						snapshot.lightSources[out + 5] = rgb[1] / max;
						snapshot.lightSources[out + 6] = rgb[2] / max;
					}
				}
			}
			used++;
		}
		boolean baseComplete = blockCursor == snapshot.blockCount()
				&& biomeCursor == SectionInputSnapshot.BIOME_QUART_WIDTH * SectionInputSnapshot.BIOME_QUART_WIDTH
						* SectionInputSnapshot.BIOME_QUARTS
				&& (!collectLightSources || sourceSectionCursor == sourceSections.size());
		if (baseComplete && relightCursor >= 0 && allowProvisionalLight
				&& System.currentTimeMillis() >= provisionalLightDeadlineMs) {
			// Geometry/biomes are complete, but this local light halo has never stayed quiet long enough
			// for a coherent recopy. Publish the first mesh instead of a white hole. The renderer tags it
			// as provisional and immediately schedules one strict resident light replacement.
			relightCursor = -1;
			provisionalLight = true;
		}
		if (baseComplete && relightCursor >= 0) {
			// The provider revision is the coherence boundary. A second wall-clock quiet period made an
			// already visible room brighten later and let adjacent sections display different generations.
			// If propagation advances during this copy, the next slice restarts only packed-light cells.
			while (used < budget && relightCursor < snapshot.blockCount()) {
				int i = relightCursor++;
				int lx = i % SectionInputSnapshot.BLOCK_WIDTH;
				int plane = i / SectionInputSnapshot.BLOCK_WIDTH;
				int lz = plane % SectionInputSnapshot.BLOCK_WIDTH;
				int ly = plane / SectionInputSnapshot.BLOCK_WIDTH;
				int x = snapshot.baseX - SectionInputSnapshot.HORIZONTAL_HALO + lx;
				int y = snapshot.baseY - SectionInputSnapshot.BELOW_HALO + ly;
				int z = snapshot.baseZ - SectionInputSnapshot.HORIZONTAL_HALO + lz;
				BlockState state = snapshot.states[i];
				snapshot.light[i] = cachedPackedLight(state == null ? Blocks.AIR.getDefaultState() : state, x, y, z);
				used++;
			}
			if (relightCursor == snapshot.blockCount()) relightCursor = -1;
		}
		return used;
		} finally {
			if (readLocked) {
				ClientLightDispatcher.unlockSnapshotRead();
			}
		}
	}

	private float sectionLightInfluence(float x, float y, float z, float rangeSq) {
		float dx = x < snapshot.baseX ? snapshot.baseX - x : Math.max(0f, x - (snapshot.baseX + 16f));
		float dy = y < snapshot.baseY ? snapshot.baseY - y : Math.max(0f, y - (snapshot.baseY + 16f));
		float dz = z < snapshot.baseZ ? snapshot.baseZ - z : Math.max(0f, z - (snapshot.baseZ + 16f));
		float distanceSq = dx * dx + dy * dy + dz * dz;
		return distanceSq >= rangeSq ? 0f : rangeSq - distanceSq;
	}

	boolean complete() {
		return blockCursor == snapshot.blockCount()
				&& biomeCursor == SectionInputSnapshot.BIOME_QUART_WIDTH * SectionInputSnapshot.BIOME_QUART_WIDTH
						* SectionInputSnapshot.BIOME_QUARTS
				&& (!collectLightSources || sourceSectionCursor == sourceSections.size())
				&& relightCursor < 0;
	}

	/** Compact live diagnostics for producer stalls; render-thread only. */
	int debugStage() {
		if (blockCursor < snapshot.blockCount()) return 0;
		if (biomeCursor < SectionInputSnapshot.BIOME_QUART_WIDTH * SectionInputSnapshot.BIOME_QUART_WIDTH
				* SectionInputSnapshot.BIOME_QUARTS) return 1;
		if (collectLightSources && sourceSectionCursor < sourceSections.size()) return 2;
		if (relightCursor >= 0) return 3;
		return 4;
	}

	boolean debugAllowsProvisionalLight() { return allowProvisionalLight; }

	SectionInputSnapshot snapshot() {
		if (!complete()) return null;
		if (provisionalLight) {
			snapshot.renderLightGeneration = -1L;
			return snapshot;
		}
		long currentRenderRevision = ClientLightDispatcher.renderLightRevisionSignature(
				snapshot.baseX >> 4, snapshot.baseY >> 4, snapshot.baseZ >> 4);
		if (renderLightRevision != currentRenderRevision) return null;
		snapshot.renderLightGeneration = renderLightRevision;
		return snapshot;
	}

	void cancel() { snapshot.recycle(); }

	/**
	 * The snapshot halo spans exactly the previous/current chunk in X/Z and sectionY−1..sectionY+1.
	 * Resolving those twelve references once removes thousands of ClientWorld block lookups per section.
	 */
	private void cacheStateSections() {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				int columnBit = 1 << ((dx + 1) * 3 + (dz + 1));
				Chunk chunk = world.getChunk(stateChunkX + dx, stateChunkZ + dz, ChunkStatus.FULL, false);
				if (chunk == null) {
					// A new column only requires a follow-up mesh when this specific immutable input
					// actually captured it as absent.  Carry this to the worker result rather than
					// rebuilding every nearby section after every packet in a streaming burst.
					snapshot.missingColumnMask |= columnBit;
					continue;
				}
				ChunkSection[] sections = chunk.getSectionArray();
				for (int dy = -1; dy <= 1; dy++) {
					int sectionIndex = chunk.getSectionIndex((stateSectionY + dy) << 4);
					if (sectionIndex >= 0 && sectionIndex < sections.length) {
						int cacheIndex = ((dx + 1) * 3 + (dz + 1)) * 3 + dy + 1;
						ChunkSection section = sections[sectionIndex];
						stateSections[cacheIndex] = section;
						// Keep the section reference for biome sampling, but skip thousands of palette
						// reads when this part of the 3x3x3 block halo is uniform air. Do not trust
						// only ChunkSection#isEmpty: raw/FAWE-style server writes can temporarily expose
						// a non-air palette before the transmitted counter catches up.
						stateSectionHasBlocks[cacheIndex] = section != null
								&& (!section.isEmpty() || section.hasAny(state -> !state.isAir()));
					}
				}
			}
		}
	}

	boolean needsHaloRefreshForColumn(int chunkX, int chunkZ) {
		int dx = chunkX - stateChunkX + 1;
		int dz = chunkZ - stateChunkZ + 1;
		return dx >= 0 && dx <= 2 && dz >= 0 && dz <= 2
				&& (snapshot.missingColumnMask & (1 << (dx * 3 + dz))) != 0;
	}

	/** Resolve one quart-biome sample from the same stable 3×3 section halo as block states. */
	private net.minecraft.world.biome.Biome cachedBiome(int x, int y, int z) {
		int dx = (x >> 4) - stateChunkX + 1;
		int dy = (y >> 4) - stateSectionY + 1;
		int dz = (z >> 4) - stateChunkZ + 1;
		if (dx >= 0 && dx <= 2 && dy >= 0 && dy <= 2 && dz >= 0 && dz <= 2) {
			ChunkSection section = stateSections[(dx * 3 + dz) * 3 + dy];
			if (section != null) {
				Object entry = section.getBiomeContainer().get((x & 15) >> 2, (y & 15) >> 2, (z & 15) >> 2);
				if (entry instanceof net.minecraft.registry.entry.RegistryEntry<?> registryEntry
						&& registryEntry.value() instanceof net.minecraft.world.biome.Biome biome) {
					return biome;
				}
			}
			if (section == null && (dx != 1 || dz != 1)) {
				// Match the provisional light-halo policy below. Until the neighbouring column is
				// received, extend the nearest quart-biome from the target column instead of sampling
				// ClientWorld's temporary/default biome and baking a coloured chunk-border ring into
				// grass/water. The normal received-column invalidation replaces this with exact blending.
				ChunkSection ownSection = stateSections[(1 * 3 + 1) * 3 + dy];
				if (ownSection != null) {
					int sampleX = dx == 0 ? 0 : dx == 2 ? 15 : x & 15;
					int sampleZ = dz == 0 ? 0 : dz == 2 ? 15 : z & 15;
					Object entry = ownSection.getBiomeContainer().get(
							sampleX >> 2, (y & 15) >> 2, sampleZ >> 2);
					if (entry instanceof net.minecraft.registry.entry.RegistryEntry<?> registryEntry
							&& registryEntry.value() instanceof net.minecraft.world.biome.Biome biome) {
						return biome;
					}
				}
			}
		}
		return world.getBiome(mutablePos.set(x, y, z)).value();
	}

	/** Cache the same 3×3×3 halo used for block states, while the async-light read lock is held. */
	private void cacheLightSections() {
		ChunkLightingView blockLight = world.getLightingProvider().get(LightType.BLOCK);
		ChunkLightingView skyLight = world.getLightingProvider().get(LightType.SKY);
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				for (int dy = -1; dy <= 1; dy++) {
					int index = ((dx + 1) * 3 + (dz + 1)) * 3 + dy + 1;
					ChunkSectionPos sectionPos = ChunkSectionPos.from(
							stateChunkX + dx, stateSectionY + dy, stateChunkZ + dz);
					blockLightSections[index] = blockLight.getLightSection(sectionPos);
					skyLightSections[index] = skyLight.getLightSection(sectionPos);
				}
			}
		}
	}

	private BlockState cachedBlockState(int x, int y, int z) {
		int dx = (x >> 4) - stateChunkX + 1;
		int dz = (z >> 4) - stateChunkZ + 1;
		int dy = (y >> 4) - stateSectionY + 1;
		if (dx < 0 || dx > 2 || dz < 0 || dz > 2 || dy < 0 || dy > 2) return Blocks.AIR.getDefaultState();
		int index = (dx * 3 + dz) * 3 + dy;
		ChunkSection section = stateSections[index];
		return section == null || !stateSectionHasBlocks[index]
				? Blocks.AIR.getDefaultState()
				: section.getBlockState(x & 15, y & 15, z & 15);
	}

	/** Exact vanilla lightmap packing from cached nibble arrays, including emissive-model behaviour. */
	private int cachedPackedLight(BlockState state, int x, int y, int z) {
		// Relight slices also call this method; never reuse the last geometry/biome position.
		mutablePos.set(x, y, z);
		if (state.hasEmissiveLighting(world, mutablePos)) {
			return 15 << 20 | 15 << 4;
		}
		int dx = (x >> 4) - stateChunkX + 1;
		int dz = (z >> 4) - stateChunkZ + 1;
		int dy = (y >> 4) - stateSectionY + 1;
		if (dx < 0 || dx > 2 || dz < 0 || dz > 2 || dy < 0 || dy > 2) {
			return WorldRenderer.getLightmapCoordinates(WorldRenderer.BrightnessGetter.DEFAULT, world, state, mutablePos);
		}
		int index = (dx * 3 + dz) * 3 + dy;
		ChunkNibbleArray blockLight = blockLightSections[index];
		ChunkNibbleArray skyLight = skyLightSections[index];
		if ((blockLight == null || skyLight == null) && (dx != 1 || dz != 1)) {
			// Geometry can arrive one packet before the neighbouring light section (or before the whole
			// neighbouring column). Vanilla's fallback then reports zero at exactly the one-cell mesh
			// halo, baking a black line along the complete chunk edge until the follow-up remesh. For this
			// short provisional window extend the target column's nearest edge light into the absent halo.
			// The received-column/light revision path still rebuilds with the exact neighbour afterwards.
			int ownIndex = (1 * 3 + 1) * 3 + dy;
			ChunkNibbleArray ownBlock = blockLightSections[ownIndex];
			ChunkNibbleArray ownSky = skyLightSections[ownIndex];
			if (ownBlock != null && ownSky != null) {
				int sampleX = dx == 0 ? 0 : dx == 2 ? 15 : x & 15;
				int sampleZ = dz == 0 ? 0 : dz == 2 ? 15 : z & 15;
				int sampleY = y & 15;
				int block = Math.max(ownBlock.get(sampleX, sampleY, sampleZ), state.getLuminance());
				int sky = ownSky.get(sampleX, sampleY, sampleZ);
				return LightmapTextureManager.pack(block, sky);
			}
		}
		if (blockLight == null || skyLight == null) {
			return WorldRenderer.getLightmapCoordinates(WorldRenderer.BrightnessGetter.DEFAULT, world, state, mutablePos);
		}
		int block = Math.max(blockLight.get(x & 15, y & 15, z & 15), state.getLuminance());
		int sky = skyLight.get(x & 15, y & 15, z & 15);
		return LightmapTextureManager.pack(block, sky);
	}

	private void collectSourceSections(int baseX, int baseY, int baseZ) {
		int chunkX = baseX >> 4, chunkZ = baseZ >> 4, sectionY = baseY >> 4;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				Chunk chunk = world.getChunk(chunkX + dx, chunkZ + dz, ChunkStatus.FULL, false);
				if (chunk == null) continue;
				ChunkSection[] sections = chunk.getSectionArray();
				for (int dy = -1; dy <= 1; dy++) {
					int sectionIndex = chunk.getSectionIndex((sectionY + dy) << 4);
					if (sectionIndex < 0 || sectionIndex >= sections.length) continue;
					ChunkSection section = sections[sectionIndex];
					// This is a palette predicate, not a 4k block traversal. Keeping it here
					// removes whole no-light sections before the budgeted source scan; without
					// it a torch-free spawn area can consume 27 * 4096 extraction cells/section.
					if (section != null && !section.isEmpty() && section.hasAny(state -> state.getLuminance() > 0)) {
						sourceSections.add(new SourceSection(section, (chunkX + dx) << 4, (sectionY + dy) << 4,
								(chunkZ + dz) << 4, ChunkSectionPos.asLong(chunkX + dx, sectionY + dy, chunkZ + dz)));
					}
				}
			}
		}
	}

	static void invalidateLightSourceSection(int sectionX, int sectionY, int sectionZ) {
		INVALIDATED_SOURCE_SECTIONS.offer(ChunkSectionPos.asLong(sectionX, sectionY, sectionZ));
	}

	static void invalidateLightSourceColumn(int chunkX, int chunkZ) {
		INVALIDATED_SOURCE_COLUMNS.offer(ChunkSectionPos.asLong(chunkX, 0, chunkZ));
	}

	static void clearLightSourceCache() {
		LIGHT_SOURCE_POSITIONS.clear();
		INVALIDATED_SOURCE_SECTIONS.clear();
		INVALIDATED_SOURCE_COLUMNS.clear();
	}

	private static void drainLightSourceInvalidations() {
		Long sectionKey;
		while ((sectionKey = INVALIDATED_SOURCE_SECTIONS.poll()) != null) {
			LIGHT_SOURCE_POSITIONS.remove(sectionKey);
		}
		Long columnKey;
		while ((columnKey = INVALIDATED_SOURCE_COLUMNS.poll()) != null) {
			int chunkX = ChunkSectionPos.unpackX(columnKey);
			int chunkZ = ChunkSectionPos.unpackZ(columnKey);
			LIGHT_SOURCE_POSITIONS.entrySet().removeIf(entry ->
					ChunkSectionPos.unpackX(entry.getKey()) == chunkX && ChunkSectionPos.unpackZ(entry.getKey()) == chunkZ);
		}
	}

	private static int[] lightPositions(ChunkSection section, long sectionKey) {
		SourceCacheEntry cached = LIGHT_SOURCE_POSITIONS.get(sectionKey);
		if (cached != null && cached.section == section) return cached.positions;
		int[] positions = new int[64];
		int count = 0;
		for (int local = 0; local < 4096; local++) {
			BlockState state = section.getBlockState(local & 15, (local >>> 4) & 15, (local >>> 8) & 15);
			if (state.getLuminance() <= 0) continue;
			if (count == positions.length) {
				int[] expanded = new int[Math.min(4096, positions.length << 1)];
				System.arraycopy(positions, 0, expanded, 0, positions.length);
				positions = expanded;
			}
			positions[count++] = local;
		}
		if (count == 0) positions = NO_LIGHT_SOURCES;
		else if (count != positions.length) {
			int[] trimmed = new int[count];
			System.arraycopy(positions, 0, trimmed, 0, count);
			positions = trimmed;
		}
		LIGHT_SOURCE_POSITIONS.put(sectionKey, new SourceCacheEntry(section, positions));
		while (LIGHT_SOURCE_POSITIONS.size() > LIGHT_SOURCE_CACHE_LIMIT) {
			LIGHT_SOURCE_POSITIONS.remove(LIGHT_SOURCE_POSITIONS.keySet().iterator().next());
		}
		return positions;
	}

	private record SourceSection(ChunkSection section, int baseX, int baseY, int baseZ, long sectionKey) {
		int[] lightPositions() { return SectionSnapshotBuilder.lightPositions(section, sectionKey); }
	}

	private record SourceCacheEntry(ChunkSection section, int[] positions) { }
}
