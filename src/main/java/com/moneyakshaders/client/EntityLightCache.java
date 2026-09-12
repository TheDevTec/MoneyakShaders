package com.moneyakshaders.client;

import com.moneyakshaders.MoneyakShadersConfig;

import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;

/**
 * Short-lived cache of per-entity packed light levels (RAM for FPS).
 *
 * <p>Every rendered entity asks the light engine for block + sky light every
 * single frame ({@code EntityRenderer.getLight}). In entity-dense scenes (mob
 * farms, item-frame image walls, head/banner props) that is thousands of
 * light-engine queries per frame. Light around an entity barely changes within
 * ~100 ms even while it moves, so a short time window removes almost all of
 * those queries with no visible difference. Players deliberately bypass this
 * cache: there are very few of them and delayed light changes on the local or
 * another player's model are much easier to notice.
 *
 * <p>Validity is purely time-based - the earlier per-block invalidation made
 * the cache useless for moving entities (every mob in a farm crosses block
 * boundaries constantly, so it missed every frame). All access is on the
 * render thread, so no locking is needed.
 */
public final class EntityLightCache {
	private static final int SWEEP_THRESHOLD = 4096;
	private static final long SWEEP_INTERVAL_NANOS = 1_000_000_000L;

	private static final Reference2ObjectOpenHashMap<Entity, Entry> CACHE = new Reference2ObjectOpenHashMap<>();
	private static long lastSweepNanos;

	private static final class Entry {
		int light;
		int blockX;
		int blockY;
		int blockZ;
		long expiresAtNanos;
	}

	private EntityLightCache() {
	}

	/**
	 * @return cached packed light, or -1 when there is no fresh entry
	 */
	public static int get(Entity entity) {
		if (entity instanceof PlayerEntity) {
			return -1;
		}
		MoneyakShadersConfig cfg = MoneyakShadersConfig.get();
		if (cfg.entityLightCacheMs <= 0) {
			return -1;
		}
		Entry entry = CACHE.get(entity);
		if (entry == null
				|| entity.getBlockX() != entry.blockX
				|| entity.getBlockY() != entry.blockY
				|| entity.getBlockZ() != entry.blockZ
				|| System.nanoTime() > entry.expiresAtNanos) {
			if (cfg.debugStats) {
				DebugStats.entLightMiss.incrementAndGet();
			}
			return -1;
		}
		if (cfg.debugStats) {
			DebugStats.entLightHit.incrementAndGet();
		}
		return entry.light;
	}

	public static void store(Entity entity, int light) {
		if (entity instanceof PlayerEntity) {
			return;
		}
		int windowMs = MoneyakShadersConfig.get().entityLightCacheMs;
		if (windowMs <= 0) {
			return;
		}
		long now = System.nanoTime();
		Entry entry = CACHE.get(entity);
		if (entry == null) {
			entry = new Entry();
			CACHE.put(entity, entry);
		}
		entry.light = light;
		entry.blockX = entity.getBlockX();
		entry.blockY = entity.getBlockY();
		entry.blockZ = entity.getBlockZ();
		// Entities extracted in one frame used to expire in one later frame, producing both a light
		// query spike and the impression that every prop flashed together. A stable per-entity phase
		// spreads refreshes over roughly 75..125% of the configured window without temporal noise.
		int phase = Math.floorMod(entity.getId() * 0x9E3779B9, 51) - 25;
		long staggeredWindowNs = windowMs * 1_000_000L * (100L + phase) / 100L;
		entry.expiresAtNanos = now + Math.max(1_000_000L, staggeredWindowNs);

		if (CACHE.size() > SWEEP_THRESHOLD && now - lastSweepNanos > SWEEP_INTERVAL_NANOS) {
			lastSweepNanos = now;
			CACHE.values().removeIf(e -> now > e.expiresAtNanos);
		}
	}

	public static void clear() {
		CACHE.clear();
	}
}
