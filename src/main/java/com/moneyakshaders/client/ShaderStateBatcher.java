package com.moneyakshaders.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.render.RenderLayer;

/**
 * Minimize Iris shader state changes by grouping chunks by render layer and material type.
 *
 * <p>Reduces texture unit switching and blend state changes during render,
 * improving performance with complex shader pipelines.
 */
public final class ShaderStateBatcher {
	private static volatile ShaderStateBatcher instance;

	private final Map<RenderLayer, ShaderStateGroup> stateGroups = new HashMap<>();
	private RenderLayer lastActiveLayer;
	private int lastTextureUnit = -1;

	private ShaderStateBatcher() {
	}

	public static ShaderStateBatcher getInstance() {
		ShaderStateBatcher result = instance;
		if (result == null) {
			synchronized (ShaderStateBatcher.class) {
				result = instance;
				if (result == null) {
					result = instance = new ShaderStateBatcher();
				}
			}
		}
		return result;
	}

	/**
	 * Record a chunk's render layer and material properties.
	 * Groups chunks to minimize state changes during rendering.
	 */
	public synchronized void recordChunkLayer(RenderLayer layer, int textureUnit) {
		ShaderStateGroup group = stateGroups.computeIfAbsent(layer, ShaderStateGroup::new);
		group.recordTexture(textureUnit);

		// Track state transitions for optimization
		if (layer != lastActiveLayer) {
			lastActiveLayer = layer;
		}
		if (textureUnit != lastTextureUnit) {
			lastTextureUnit = textureUnit;
		}
	}

	/**
	 * Get render order optimized for Iris to minimize state changes.
	 * Returns layers sorted by texture unit to reduce switching.
	 */
	public synchronized RenderLayer[] getOptimizedRenderOrder() {
		return stateGroups.keySet().stream()
				.sorted((a, b) -> Integer.compare(
						stateGroups.get(a).getMostCommonTextureUnit(),
						stateGroups.get(b).getMostCommonTextureUnit()
				))
				.toArray(RenderLayer[]::new);
	}

	public synchronized void reset() {
		stateGroups.clear();
		lastActiveLayer = null;
		lastTextureUnit = -1;
	}

	/**
	 * Track render state for a specific layer.
	 */
	private static final class ShaderStateGroup {
		final RenderLayer layer;
		private int textureUnitCount = 0;
		private int mostCommonTextureUnit = 0;

		ShaderStateGroup(RenderLayer layer) {
			this.layer = layer;
		}

		void recordTexture(int textureUnit) {
			if (textureUnit > mostCommonTextureUnit) {
				mostCommonTextureUnit = textureUnit;
			}
			textureUnitCount++;
		}

		int getMostCommonTextureUnit() {
			return mostCommonTextureUnit;
		}
	}
}
