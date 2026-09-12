package com.moneyakshaders.render;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.lwjgl.system.MemoryUtil;

/**
 * Plan C / Phase 1.3 — the output of meshing one chunk section.
 *
 * <p>Holds a growable, GL-uploadable (direct, little-endian) vertex buffer per
 * render layer, filled with {@link TerrainVertex}-format vertices. The mesher
 * (Phase 2) writes into this; the GL uploader (Phase 3) reads {@link
 * #buffer(int)} and {@link #vertexCount(int)}.
 *
 * <p>Dependency-free so it can be self-tested standalone - see {@link #main}.
 */
public final class SectionMeshData {
	public static final int LAYER_SOLID = 0;
	public static final int LAYER_CUTOUT = 1;
	public static final int LAYER_TRANSLUCENT = 2;
	public static final int LAYERS = 3;

	// Phase 4.2: 6-bit mask of which section faces have at least one non-opaque block
	// on the boundary (bit 0=WEST, 1=EAST, 2=DOWN, 3=UP, 4=NORTH, 5=SOUTH).
	// Used by the BFS visibility algorithm to decide if BFS can enter this section.
	public byte openFaces;

	// Occlusion connectivity: directed 6×6 face-visibility matrix packed as bit (from*6+to) of a long
	// (face order = Direction.ordinal(): DOWN0 UP1 NORTH2 SOUTH3 WEST4 EAST5). A set bit means sight can
	// pass from face `from` to face `to` through this section's transparent space. Default = all-connected
	// (every bit set) so a not-yet-computed section never wrongly occludes. Filled by the mesher.
	public long visibility = -1L;

	private static final int INITIAL_VERTICES = 2048;

	private final ByteBuffer[] buffers = new ByteBuffer[LAYERS];
	private final int[] vertexCount = new int[LAYERS];
	// Prepared on the mesh worker so GL upload never scans a large translucent vertex stream.
	private float[] translucentQuadCentroids;
	private boolean translucentMixedWater;
	private boolean translucentOnlyWater;
	private boolean translucentMetadataPrepared;
	/**
	 * Luminous blocks owned by this section. Low 16 bits are level:4,y:4,z:4,x:4;
	 * high 16 bits are the source hue in RGB565. Keeping the colour beside the published
	 * position makes entity/point-light tint deterministic and avoids render-time world reads.
	 */
	private int[] placedLights;
	/** Six exposed-face bits per placed light: +X,-X,+Y,-Y,+Z,-Z. */
	private byte[] placedLightFaceMasks;
	private int placedLightCount;
	/** Bit i is set when placedLights[i] is an opaque full-cube emitter. */
	private long placedLightSolidMask;
	/** Uncapped count; dense light floors use it to disable misleading four-source point shadows. */
	private int placedLightTotal;
	private static final int MAX_PLACED_LIGHTS = 64;
	/** 4096 conservative light-occluder flags, packed as 64 longs for local voxel shadows. */
	private long[] opaqueVoxels;

	public void putVertex(int layer,
			float x, float y, float z, float u, float v,
			int r, int g, int b, int a, float blockLight, float skyLight,
			float nx, float ny, float nz, int material, int lightTint, float ambientOcclusion, float swayWeight) {
		ByteBuffer buf = ensure(layer);
		// lightTint packs the baked coloured block-light as 0xWWRRGGBB-style (w<<24|r<<16|g<<8|b); 0 = none.
		TerrainVertex.write(buf, x, y, z, u, v, r, g, b, a, blockLight, skyLight, nx, ny, nz, material,
				(lightTint >> 16) & 0xFF, (lightTint >> 8) & 0xFF, lightTint & 0xFF, (lightTint >>> 24) & 0xFF,
				ambientOcclusion, swayWeight);
		vertexCount[layer]++;
	}

