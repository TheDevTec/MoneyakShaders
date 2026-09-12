package com.moneyakshaders.render;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.moneyakshaders.MoneyakShaders;
import com.moneyakshaders.MoneyakShadersConfig;
import net.minecraft.util.math.ChunkSectionPos;

final class TerrainEditDebug {
	private static final long WINDOW_MS = 1000L;
	private static final ConcurrentHashMap<Long, Entry> ENTRIES = new ConcurrentHashMap<>();
	private static final AtomicLong NEXT_DUMP = new AtomicLong();

	private static final class Entry {
		final AtomicInteger packets = new AtomicInteger(), dirties = new AtomicInteger(), snapshots = new AtomicInteger();
		final AtomicInteger publishes = new AtomicInteger(), stale = new AtomicInteger(), blocked = new AtomicInteger();
		final AtomicInteger retries = new AtomicInteger(), visibilityChanges = new AtomicInteger();
		volatile long lastRevision, lastBuiltRevision, lastLightGeneration;
		volatile int lastX, lastY, lastZ, lastPriority;
		volatile boolean lastProvisional;
	}

	private TerrainEditDebug() {}
	private static boolean enabled() { return MoneyakShadersConfig.get().debugStats; }
	private static Entry entry(long key) { return ENTRIES.computeIfAbsent(key, ignored -> new Entry()); }

	static void block(long key, int x, int y, int z, long revision) {
		if (!enabled()) return;
		Entry e = entry(key); e.packets.incrementAndGet(); e.lastX = x; e.lastY = y; e.lastZ = z; e.lastRevision = revision; dumpIfDue();
	}
	static void dirty(long key, long revision) { if (!enabled()) return; Entry e = entry(key); e.dirties.incrementAndGet(); e.lastRevision = revision; dumpIfDue(); }
	static void snapshot(long key, long revision, int priority, boolean provisional) { if (!enabled()) return; Entry e = entry(key); e.snapshots.incrementAndGet(); e.lastRevision = revision; e.lastPriority = priority; e.lastProvisional = provisional; dumpIfDue(); }
	static void publish(long key, long revision, long lightGeneration, boolean visibilityChanged) { if (!enabled()) return; Entry e = entry(key); e.publishes.incrementAndGet(); if (visibilityChanged) e.visibilityChanges.incrementAndGet(); e.lastBuiltRevision = revision; e.lastRevision = revision; e.lastLightGeneration = lightGeneration; dumpIfDue(); }
	static void stale(long key, long builtRevision, long currentRevision, long lightGeneration) { if (!enabled()) return; Entry e = entry(key); e.stale.incrementAndGet(); e.lastBuiltRevision = builtRevision; e.lastRevision = currentRevision; e.lastLightGeneration = lightGeneration; dumpIfDue(); }
	static void blocked(long key) { if (!enabled()) return; entry(key).blocked.incrementAndGet(); dumpIfDue(); }
	static void retry(long key) { if (!enabled()) return; entry(key).retries.incrementAndGet(); dumpIfDue(); }

	private static void dumpIfDue() {
		long now = System.currentTimeMillis(), due = NEXT_DUMP.get();
		if (now < due || !NEXT_DUMP.compareAndSet(due, now + WINDOW_MS)) return;
		for (Map.Entry<Long, Entry> me : ENTRIES.entrySet()) {
			long key = me.getKey(); Entry e = me.getValue();
			int packets = e.packets.get(), dirty = e.dirties.get(), snap = e.snapshots.get(), pub = e.publishes.get();
			int stale = e.stale.get(), blocked = e.blocked.get(), retry = e.retries.get(), vis = e.visibilityChanges.get();
			if (packets == 0 && dirty == 0 && stale == 0 && blocked == 0 && retry == 0) continue;
			MoneyakShaders.LOGGER.info("[Plan C/EditTrace] sec={}/{}/{} packets={} dirty={} snap={} pub={} stale={} blocked={} retry={} visChange={} rev={} builtRev={} light={} lastBlock={}/{}/{} priority={} provisional={}",
					ChunkSectionPos.unpackX(key), ChunkSectionPos.unpackY(key), ChunkSectionPos.unpackZ(key), packets, dirty, snap, pub, stale, blocked, retry, vis,
					e.lastRevision, e.lastBuiltRevision, e.lastLightGeneration, e.lastX, e.lastY, e.lastZ, e.lastPriority, e.lastProvisional);
		}
		ENTRIES.clear();
	}
}
