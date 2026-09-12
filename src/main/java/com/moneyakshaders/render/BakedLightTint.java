package com.moneyakshaders.render;

import com.moneyakshaders.client.DynamicLightSources;

import net.minecraft.block.BlockState;
import net.minecraft.world.BlockRenderView;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;

/**
 * Baked coloured block-light: the mesher gathers every luminous block that can reach the section
 * (its own chunk ± 1, vertically ± 1 section — block light travels ≤ 15 blocks) ONCE per mesh, and
 * bakes a per-vertex light tint (hue + strength) into the vertex data. The fragment shader then just
 * reads the varying — no per-frame source scanning, no per-fragment light loop for placed lights,
 * no range or count limits. Placing/removing a light re-meshes the affected sections via the normal
 * light-update → dirty path, which re-bakes the tint.
 *
 * <p>The gather is cheap: {@link ChunkSection#hasAny} is a palette check, so sections with no
 * luminous block types are skipped without iterating any blocks. Runs on the mesh worker threads;
 * per-mesh state is a ThreadLocal so no signatures needed threading through the mesher.
 */
public final class BakedLightTint {
	private static final int CLUSTER_AXIS = 4;
	private static final int CLUSTER_COUNT = CLUSTER_AXIS * CLUSTER_AXIS * CLUSTER_AXIS;
	private static final int MAX_VERTEX_SOURCES = 32;
	private static final int MAX_SOURCES_PER_CLUSTER = 16;
	// per source: x, y, z, rangeSq, hueR, hueG, hueB
	private static final ThreadLocal<float[]> SRC = ThreadLocal.withInitial(() -> new float[128 * 7]);
	private static final ThreadLocal<int[]> SRC_N = ThreadLocal.withInitial(() -> new int[1]);
	private static final ThreadLocal<float[]> SELECT_SCORES = ThreadLocal.withInitial(() -> new float[MAX_VERTEX_SOURCES]);
	private static final class ClusterScratch {
		final byte[] counts = new byte[CLUSTER_COUNT];
		final int[] sourceIndices = new int[CLUSTER_COUNT * MAX_SOURCES_PER_CLUSTER];
		final float[] scores = new float[CLUSTER_COUNT * MAX_SOURCES_PER_CLUSTER];
		int baseX, baseY, baseZ;
	}
	private static final ThreadLocal<ClusterScratch> CLUSTERS = ThreadLocal.withInitial(ClusterScratch::new);

	private BakedLightTint() {
	}

	/** Gather luminous blocks reaching the section at (baseX,baseY,baseZ). Call once per mesh(). */
	public static void begin(BlockRenderView view, int baseX, int baseY, int baseZ) {
		int[] nRef = SRC_N.get();
		nRef[0] = 0;
		if (view instanceof SectionInputSnapshot snapshot) {
			int count = snapshot.lightSourceCount;
			if (count > 0) {
				float[] src = ensureSourceCapacity(count);
				System.arraycopy(snapshot.lightSources, 0, src, 0, count * 7);
				nRef[0] = count;
				limitToSectionRelevantSources(baseX, baseY, baseZ, nRef);
			}
			buildClusters(baseX, baseY, baseZ, nRef[0]);
			return;
		}
		if (!(view instanceof World w)) {
			return;
		}
		float[] src = SRC.get();
		int n = 0;
		int ccx = baseX >> 4, ccz = baseZ >> 4;
		for (int dcx = -1; dcx <= 1; dcx++) {
			for (int dcz = -1; dcz <= 1; dcz++) {
				Chunk chunk = w.getChunk(ccx + dcx, ccz + dcz, ChunkStatus.FULL, false);
				if (chunk == null) {
					continue;
				}
				ChunkSection[] arr = chunk.getSectionArray();
				for (int dsy = -1; dsy <= 1; dsy++) {
					int sy = (baseY >> 4) + dsy;
					int idx = chunk.getSectionIndex(sy << 4);
					if (idx < 0 || idx >= arr.length) {
						continue;
					}
					ChunkSection sec = arr[idx];
					if (sec == null || sec.isEmpty() || !sec.hasAny(s -> s.getLuminance() > 0)) {
						continue; // palette check — no luminous block TYPE in this section at all
					}
					int bx0 = (ccx + dcx) << 4, by0 = sy << 4, bz0 = (ccz + dcz) << 4;
					for (int lx = 0; lx < 16; lx++) {
						for (int ly = 0; ly < 16; ly++) {
							for (int lz = 0; lz < 16; lz++) {
								BlockState st = sec.getBlockState(lx, ly, lz);
								int lum = st.getLuminance();
								if (lum <= 0) {
									continue;
								}
								float[] c = DynamicLightSources.blockColor(st.getBlock());
								float mx = Math.max(c[0], Math.max(c[1], c[2]));
								if (mx <= 0.001f) {
									continue;
								}
								src = ensureSourceCapacity(n + 1);
								int o = n * 7;
								src[o] = bx0 + lx + 0.5f;
								src[o + 1] = by0 + ly + 0.5f;
								src[o + 2] = bz0 + lz + 0.5f;
								src[o + 3] = (float) lum * lum; // range² (range = luminance, like the live lights)
								src[o + 4] = c[0] / mx;         // normalized hue
								src[o + 5] = c[1] / mx;
								src[o + 6] = c[2] / mx;
								n++;
							}
						}
					}
				}
			}
		}
		nRef[0] = n;
		limitToSectionRelevantSources(baseX, baseY, baseZ, nRef);
		buildClusters(baseX, baseY, baseZ, nRef[0]);
	}

