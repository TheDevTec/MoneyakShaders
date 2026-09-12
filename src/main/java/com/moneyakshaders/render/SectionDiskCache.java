package com.moneyakshaders.render;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.moneyakshaders.MoneyakShaders;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.world.ClientWorld;

/**
 * v1 cross-session section cache — file IO + (de)serialisation only, no GL. The renderer reads a
 * section's meshed vertex bytes back from the GPU arena (leave-time) and hands them here to persist,
 * keyed by server address + dimension; on join it loads them back and re-uploads. Indices are NOT
 * stored — they are a fixed function of the vertex count, regenerated on upload — so a section is just
 * its solid + water vertex bytes plus the occlusion metadata.
 *
 * <p>A resource-pack fingerprint is written in the header: the baked vertex bytes encode atlas UVs, so
 * a cache written under a different pack set would render with wrong textures. On a fingerprint
 * mismatch the file is ignored (and deleted). Wrong-world content on a multi-world/proxy server is not
 * guarded here — it self-heals per chunk: every cached section is replaced by the server's real data
 * the moment its chunk reloads (the renderer's swap-on-load), so stale geometry lasts only the reload gap.
 */
public final class SectionDiskCache {
	private static final int MAGIC = 0x4F4C5343; // 'OLSC'
	// v5 invalidates development-era meshes whose biome tier ignored fastBiomeBlend=false and whose
	// first light capture could publish a mixed revision. The vertex layout is compatible, but cached
	// colours/light and boundary water geometry are not safe to show as provisional terrain.
	private static final int FORMAT = 7;
	/** Guard against a corrupt length field allocating a huge array — no real section is this big. */
	private static final int MAX_LAYER_BYTES = 8 * 1024 * 1024;

	private SectionDiskCache() {
	}

	/** One section's persisted geometry. Byte arrays are {@code verts * TerrainVertex.STRIDE} long. */
	public static final class Entry {
		public long key;
		public byte openFaces;
		public long visibility;
		public int solidVerts;
		public byte[] solid = EMPTY;
		public int waterVerts;
		public byte[] water = EMPTY;
		/** Derived while the cache file is decoded, never persisted. Keeps restore CPU off the render thread. */
		public float[] translucentQuadCentroids;
		/** Derived alongside {@link #translucentQuadCentroids}; true only when order-sensitive materials mix. */
		public boolean translucentMixedWater;
		/** Pure horizontal water surface has no intersecting alpha geometry and keeps stable mesh order. */
		public boolean translucentOnlyWater;

		/**
		 * Coloured light is baked into the vertex stream from sources in this section and its halo. That
		 * halo has no persistent revision in the disk key, so restoring such geometry can briefly show an
		 * old chunk-shaped tint before the live packet remesh replaces it. Keep ordinary cached terrain,
		 * but never publish cached vertices whose light-tint weight is non-zero.
		 */
		private boolean hasBakedLightTint() {
			return hasBakedLightTint(solid, solidVerts) || hasBakedLightTint(water, waterVerts);
		}

		private static boolean hasBakedLightTint(byte[] vertices, int vertexCount) {
			if (vertexCount <= 0 || vertices.length != vertexCount * TerrainVertex.STRIDE) {
				return false;
			}
			int weightOffset = TerrainVertex.OFF_LIGHT_TINT + 3;
			for (int vertex = 0; vertex < vertexCount; vertex++) {
				if (vertices[vertex * TerrainVertex.STRIDE + weightOffset] != 0) {
					return true;
				}
			}
			return false;
		}

		private void prepareTranslucentMetadata() {
			if (waterVerts <= 0 || water.length != waterVerts * TerrainVertex.STRIDE) {
				return;
			}
			int quads = waterVerts / 4;
			float[] centroids = new float[quads * 3];
			boolean waterMaterial = false;
			boolean nonWaterMaterial = false;
			boolean waterTopOnly = true;
			for (int q = 0; q < quads; q++) {
				float sx = 0f, sy = 0f, sz = 0f;
				int vertexBase = q * 4 * TerrainVertex.STRIDE;
				for (int v = 0; v < 4; v++) {
					int offset = vertexBase + v * TerrainVertex.STRIDE;
					sx += TerrainVertex.decodePos(u16le(water, offset));
					sy += TerrainVertex.decodePos(u16le(water, offset + 2));
					sz += TerrainVertex.decodePos(u16le(water, offset + 4));
				}
				int material = water[vertexBase + TerrainVertex.OFF_MATERIAL] & 0xFF;
				if (McSectionMesher.isWaterMaterial(material)) {
					waterMaterial = true;
					if (water[vertexBase + TerrainVertex.OFF_NORMAL + 1] < 120) waterTopOnly = false;
				}
				else nonWaterMaterial = true;
				int centroidBase = q * 3;
				centroids[centroidBase] = sx * 0.25f;
				centroids[centroidBase + 1] = sy * 0.25f;
				centroids[centroidBase + 2] = sz * 0.25f;
			}
			translucentQuadCentroids = centroids;
			translucentMixedWater = waterMaterial && nonWaterMaterial;
			translucentOnlyWater = waterMaterial && !nonWaterMaterial && waterTopOnly;
		}
	}

	private static final byte[] EMPTY = new byte[0];

	private static Path cacheDir() {
		return MinecraftClient.getInstance().runDirectory.toPath().resolve("moneyakshaders").resolve("sectioncache");
	}

	private static Path file(String serverKey, String dimKey) {
		return cacheDir().resolve(serverKey + "__" + dimKey + ".olsc");
	}

