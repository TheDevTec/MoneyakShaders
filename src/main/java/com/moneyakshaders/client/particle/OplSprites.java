package com.moneyakshaders.client.particle;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.util.Identifier;

/**
 * Direct particle-atlas sprite lookup. The particles atlas uses a DIRECTORY source, so every
 * {@code textures/particle/*.png} in the mod's namespace is stitched automatically — fetching by
 * identifier here avoids the whole SpriteAwareFactory/particles-json binding (whose SpriteProvider
 * arrived unbound in practice and NPE'd on first spawn).
 */
public final class OplSprites {
	private OplSprites() {
	}

	private static final java.util.Set<String> LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/** Sprite from the particles atlas, or null when the atlas isn't ready yet. */
	public static Sprite get(String path) {
		if (MinecraftClient.getInstance().getTextureManager()
				.getTexture(SpriteAtlasTexture.PARTICLE_ATLAS_TEXTURE) instanceof SpriteAtlasTexture atlas) {
			Sprite s = atlas.getSprite(Identifier.of("moneyakshaders", path));
			if (LOGGED.add(path)) {
				com.moneyakshaders.MoneyakShaders.LOGGER.info("[OPL] particle sprite {} -> {}",
						path, s == null ? "null" : s.getContents().getId());
			}
			return s;
		}
		if (LOGGED.add("!atlas")) {
			com.moneyakshaders.MoneyakShaders.LOGGER.info("[OPL] particles atlas not a SpriteAtlasTexture");
		}
		return null;
	}
}
