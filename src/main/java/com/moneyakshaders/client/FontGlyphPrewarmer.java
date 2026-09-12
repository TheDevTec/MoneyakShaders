package com.moneyakshaders.client;

import com.moneyakshaders.mixin.client.TextRendererGlyphAccessor;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Style;

/** Gradually bakes basic Latin, Latin-1 and Latin Extended-A (including Czech) glyphs. */
public final class FontGlyphPrewarmer {
	private static final int FIRST = 32;
	private static final int LAST = 383;
	private static Object lastRenderer;
	private static int next = FIRST;

	private FontGlyphPrewarmer() {
	}

	public static void tick(MinecraftClient client) {
		if (client == null || client.world == null || client.player == null || client.currentScreen != null) return;
		if (lastRenderer != client.textRenderer) {
			lastRenderer = client.textRenderer;
			next = FIRST;
		}
		if (next > LAST) return;
		int codepoint = next++;
		try {
			((TextRendererGlyphAccessor) (Object) client.textRenderer)
					.moneyakshaders$getGlyph(codepoint, Style.EMPTY);
		} catch (Throwable ignored) {
			// Missing/unsupported glyphs are harmless and must not stop the bounded warm-up pass.
		}
	}
}