	/** Filesystem-safe token from an arbitrary id (server address / dimension id). */
	private static String sanitize(String s) {
		String out = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
		return out.length() > 80 ? out.substring(0, 80) + Integer.toHexString(s.hashCode()) : out;
	}

	/** Cache bucket for the current connection, or null if it should not be cached (realms / unknown). */
	public static String serverKey(MinecraftClient mc) {
		ServerInfo si = mc.getCurrentServerEntry();
		if (si != null && si.address != null) {
			return "mp_" + sanitize(si.address);
		}
		if (mc.getServer() != null) {
			return "sp_" + sanitize(mc.getServer().getSaveProperties().getLevelInfo().getLevelName());
		}
		return null;
	}

	public static String dimKey(ClientWorld world) {
		return sanitize(world.getRegistryKey().getValue().toString());
	}

	/**
	 * Changes when the enabled resource packs change — baked atlas UVs are only valid for this set.
	 * Ids are SORTED (enable order must not matter) and SERVER-pushed packs are excluded: the server
	 * pack is enabled at save time (in-game) but not yet at load time (world join, before the pack
	 * download applies), so including it invalidated the cache on every single restart. A server-pack
	 * atlas difference only means briefly stale textures — healed by the per-section swap-on-load.
	 */
	public static int packFingerprint(MinecraftClient mc) {
		List<String> ids = new ArrayList<>(mc.getResourcePackManager().getEnabledIds());
		ids.removeIf(id -> id.startsWith("server"));
		java.util.Collections.sort(ids);
		int h = FORMAT * 31 + TerrainVertex.STRIDE;
		for (String id : ids) {
			h = h * 31 + id.hashCode();
		}
		return h;
	}

	/** Write the entries to the bucket's file (temp + atomic move). Safe to call off the render thread. */
	public static void save(String serverKey, String dimKey, int fingerprint, List<Entry> entries) {
		if (serverKey == null || entries.isEmpty()) {
			return;
		}
		try {
			Path dir = cacheDir();
			Files.createDirectories(dir);
			Path dst = file(serverKey, dimKey);
			Path tmp = dst.resolveSibling(dst.getFileName() + ".tmp");
			try (DataOutputStream out = new DataOutputStream(
					new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp))))) {
				out.writeInt(MAGIC);
				out.writeInt(FORMAT);
				out.writeInt(fingerprint);
				out.writeInt(entries.size());
				for (Entry e : entries) {
					out.writeLong(e.key);
					out.writeByte(e.openFaces);
					out.writeLong(e.visibility);
					out.writeInt(e.solidVerts);
					out.writeInt(e.solid.length);
					out.write(e.solid);
					out.writeInt(e.waterVerts);
					out.writeInt(e.water.length);
					out.write(e.water);
				}
			}
			try {
				Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException atomicUnsupported) {
				Files.move(tmp, dst, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException | RuntimeException ex) {
			MoneyakShaders.LOGGER.warn("[Optimized Loading] section cache save failed for {}/{}", serverKey, dimKey, ex);
		}
	}

	/** Load a bucket's entries, or null if it is missing, corrupt, or written under a different pack set. */
	public static List<Entry> load(String serverKey, String dimKey, int fingerprint) {
		if (serverKey == null) {
			return null;
		}
		Path f = file(serverKey, dimKey);
		if (!Files.isRegularFile(f)) {
			return null;
		}
		try (DataInputStream in = new DataInputStream(
				new BufferedInputStream(new GZIPInputStream(Files.newInputStream(f))))) {
			if (in.readInt() != MAGIC || in.readInt() != FORMAT || in.readInt() != fingerprint) {
				// Stale (pack set changed / old format) — IGNORE but never delete: a transient pack-state
				// difference at load time must not wipe the cache; the next flush overwrites it anyway.
				return null;
			}
			int n = in.readInt();
			if (n < 0 || n > 500_000) {
				return null;
			}
			List<Entry> out = new ArrayList<>(n);
			for (int i = 0; i < n; i++) {
				Entry e = new Entry();
				e.key = in.readLong();
				e.openFaces = in.readByte();
				e.visibility = in.readLong();
				e.solidVerts = in.readInt();
				e.solid = readBlob(in);
				e.waterVerts = in.readInt();
				e.water = readBlob(in);
				// This is pure byte-array work. Do it on the cache I/O thread so restoring many
				// resource-pack-heavy sections cannot steal a join/render frame from GL upload.
				if (!e.hasBakedLightTint()) {
					e.prepareTranslucentMetadata();
					out.add(e);
				}
			}
			return out;
		} catch (IOException | RuntimeException ex) {
			MoneyakShaders.LOGGER.warn("[Optimized Loading] section cache load failed for {}/{} — ignoring", serverKey, dimKey, ex);
			return null;
		}
	}

	private static byte[] readBlob(DataInputStream in) throws IOException {
		int len = in.readInt();
		if (len < 0 || len > MAX_LAYER_BYTES) {
			throw new IOException("section cache: bad blob length " + len);
		}
		if (len == 0) {
			return EMPTY;
		}
		byte[] b = new byte[len];
		in.readFully(b);
		return b;
	}

	private static int u16le(byte[] data, int offset) {
		return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
	}

	/** Convenience for callers that only have the pack set and want a stable list of ids (debug/logging). */
	public static Collection<String> enabledPackIds(MinecraftClient mc) {
		return mc.getResourcePackManager().getEnabledIds();
	}
}
