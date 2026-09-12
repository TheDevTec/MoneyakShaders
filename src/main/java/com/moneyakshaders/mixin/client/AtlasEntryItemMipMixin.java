package com.moneyakshaders.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.AtlasManager;
import net.minecraft.client.texture.SpriteAtlasTexture;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import com.moneyakshaders.MoneyakShadersConfig;

/**
 * Vanilla deliberately builds the item atlas without mipmaps. That is cheap for 16 px items, but
 * very expensive for resource packs whose custom item-display models sample an 8K atlas in the
 * distance. Build the same mip chain as the block atlas; the sampler selects level zero nearby and
 * progressively smaller levels only when screen-space derivatives require them.
 */
@Mixin(targets = "net.minecraft.client.texture.AtlasManager$Entry")
public abstract class AtlasEntryItemMipMixin {
	@Shadow @Final private AtlasManager.Metadata metadata;

	@ModifyArg(method = "load", at = @At(value = "INVOKE", target =
			"Lnet/minecraft/client/texture/SpriteLoader;load(Lnet/minecraft/resource/ResourceManager;Lnet/minecraft/util/Identifier;ILjava/util/concurrent/Executor;Ljava/util/Set;)Ljava/util/concurrent/CompletableFuture;"), index = 2, require = 0)
	private int moneyakshaders$itemAtlasMipLevels(int vanillaLevel) {
		if (!MoneyakShadersConfig.get().entityTextureDistanceLod
				|| !SpriteAtlasTexture.ITEMS_ATLAS_TEXTURE.equals(this.metadata.textureId())) {
			return vanillaLevel;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		return client == null ? vanillaLevel
				: Math.max(0, Math.min(1, client.options.getMipmapLevels().getValue()));
	}
}