	private ByteBuffer ensure(int layer) {
		ByteBuffer buf = buffers[layer];
		if (buf == null) {
			buf = MemoryUtil.memAlloc(INITIAL_VERTICES * TerrainVertex.STRIDE).order(ByteOrder.LITTLE_ENDIAN);
			buffers[layer] = buf;
		} else if (buf.remaining() < TerrainVertex.STRIDE) {
			ByteBuffer bigger = MemoryUtil.memAlloc(buf.capacity() * 2).order(ByteOrder.LITTLE_ENDIAN);
			buf.flip();
			bigger.put(buf);
			MemoryUtil.memFree(buf); // release old native buffer immediately
			buffers[layer] = bigger;
			buf = bigger;
		}
		return buf;
	}

	public int vertexCount(int layer) {
		return vertexCount[layer];
	}

	public void addPlacedLight(int localX, int localY, int localZ, int level,
			float red, float green, float blue, boolean solidEmitter, int exposedFaceMask) {
		placedLightTotal++;
		if (placedLightCount >= MAX_PLACED_LIGHTS) return;
		if (placedLights == null) {
			placedLights = new int[4];
			placedLightFaceMasks = new byte[4];
		} else if (placedLightCount == placedLights.length) {
			int nextLength = Math.min(MAX_PLACED_LIGHTS, placedLights.length << 1);
			placedLights = java.util.Arrays.copyOf(placedLights, nextLength);
			placedLightFaceMasks = java.util.Arrays.copyOf(placedLightFaceMasks, nextLength);
		}
		int r = Math.max(0, Math.min(31, Math.round(red * 31f)));
		int g = Math.max(0, Math.min(63, Math.round(green * 63f)));
		int b = Math.max(0, Math.min(31, Math.round(blue * 31f)));
		int rgb565 = r << 11 | g << 5 | b;
		if (solidEmitter) placedLightSolidMask |= 1L << placedLightCount;
		placedLightFaceMasks[placedLightCount] = (byte) (exposedFaceMask & 0x3F);
		placedLights[placedLightCount++] = rgb565 << 16 | (level & 15) << 12 | (localY & 15) << 8
				| (localZ & 15) << 4 | localX & 15;
	}

	public static float placedLightRed(int packed) { return (packed >>> 27 & 31) / 31f; }
	public static float placedLightGreen(int packed) { return (packed >>> 21 & 63) / 63f; }
	public static float placedLightBlue(int packed) { return (packed >>> 16 & 31) / 31f; }

	public int[] placedLights() { return placedLights; }
	public int placedLightCount() { return placedLightCount; }
	public int placedLightTotal() { return placedLightTotal; }
	public long placedLightSolidMask() { return placedLightSolidMask; }
	public byte[] placedLightFaceMasks() { return placedLightFaceMasks; }

	public void markOpaqueVoxel(int index) {
		if (opaqueVoxels == null) opaqueVoxels = new long[64];
		opaqueVoxels[index >>> 6] |= 1L << (index & 63);
	}

	public long[] opaqueVoxels() { return opaqueVoxels; }

	public boolean isEmpty() {
		for (int c : vertexCount) {
			if (c > 0) {
				return false;
			}
		}
		return true;
	}