	private static float[] ensureSourceCapacity(int sourceCount) {
		float[] src = SRC.get();
		int floats = sourceCount * 7;
		if (floats > src.length) {
			src = java.util.Arrays.copyOf(src, Math.max(floats, src.length + (src.length >> 1)));
			SRC.set(src);
		}
		return src;
	}

	private static void limitToSectionRelevantSources(int baseX, int baseY, int baseZ, int[] nRef) {
		int sourceCount = nRef[0];
		if (sourceCount <= MAX_VERTEX_SOURCES) return;
		float[] src = SRC.get();
		float[] scores = SELECT_SCORES.get();
		int kept = 0;
		for (int i = 0; i < sourceCount; i++) {
			int sourceOffset = i * 7;
			float score = sectionInfluence(src, sourceOffset, baseX, baseY, baseZ);
			if (score <= 0f) continue;
			if (kept < MAX_VERTEX_SOURCES) {
				if (kept != i) System.arraycopy(src, sourceOffset, src, kept * 7, 7);
				scores[kept++] = score;
				continue;
			}
			int weakest = 0;
			for (int k = 1; k < kept; k++) if (scores[k] < scores[weakest]) weakest = k;
			if (score > scores[weakest]) {
				System.arraycopy(src, sourceOffset, src, weakest * 7, 7);
				scores[weakest] = score;
			}
		}
		nRef[0] = kept;
	}

	private static float sectionInfluence(float[] src, int offset, int baseX, int baseY, int baseZ) {
		float x = src[offset], y = src[offset + 1], z = src[offset + 2];
		float dx = x < baseX ? baseX - x : Math.max(0f, x - (baseX + 16f));
		float dy = y < baseY ? baseY - y : Math.max(0f, y - (baseY + 16f));
		float dz = z < baseZ ? baseZ - z : Math.max(0f, z - (baseZ + 16f));
		float rangeSq = src[offset + 3];
		float distanceSq = dx * dx + dy * dy + dz * dz;
		return distanceSq >= rangeSq ? 0f : rangeSq - distanceSq;
	}

