package com.moneyakshaders.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.moneyakshaders.MoneyakShadersConfig;

import net.minecraft.client.texture.atlas.AtlasLoader;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Atlases;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * ITEMS-atlas source injection, two jobs:
 * <ol>
 *   <li>CIT: stitches every pack's {@code optifine/cit/**.png} (+ mcpatcher) so CIT rules can remap
 *       item quads onto them ({@link com.moneyakshaders.client.etf.CitAtlasSource} — CIT art lives
 *       outside {@code textures/}, which a plain DirectoryAtlasSource can't reach).</li>
 *   <li>Old-pack item fix: pre-1.21.4 packs declare their custom texture folders only in
 *       {@code atlases/blocks.json} (items and blocks shared one atlas back then). Since the items
 *       atlas split, their item models resolve some sprites from the blocks atlas → "Multiple
 *       atlases used in model" bake failure → purple-black items. Mirroring the vanilla
 *       {@code block/} directory plus every NON-vanilla pack's blocks.json sources into the items
 *       atlas lets those models resolve everything from one atlas again.</li>
 * </ol>
 */
@Mixin(AtlasLoader.class)
public abstract class AtlasLoaderCitMixin {
	@ModifyReturnValue(method = "of", at = @At("RETURN"), require = 0)
	private static AtlasLoader moneyakshaders$addCitSources(AtlasLoader loader,
			ResourceManager resourceManager, Identifier atlasId) {
		if (!Atlases.ITEMS.equals(atlasId) || !(loader instanceof AtlasLoaderAccessor acc)) {
			return loader;
		}
		MoneyakShadersConfig cfg = MoneyakShadersConfig.get();
		if (cfg.citTextures) {
			acc.moneyakshaders$sources().add(new com.moneyakshaders.client.etf.CitAtlasSource("optifine/cit"));
			acc.moneyakshaders$sources().add(new com.moneyakshaders.client.etf.CitAtlasSource("mcpatcher/cit"));
		}
		// NOTE (tried & reverted): mirroring the block/ directory + pack blocks.json sources into
		// the ITEMS atlas broke EVERY block model — duplicating a sprite id across the two atlases
		// makes the combined block_or_item resolver flip block models onto the items atlas →
		// "Multiple atlases used in model" for the whole world. The old-pack item fix needs a
		// different seam (item-model bake resolver), not atlas stuffing.
		return loader;
	}
}
