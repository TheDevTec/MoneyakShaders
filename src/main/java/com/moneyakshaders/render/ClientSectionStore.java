package com.moneyakshaders.render;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

/**
 * First slice of the spec's client-side chunk rework: an independent map of section-key →
 * vanilla {@link ChunkSection} that we own the lifetime of, decoupled from
 * {@code ClientChunkManager}. Populated when a chunk column arrives from the server; entries are
 * removed when the column unloads.
 *
 * <p>Holds references, not copies — a future consumer meshing off-thread will take an immutable
 * palette+lightmap snapshot at pickup time (vanilla {@code ChunkSection} is mutable in place, so
 * reading it while the game thread edits would race). What this class buys today is a single
 * canonical registry of "which sections do we actually care about right now", addressable by
 * packed section key, without walking vanilla's chunk map.
 *
 * <p>All methods are safe to call from any thread. Reads are cheap (single synchronized get);
 * writes happen once per chunk load/unload.
 */
public final class ClientSectionStore {
	private static final Long2ObjectOpenHashMap<ChunkSection> SECTIONS = new Long2ObjectOpenHashMap<>();
	private static final Object LOCK = new Object();

	private ClientSectionStore() {
	}

	public static void put(long sectionKey, ChunkSection section) {
		if (section == null) return;
		synchronized (LOCK) {
			SECTIONS.put(sectionKey, section);
		}
	}

	public static ChunkSection get(long sectionKey) {
		synchronized (LOCK) {
			return SECTIONS.get(sectionKey);
		}
	}

	public static void remove(long sectionKey) {
		synchronized (LOCK) {
			SECTIONS.remove(sectionKey);
		}
	}

	public static void ingest(WorldChunk chunk) {
		if (chunk == null) return;
		ChunkPos cp = chunk.getPos();
		ChunkSection[] sections = chunk.getSectionArray();
		int bottomSectionY = chunk.getBottomSectionCoord();
		for (int i = 0; i < sections.length; i++) {
			ChunkSection s = sections[i];
			if (s == null) continue;
			long key = ChunkSectionPos.asLong(cp.x, bottomSectionY + i, cp.z);
			put(key, s);
		}
	}

	public static void evictColumn(int chunkX, int chunkZ) {
		synchronized (LOCK) {
			SECTIONS.long2ObjectEntrySet().removeIf(en -> {
				long k = en.getLongKey();
				return ChunkSectionPos.unpackX(k) == chunkX && ChunkSectionPos.unpackZ(k) == chunkZ;
			});
		}
	}

	public static void clear() {
		synchronized (LOCK) {
			SECTIONS.clear();
		}
	}

	public static int size() {
		synchronized (LOCK) {
			return SECTIONS.size();
		}
	}
}
