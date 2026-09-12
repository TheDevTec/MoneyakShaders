package com.moneyakshaders.mixin.client;

import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.PaintingEntityRenderer;
import net.minecraft.client.render.entity.state.PaintingEntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.moneyakshaders.client.DynamicLightSources;

/** Paintings carry one packed light value per tile; lift all of them for nearby dynamic sources. */
@Mixin(PaintingEntityRenderer.class)
public abstract class PaintingEntityTintMixin {
	@Inject(method = "render", at = @At("HEAD"))
	private void moneyakshaders$captureLightTint(PaintingEntityRenderState state, MatrixStack matrices,
			OrderedRenderCommandQueue queue, CameraRenderState cameraState, CallbackInfo ci) {
		// PaintingEntityRenderer bypasses EntityRenderState.light and supplies one vanilla-only packed
		// light value per painting tile. Lift those values too; otherwise RGB tint is multiplied by a
		// black lightmap and a painting beside a held light remains visibly dark.
		for (int i = 0; i < state.lightmapCoordinates.length; i++) {
			state.lightmapCoordinates[i] = DynamicLightSources.boost(
					state.x, state.y, state.z, state.lightmapCoordinates[i]);
		}
	}
}
