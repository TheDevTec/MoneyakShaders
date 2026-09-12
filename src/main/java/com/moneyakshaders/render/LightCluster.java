package com.moneyakshaders.render;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * Spec §8.2: spatial cluster grid for local lights. World bucketed into {@code CELL} blocks;
 * every light lands in every cell its radius overlaps. Consumers iterate only their cluster's
 * light list, capped at {@link #MAX_PER_CLUSTER} (brightest N kept). No callers today.
 */
public final class LightCluster {
	public static final int CELL = 16;
	public static final int MAX_PER_CLUSTER = 24;

	public static final class Light {
		public final int id;
		public final float x, y, z;
		public final float r, g, b;
		public final float intensity;
		public final float radius;
		public final long resourceGeneration;

		public Light(int id, float x, float y, float z, float r, float g, float b,
				float intensity, float radius, long resourceGeneration) {
			this.id = id;
			this.x = x; this.y = y; this.z = z;
			this.r = r; this.g = g; this.b = b;
			this.intensity = intensity;
			this.radius = radius;
			this.resourceGeneration = resourceGeneration;
		}
	}

	private final Long2ObjectOpenHashMap<int[]> cellToIds = new Long2ObjectOpenHashMap<>();
	private final Long2ObjectOpenHashMap<Light> lights = new Long2ObjectOpenHashMap<>();
	private int nextId;

	public int register(Light template) {
		int id = ++nextId;
		Light l = new Light(id, template.x, template.y, template.z, template.r, template.g, template.b,
				template.intensity, template.radius, template.resourceGeneration);
		lights.put(id, l);
		distribute(l);
		return id;
	}

	public void clear() {
		cellToIds.clear();
		lights.clear();
		nextId = 0;
	}

	public int[] idsInCell(int cx, int cy, int cz) {
		int[] arr = cellToIds.get(cellKey(cx, cy, cz));
		return arr == null ? EMPTY : arr;
	}

	public Light get(int id) {
		return lights.get(id);
	}

	private void distribute(Light l) {
		int r = Math.max(1, (int) Math.ceil(l.radius / CELL));
		int cx0 = (int) Math.floor(l.x / CELL);
		int cy0 = (int) Math.floor(l.y / CELL);
		int cz0 = (int) Math.floor(l.z / CELL);
		for (int dz = -r; dz <= r; dz++) {
			for (int dy = -r; dy <= r; dy++) {
				for (int dx = -r; dx <= r; dx++) {
					long key = cellKey(cx0 + dx, cy0 + dy, cz0 + dz);
					int[] arr = cellToIds.get(key);
					if (arr == null) {
						cellToIds.put(key, new int[] { l.id });
					} else if (arr.length < MAX_PER_CLUSTER) {
						int[] grown = new int[arr.length + 1];
						System.arraycopy(arr, 0, grown, 0, arr.length);
						grown[arr.length] = l.id;
						cellToIds.put(key, grown);
					} else {
						replaceWeakest(arr, l);
					}
				}
			}
		}
	}

	private void replaceWeakest(int[] arr, Light incoming) {
		int weakestIdx = 0;
		float weakestScore = Float.MAX_VALUE;
		for (int i = 0; i < arr.length; i++) {
			Light candidate = lights.get(arr[i]);
			float score = candidate == null ? Float.MIN_VALUE : candidate.intensity;
			if (score < weakestScore) {
				weakestScore = score;
				weakestIdx = i;
			}
		}
		if (incoming.intensity > weakestScore) {
			arr[weakestIdx] = incoming.id;
		}
	}

	private static long cellKey(int cx, int cy, int cz) {
		return ((long) (cx & 0x3FFFFFF) << 38) | ((long) (cz & 0x3FFFFFF) << 12) | (long) (cy & 0xFFF);
	}

	private static final int[] EMPTY = new int[0];
}