	/** A read-only, position-0..limit view of a layer's vertex bytes, ready for GL upload. */
	public ByteBuffer buffer(int layer) {
		ByteBuffer buf = buffers[layer];
		if (buf == null) {
			return null;
		}
		ByteBuffer view = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN);
		view.flip();
		return view;
	}

	public void reset() {
		for (int i = 0; i < LAYERS; i++) {
			if (buffers[i] != null) {
				buffers[i].clear();
			}
			vertexCount[i] = 0;
		}
		translucentQuadCentroids = null;
		translucentMixedWater = false;
		translucentOnlyWater = false;
		translucentMetadataPrepared = false;
		placedLights = null;
		placedLightFaceMasks = null;
		placedLightCount = 0;
		placedLightTotal = 0;
		placedLightSolidMask = 0L;
		opaqueVoxels = null;
	}

	/**
	 * Worker-side preprocessing consumed by the translucent upload and sort path. The data depends
	 * only on immutable mesh bytes, so doing it here keeps mesh-size-proportional CPU work away from
	 * the render thread during chunk bursts.
	 */
	public void prepareUploadMetadata() {
		if (translucentMetadataPrepared) return;
		translucentMetadataPrepared = true;
		if (placedLights != null && placedLights.length != placedLightCount) {
			placedLights = java.util.Arrays.copyOf(placedLights, placedLightCount);
			placedLightFaceMasks = java.util.Arrays.copyOf(placedLightFaceMasks, placedLightCount);
		}
		int vertices = vertexCount[LAYER_TRANSLUCENT];
		ByteBuffer buffer = buffers[LAYER_TRANSLUCENT];
		if (vertices <= 0 || buffer == null) return;
		int quads = vertices / 4;
		translucentQuadCentroids = new float[quads * 3];
		boolean water = false;
		boolean nonWater = false;
		boolean waterTopOnly = true;
		for (int q = 0; q < quads; q++) {
			float sx = 0f, sy = 0f, sz = 0f;
			int vertexStart = q * 4;
			for (int k = 0; k < 4; k++) {
				int offset = (vertexStart + k) * TerrainVertex.STRIDE;
				sx += TerrainVertex.decodePos(buffer.getShort(offset) & 0xFFFF);
				sy += TerrainVertex.decodePos(buffer.getShort(offset + 2) & 0xFFFF);
				sz += TerrainVertex.decodePos(buffer.getShort(offset + 4) & 0xFFFF);
			}
			translucentQuadCentroids[q * 3] = sx * 0.25f;
			translucentQuadCentroids[q * 3 + 1] = sy * 0.25f;
			translucentQuadCentroids[q * 3 + 2] = sz * 0.25f;
			int material = buffer.get(vertexStart * TerrainVertex.STRIDE + TerrainVertex.OFF_MATERIAL) & 0xFF;
			if (McSectionMesher.isWaterMaterial(material)) {
				water = true;
				if (buffer.get(vertexStart * TerrainVertex.STRIDE + TerrainVertex.OFF_NORMAL + 1) < 120) {
					waterTopOnly = false;
				}
			}
			else nonWater = true;
		}
		translucentMixedWater = water && nonWater;
		translucentOnlyWater = water && !nonWater && waterTopOnly;
	}

	public float[] translucentQuadCentroids() { return translucentQuadCentroids; }
	public boolean translucentMixedWater() { return translucentMixedWater; }
	public boolean translucentOnlyWater() { return translucentOnlyWater; }

	/** Release all native buffers. Call after the data has been uploaded to GL. */
	public void free() {
		for (int i = 0; i < LAYERS; i++) {
			if (buffers[i] != null) {
				MemoryUtil.memFree(buffers[i]);
				buffers[i] = null;
			}
		}
	}

	public static void main(String[] args) {
		SectionMeshData m = new SectionMeshData();
		int n = 1000; // forces several grows past INITIAL_VERTICES
		for (int i = 0; i < n; i++) {
			m.putVertex(LAYER_SOLID, i % 16, 1, 2, 0.5f, 0.5f, 255, 255, 255, 255, 15, 15, 0, 1, 0, 0, 0, 1f, 0f);
		}
		m.putVertex(LAYER_TRANSLUCENT, 0, 0, 0, 0, 0, 0, 0, 255, 128, 12, 4, 0, 0, 1, 2, 0, 1f, 0f);

		ByteBuffer solid = m.buffer(LAYER_SOLID);
		boolean ok = m.vertexCount(LAYER_SOLID) == n
				&& m.vertexCount(LAYER_TRANSLUCENT) == 1
				&& m.vertexCount(LAYER_CUTOUT) == 0
				&& !m.isEmpty()
				&& solid != null && solid.remaining() == n * TerrainVertex.STRIDE;
		System.out.printf("solid=%d cutout=%d translucent=%d bytes=%d%n",
				m.vertexCount(LAYER_SOLID), m.vertexCount(LAYER_CUTOUT), m.vertexCount(LAYER_TRANSLUCENT),
				solid == null ? 0 : solid.remaining());
		System.out.println(ok ? "SELF-TEST PASS" : "SELF-TEST FAIL");
		if (!ok) {
			System.exit(1);
		}
	}
}
