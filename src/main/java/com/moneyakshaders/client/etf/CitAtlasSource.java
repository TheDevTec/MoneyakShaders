package com.moneyakshaders.client.etf;

import com.mojang.serialization.MapCodec;

import net.minecraft.client.texture.atlas.AtlasSource;
import net.minecraft.client.texture.atlas.DirectoryAtlasSource;
import net.minecraft.resource.ResourceFinder;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;

/**
 * Atlas source for OptiFine CIT textures. Vanilla's {@link DirectoryAtlasSource} hardwires the
 * {@code textures/} root, but CIT packs keep their art at {@code assets/<ns>/optifine/cit/**} —
 * outside it — so a directory source can never see them. This source scans the pack root path
 * directly and registers each png under its full path as the sprite id (e.g.
 * {@code minecraft:optifine/cit/excalibur}), which is what {@link CitEngine} looks up.
 */
public record CitAtlasSource(String root) implements AtlasSource {
	@Override
	public void load(ResourceManager resourceManager, AtlasSource.SpriteRegions regions) {
		ResourceFinder finder = new ResourceFinder(root, ".png");
		int[] n = { 0 };
		finder.findResources(resourceManager).forEach((id, resource) -> {
			Identifier spriteId = finder.toResourceId(id).withPrefixedPath(root + "/");
			regions.add(spriteId, resource);
			n[0]++;
		});
		if (com.moneyakshaders.MoneyakShadersConfig.get().debugCit && n[0] > 0) {
			com.moneyakshaders.MoneyakShaders.LOGGER.info(
					"[Optimized Loading/CIT] stitched {} textures from {}/** into the items atlas", n[0], root);
		}
	}

	@Override
	public MapCodec<? extends AtlasSource> getCodec() {
		// Only used when serializing atlas JSONs — this source is injected programmatically and
		// never written back, so borrowing the directory codec is safe.
		return DirectoryAtlasSource.CODEC;
	}
}
