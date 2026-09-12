package com.moneyakshaders.render;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * Spec §10.4: split shadow casters into static (terrain + baked models) and dynamic (entities,
 * animated block entities, recently-changed blocks) layers. A moving entity dirties only the
 * dynamic layer so the static cascade is not re-rasterised every frame. No callers today.
 */
public final class StaticDynamicCasters {
	public static final int STATIC_QUIET_FRAMES = 60;

	private static final Long2LongOpenHashMap LAST_DIRTY_FRAME = new Long2LongOpenHashMap();
	private static final LongOpenHashSet DYNAMIC_ENTITIES_SECTIONS = new LongOpenHashSet();
	private static volatile long currentFrame;

	private StaticDynamicCasters() {
	}

	static { LAST_DIRTY_FRAME.defaultReturnValue(Long.MIN_VALUE); }

	public static void beginFrame() {
		currentFrame++;
		DYNAMIC_ENTITIES_SECTIONS.clear();
	}

	public static synchronized void markDirty(long sectionKey) {
		LAST_DIRTY_FRAME.put(sectionKey, currentFrame);
	}

	public static synchronized void markEntitySection(long sectionKey) {
		DYNAMIC_ENTITIES_SECTIONS.add(sectionKey);
	}

	public static synchronized boolean isDynamic(long sectionKey) {
		if (DYNAMIC_ENTITIES_SECTIONS.contains(sectionKey)) return true;
		long last = LAST_DIRTY_FRAME.get(sectionKey);
		if (last == Long.MIN_VALUE) return false;
		return currentFrame - last < STATIC_QUIET_FRAMES;
	}

	public static long currentFrame() {
		return currentFrame;
	}

	public static synchronized int trackedSections() {
		return LAST_DIRTY_FRAME.size();
	}
}
