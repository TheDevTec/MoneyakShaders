package com.moneyakshaders.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.moneyakshaders.MoneyakShadersConfig;
import com.moneyakshaders.client.AnimatedTextureScheduler;

import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.texture.SpriteLoader;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Enables derivative-selected mip sampling for the generated high-resolution item-atlas chain. */
@Mixin(SpriteAtlasTexture.class)
public abstract class SpriteAtlasTextureLodMixin extends AbstractTexture {
	@Shadow @Final private Identifier id;

	@Inject(method = "create", at = @At("TAIL"), require = 0)
	private void moneyakshaders$enableItemAtlasLod(SpriteLoader.StitchResult result, CallbackInfo ci) {
		if (result.mipLevel() > 0
				&& MoneyakShadersConfig.get().entityTextureDistanceLod
				&& SpriteAtlasTexture.ITEMS_ATLAS_TEXTURE.equals(this.id)) {
			// NEAREST preserves pixel art at each level; the unbounded LOD range lets the GPU choose a
			// smaller level at distance instead of repeatedly fetching mip zero from the 8K atlas.
			this.sampler = RenderSystem.getSamplerCache().get(FilterMode.NEAREST, true);
		}
	}

	@Inject(method = "tickAnimatedSprites", at = @At("HEAD"), require = 0)
	private void moneyakshaders$beginAnimationBudget(CallbackInfo ci) {
		AnimatedTextureScheduler.beginAtlas(this.id);
	}

	@Inject(method = "tickAnimatedSprites", at = @At("RETURN"), require = 0)
	private void moneyakshaders$endAnimationBudget(CallbackInfo ci) {
		AnimatedTextureScheduler.endAtlas();
	}
}
