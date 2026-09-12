

package com.moneyakshaders.mixin.client;

import java.util.List;
import java.util.Optional;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.client.resource.metadata.AnimationResourceMetadata;
import net.minecraft.client.resource.metadata.TextureResourceMetadata;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.SpriteContents;
import net.minecraft.client.texture.SpriteDimensions;
import net.minecraft.client.texture.SpriteOpener;
import net.minecraft.resource.metadata.ResourceMetadataSerializer;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Keeps tiny static resource-pack sprites mip-safe. A 1x1 sprite otherwise disables mipmaps for
 * an entire 8K atlas; uniformly nearest-scaling both its pixels and logical dimensions preserves
 * its appearance and aspect ratio while allowing the common four-level chain.
 */
@Mixin(SpriteOpener.class)
public interface SpriteOpenerMipSafetyMixin {
	@WrapOperation(method = "method_52851", at = @At(value = "NEW", target =
			"(Lnet/minecraft/util/Identifier;Lnet/minecraft/client/texture/SpriteDimensions;Lnet/minecraft/client/texture/NativeImage;Ljava/util/Optional;Ljava/util/List;Ljava/util/Optional;)Lnet/minecraft/client/texture/SpriteContents;"), require = 1)
	private static SpriteContents moneyakshaders$upscaleTinyStaticSprite(Identifier id,
			SpriteDimensions dimensions, NativeImage image,
			Optional<AnimationResourceMetadata> animation,
			List<ResourceMetadataSerializer.Value<?>> additionalMetadata,
			Optional<TextureResourceMetadata> textureMetadata,
			Operation<SpriteContents> original) {
		int shortest = Math.min(dimensions.width(), dimensions.height());
		if (animation.isPresent() || shortest >= 2 || shortest <= 0) {
			return original.call(id, dimensions, image, animation, additionalMetadata, textureMetadata);
		}

		int scale = (2 + shortest - 1) / shortest;
		int width = Math.multiplyExact(image.getWidth(), scale);
		int height = Math.multiplyExact(image.getHeight(), scale);
		NativeImage enlarged = new NativeImage(width, height, false);
		try {
			image.resizeSubRectTo(0, 0, image.getWidth(), image.getHeight(), enlarged);
			image.close();
			SpriteDimensions enlargedDimensions = new SpriteDimensions(
					Math.multiplyExact(dimensions.width(), scale),
					Math.multiplyExact(dimensions.height(), scale));
			return original.call(id, enlargedDimensions, enlarged, animation, additionalMetadata, textureMetadata);
		} catch (Throwable error) {
			enlarged.close();
			throw error;
		}
	}
}
