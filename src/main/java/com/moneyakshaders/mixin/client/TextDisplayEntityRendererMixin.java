package com.moneyakshaders.mixin.client;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.moneyakshaders.MoneyakShadersConfig;
import com.moneyakshaders.client.DepthSafeTextDisplayLayer;

/** Replaces only text_display's background with the isolated no-depth-write frosted layer. */
@Mixin(targets = "net.minecraft.client.render.entity.DisplayEntityRenderer$TextDisplayEntityRenderer")
public abstract class TextDisplayEntityRendererMixin {
	@Redirect(
			method = "render(Lnet/minecraft/client/render/entity/state/TextDisplayEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;IF)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/RenderLayers;textBackground()Lnet/minecraft/client/render/RenderLayer;"),
			require = 1)
	private RenderLayer moneyakshaders$depthSafeTextBackground() {
		return MoneyakShadersConfig.get().depthSafeTextDisplays
				? DepthSafeTextDisplayLayer.get()
				: RenderLayers.textBackground();
	}
}
