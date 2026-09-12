package com.moneyakshaders.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class RenderTaskDeduplicator {
	private static final Map<String, Long> lastScheduled = new ConcurrentHashMap<>();

	private RenderTaskDeduplicator() {
	}

	public static boolean shouldSkipDuplicate(int x, int y, int z, int windowMs) {
		return shouldSkip(buildChunkKey(x, y, z), windowMs);
	}

	public static boolean shouldSkipDuplicateRegion(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int windowMs) {
		return shouldSkip(buildRegionKey(minX, minY, minZ, maxX, maxY, maxZ), windowMs);
	}

	public static boolean shouldSkipDuplicateObject(Object object, int windowMs) {
		return shouldSkip(buildObjectKey(object), windowMs);
	}

	public static boolean shouldSkipDuplicateSection(int x, int y, int z, int windowMs) {
		return shouldSkip(buildSectionKey(x, y, z), windowMs);
	}

	private static boolean shouldSkip(String key, int windowMs) {
		long now = System.currentTimeMillis();
		Long previous = lastScheduled.get(key);
		if (previous != null && now - previous < windowMs) {
			return true;
		}
		lastScheduled.put(key, now);
		return false;
	}

	private static String buildChunkKey(int x, int y, int z) {
		return "chunk:" + x + ',' + y + ',' + z;
	}

	private static String buildRegionKey(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
		return "region:" + minX + ',' + minY + ',' + minZ + ',' + maxX + ',' + maxY + ',' + maxZ;
	}

	private static String buildSectionKey(int x, int y, int z) {
		return "section:" + x + ',' + y + ',' + z;
	}

	private static String buildObjectKey(Object object) {
		if (object == null) {
			return "object:null";
		}
		return "object:" + object.getClass().getName() + '@' + System.identityHashCode(object);
	}
}
