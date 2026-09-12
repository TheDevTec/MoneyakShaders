package com.moneyakshaders.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.moneyakshaders.MoneyakShadersConfig;

import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.texture.SpriteContents;
import net.minecraft.client.texture.SpriteLoader;
import net.minecraft.client.texture.TextureStitcher;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Resource packs commonly contain odd-sized sprites (for example 35x35). Vanilla treats their
 * lowest set bit as the atlas-wide mip limit, so one sprite disables mipmaps for thousands of
 * otherwise valid high-resolution sprites. Modern mip generation and the stitcher's aligned
 * padding both support odd sizes; cap by actual sprite size instead of divisibility.
 */
@Mixin(SpriteLoader.class)
public abstract class SpriteLoaderRelaxedMipMixin {
	@Shadow @Final private Identifier id;

	@Redirect(method = "stitch", at = @At(value = "INVOKE", target =
			"Ljava/lang/Integer;lowestOneBit(I)I"), require = 0)
	private int moneyakshaders$allowOddSizedSpriteMips(int size) {
		MoneyakShadersConfig cfg = MoneyakShadersConfig.get();
		boolean managedAtlas = SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE.equals(this.id) && cfg.terrainMipmaps
				|| SpriteAtlasTexture.ITEMS_ATLAS_TEXTURE.equals(this.id) && cfg.entityTextureDistanceLod;
		return managedAtlas ? Integer.highestOneBit(Math.max(1, size)) : Integer.lowestOneBit(size);
	}

	@WrapOperation(method = "stitch", at = @At(value = "NEW", target =
			"(IIII)Lnet/minecraft/client/texture/TextureStitcher;"), require = 1)
	private TextureStitcher<SpriteContents> moneyakshaders$avoidItemAtlasAnisotropicPadding(
			int maxWidth, int maxHeight, int mipLevel, int anisotropy,
			Operation<TextureStitcher<SpriteContents>> original) {
		// The sampler for custom item displays is deliberately non-anisotropic. Keeping vanilla's
		// anisotropic stitch padding here can turn an 8K RP atlas into a 32K atlas for no visual gain.
		return original.call(maxWidth, maxHeight, mipLevel,
				SpriteAtlasTexture.ITEMS_ATLAS_TEXTURE.equals(this.id) ? 0 : anisotropy);
	}
}