	/** Build a bounded 4×4×4 local list once per mesh after the complete source set was considered. */
	private static void buildClusters(int baseX, int baseY, int baseZ, int sourceCount) {
		ClusterScratch clusters = CLUSTERS.get();
		clusters.baseX = baseX; clusters.baseY = baseY; clusters.baseZ = baseZ;
		java.util.Arrays.fill(clusters.counts, (byte) 0);
		if (sourceCount == 0) return;
		float[] src = SRC.get();
		for (int cell = 0; cell < CLUSTER_COUNT; cell++) {
			int cx = cell & 3, cz = (cell >>> 2) & 3, cy = cell >>> 4;
			int cellX = baseX + (cx << 2), cellY = baseY + (cy << 2), cellZ = baseZ + (cz << 2);
			int clusterBase = cell * MAX_SOURCES_PER_CLUSTER;
			int kept = 0;
			for (int source = 0; source < sourceCount; source++) {
				float score = sectionInfluence(src, source * 7, cellX, cellY, cellZ, 4f);
				if (score <= 0f) continue;
				if (kept < MAX_SOURCES_PER_CLUSTER) {
					clusters.sourceIndices[clusterBase + kept] = source;
					clusters.scores[clusterBase + kept++] = score;
					continue;
				}
				int weakest = 0;
				for (int k = 1; k < kept; k++) {
					if (clusters.scores[clusterBase + k] < clusters.scores[clusterBase + weakest]) weakest = k;
				}
				if (score > clusters.scores[clusterBase + weakest]) {
					clusters.sourceIndices[clusterBase + weakest] = source;
					clusters.scores[clusterBase + weakest] = score;
				}
			}
			clusters.counts[cell] = (byte) kept;
		}
	}

	private static float sectionInfluence(float[] src, int offset, int baseX, int baseY, int baseZ, float size) {
		float x = src[offset], y = src[offset + 1], z = src[offset + 2];
		float dx = x < baseX ? baseX - x : Math.max(0f, x - (baseX + size));
		float dy = y < baseY ? baseY - y : Math.max(0f, y - (baseY + size));
		float dz = z < baseZ ? baseZ - z : Math.max(0f, z - (baseZ + size));
		float rangeSq = src[offset + 3];
		float distanceSq = dx * dx + dy * dy + dz * dz;
		return distanceSq >= rangeSq ? 0f : rangeSq - distanceSq;
	}

	/** True when this section has no reachable sources — lets callers skip per-vertex work. */
	public static boolean isEmpty() {
		return SRC_N.get()[0] == 0;
	}

	/**
	 * Baked tint at a world position, packed {@code weight<<24 | premulR<<16 | premulG<<8 | premulB}
	 * (0 = no tint). RGB is premultiplied by the packed weight before interpolation. Interpolating an
	 * average hue and its weight independently and multiplying them later creates cross-colour terms
	 * between vertices; those were the moving yellow/green patches where several lamps overlap.
	 * Same falloff as the live point-light shader: att = 1 − d²/r², weight = att² summed per source.
	 */
	public static int tintAt(float wx, float wy, float wz) {
		int n = SRC_N.get()[0];
		if (n == 0) {
			return 0;
		}
		float[] src = SRC.get();
		ClusterScratch clusters = CLUSTERS.get();
		int cellX = Math.max(0, Math.min(3, (int) ((wx - clusters.baseX) * 0.25f)));
		int cellY = Math.max(0, Math.min(3, (int) ((wy - clusters.baseY) * 0.25f)));
		int cellZ = Math.max(0, Math.min(3, (int) ((wz - clusters.baseZ) * 0.25f)));
		int cell = cellX | (cellZ << 2) | (cellY << 4);
		int sourceCount = clusters.counts[cell] & 0xFF;
		int clusterBase = cell * MAX_SOURCES_PER_CLUSTER;
		float hr = 0f, hg = 0f, hb = 0f, wSum = 0f;
		for (int i = 0; i < sourceCount; i++) {
			int o = clusters.sourceIndices[clusterBase + i] * 7;
			float dx = wx - src[o], dy = wy - src[o + 1], dz = wz - src[o + 2];
			float d2 = dx * dx + dy * dy + dz * dz;
			float r2 = src[o + 3];
			if (d2 >= r2) {
				continue;
			}
			float att = 1f - d2 / r2;
			float wgt = att * att;
			hr += src[o + 4] * wgt;
			hg += src[o + 5] * wgt;
			hb += src[o + 6] * wgt;
			wSum += wgt;
		}
		if (wSum <= 0.004f) {
			return 0;
		}
		float strength = Math.min(1f, wSum);
		int r = (int) (hr / wSum * strength * 255f + 0.5f);
		int g = (int) (hg / wSum * strength * 255f + 0.5f);
		int b = (int) (hb / wSum * strength * 255f + 0.5f);
		int wq = (int) (strength * 255f + 0.5f);
		return (wq << 24) | (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, b);
	}
}
