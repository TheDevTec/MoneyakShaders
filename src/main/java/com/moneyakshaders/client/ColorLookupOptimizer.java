package com.moneyakshaders.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Color Lookup Texture (LUT) for stained glass optimization.
 * Replaces individual textures with a single LUT texture + color index.
 *
 * <p>Dramatically reduces texture unit switching for multi-colored glass scenes.
 * Worker threads build LUT palette; main thread samples via index lookup.
 */
public final class ColorLookupOptimizer {
	private static volatile ColorLookupOptimizer instance;

	private final ConcurrentHashMap<String, ColorLUT> lutCache = new ConcurrentHashMap<>();

	private ColorLookupOptimizer() {
	}

	public static ColorLookupOptimizer getInstance() {
		if (instance == null) {
			synchronized (ColorLookupOptimizer.class) {
				if (instance == null) {
					instance = new ColorLookupOptimizer();
				}
			}
		}
		return instance;
	}

	/**
	 * Schedule async color palette LUT building.
	 */
	public void queueLUTBuilding(String lutId, List<GlassColor> colors) {
		ChunkMeshExecutor.executeBackground(() -> buildColorLUT(lutId, colors));
	}

	private void buildColorLUT(String lutId, List<GlassColor> colors) {
		// Create 256x256 LUT texture (256 colors, 256 variations per color)
		int[] lutTexture = new int[256 * 256];
		Map<String, Integer> colorIndices = new HashMap<>();

		int colorIdx = 0;
		for (GlassColor color : colors) {
			if (colorIdx >= 256) {
				break; // Max 256 colors in LUT
			}

			// Store color and build variations (brightness levels)
			int baseColor = color.rgb;
			int r = (baseColor >> 16) & 0xFF;
			int g = (baseColor >> 8) & 0xFF;
			int b = baseColor & 0xFF;

			// Build column for this color (256 variations)
			for (int variation = 0; variation < 256; variation++) {
				float brightness = (float) variation / 255.0f;

				int lutR = (int) (r * brightness);
				int lutG = (int) (g * brightness);
				int lutB = (int) (b * brightness);
				int lutA = 255; // Full alpha

				int lutColor = (lutA << 24) | (lutR << 16) | (lutG << 8) | lutB;
				lutTexture[colorIdx * 256 + variation] = lutColor;
			}

			colorIndices.put(color.name, colorIdx);
			colorIdx++;
		}

		lutCache.put(lutId, new ColorLUT(lutTexture, colorIndices, colors.size()));
	}

	/**
	 * Get color LUT (main thread safe, cached).
	 */
	public ColorLUT getLUT(String lutId) {
		return lutCache.get(lutId);
	}

	/**
	 * Check if LUT is ready (non-blocking).
	 */
	public boolean isLUTReady(String lutId) {
		return lutCache.containsKey(lutId);
	}

	/**
	 * Get color index in LUT (main thread safe).
	 */
	public int getColorIndex(String lutId, String colorName) {
		ColorLUT lut = getLUT(lutId);
		if (lut == null) {
			return 0;
		}
		return lut.colorIndices.getOrDefault(colorName, 0);
	}

	/**
	 * Clear cache to free memory.
	 */
	public void clearCache() {
		lutCache.clear();
	}

	/**
	 * Color Lookup Table (immutable).
	 */
	public static final class ColorLUT {
		public final int[] lutTexture;
		public final Map<String, Integer> colorIndices;
		public final int colorCount;

		public ColorLUT(int[] lutTexture, Map<String, Integer> colorIndices, int colorCount) {
			this.lutTexture = lutTexture;
			this.colorIndices = colorIndices;
			this.colorCount = colorCount;
		}

		public int getTextureSize() {
			return 256 * 256 * 4; // 256x256 * 4 bytes per pixel
		}

		public int getCompressionRatio() {
			// How many separate textures we replaced with this LUT
			return colorCount;
		}
	}

	/**
	 * Glass color definition.
	 */
	public static final class GlassColor {
		public final String name;
		public final int rgb;
		public final float alpha;

		public GlassColor(String name, int rgb, float alpha) {
			this.name = name;
			this.rgb = rgb;
			this.alpha = alpha;
		}

		public static GlassColor createFromName(String name) {
			// Map Minecraft glass names to RGB values
			Map<String, Integer> colorMap = new HashMap<>();
			colorMap.put("white", 0xFFFFFF);
			colorMap.put("orange", 0xFF7F00);
			colorMap.put("magenta", 0xFF00FF);
			colorMap.put("light_blue", 0x00FFFF);
			colorMap.put("yellow", 0xFFFF00);
			colorMap.put("lime", 0x00FF00);
			colorMap.put("pink", 0xFF69B4);
			colorMap.put("gray", 0x808080);
			colorMap.put("light_gray", 0xC0C0C0);
			colorMap.put("cyan", 0x00FFFF);
			colorMap.put("purple", 0x800080);
			colorMap.put("blue", 0x0000FF);
			colorMap.put("brown", 0x8B4513);
			colorMap.put("green", 0x008000);
			colorMap.put("red", 0xFF0000);
			colorMap.put("black", 0x000000);

			int rgb = colorMap.getOrDefault(name, 0xFFFFFF);
			return new GlassColor(name, rgb, 0.8f); // 80% transparency
		}
	}
}
