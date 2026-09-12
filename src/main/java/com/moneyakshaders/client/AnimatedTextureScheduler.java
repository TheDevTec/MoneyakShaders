package com.moneyakshaders.client;

import java.util.IdentityHashMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.moneyakshaders.MoneyakShadersConfig;

import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.texture.SpriteContents;
import net.minecraft.util.Identifier;

/**
 * Render-thread scheduler for atlas animation blits.
 *
 * <p>Minecraft 1.21.11 already caches every decoded animation frame in a GPU texture. The recurring
 * cost is the render-pass blit of every dirty animator into every mip level. This class therefore
 * does not build a duplicate image cache: it phase-spreads large RP sprite blits while small sprites
 * and gameplay-critical fluids remain full-rate. Skipped animators keep ticking and upload their
 * newest frame on the next admitted tick, so no queue or stale native image can accumulate.
 */
public final class AnimatedTextureScheduler {
	private static final Map<SpriteContents.Animator, Entry> ENTRIES = new IdentityHashMap<>();
	private static final Map<Identifier, List<Entry>> ENTRIES_BY_SPRITE = new HashMap<>();
	private static Identifier currentAtlas;
	private static long atlasTick;
	private static long admittedPixels;

	private AnimatedTextureScheduler() {
	}

	public static void register(SpriteContents.Animator animator, Identifier atlas, Identifier sprite,
			int width, int height) {
		if (animator == null) return;
		long hash = sprite == null ? System.identityHashCode(animator) : sprite.hashCode();
		Entry entry = new Entry(atlas, sprite, Math.max(1, width), Math.max(1, height),
				(int) Math.floorMod(hash, 8L));
		ENTRIES.put(animator, entry);
		if (sprite != null) ENTRIES_BY_SPRITE.computeIfAbsent(sprite, ignored -> new ArrayList<>()).add(entry);
	}

	public static void markUsed(Identifier sprite) {
		if (sprite == null) return;
		List<Entry> entries = ENTRIES_BY_SPRITE.get(sprite);
		if (entries != null) for (Entry entry : entries) entry.lastUsedTick = atlasTick;
	}

	public static void beginAtlas(Identifier atlas) {
		currentAtlas = atlas;
		atlasTick++;
		admittedPixels = 0L;
	}

	public static void endAtlas() {
		currentAtlas = null;
	}

	public static boolean allow(SpriteContents.Animator animator) {
		Entry entry = ENTRIES.get(animator);
		if (entry == null || currentAtlas == null || !currentAtlas.equals(entry.atlas)) return true;
		if (entry.decisionTick == atlasTick) return entry.admitted;
		entry.decisionTick = atlasTick;

		MoneyakShadersConfig cfg = MoneyakShadersConfig.get();
		long budget = Math.max(0, cfg.animatedTextureUploadPixelsPerTick);
		if (budget == 0L || critical(entry)) {
			entry.admitted = true;
			return true;
		}

		long pixels = (long) entry.width * entry.height;
		// Account approximately for the complete mip chain processed by SpriteAtlasTexture.
		long mipPixels = pixels + pixels / 3L;
		boolean recentlyUsed = atlasTick - entry.lastUsedTick <= 40L;
		int cadence;
		if (recentlyUsed || pixels <= 128L * 128L) cadence = 1;
		else if (pixels <= 256L * 256L) cadence = 2;
		else if (pixels <= 512L * 512L) cadence = 3;
		else cadence = 4;

		boolean phaseReady = Math.floorMod(atlasTick + entry.phase, cadence) == 0L;
		boolean fits = admittedPixels == 0L || admittedPixels + mipPixels <= budget;
		entry.admitted = phaseReady && fits;
		if (entry.admitted) admittedPixels += mipPixels;
		return entry.admitted;
	}

	public static void clear() {
		ENTRIES.clear();
		ENTRIES_BY_SPRITE.clear();
		currentAtlas = null;
		admittedPixels = 0L;
	}

	private static boolean critical(Entry entry) {
		if (!SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE.equals(entry.atlas) || entry.sprite == null) return false;
		String path = entry.sprite.getPath();
		return path.contains("water_") || path.contains("lava_") || path.contains("fire_")
				|| path.contains("portal") || path.contains("campfire");
	}

	private static final class Entry {
		final Identifier atlas;
		final Identifier sprite;
		final int width;
		final int height;
		final int phase;
		long lastUsedTick = Long.MIN_VALUE / 2L;
		long decisionTick = Long.MIN_VALUE;
		boolean admitted;

		Entry(Identifier atlas, Identifier sprite, int width, int height, int phase) {
			this.atlas = atlas;
			this.sprite = sprite;
			this.width = width;
			this.height = height;
			this.phase = phase;
		}
	}
}
