package com.moneyakshaders.mixin.client;

import net.minecraft.client.model.Model;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.block.entity.SkullBlockEntityRenderer;
import net.minecraft.client.render.block.entity.state.SkullBlockEntityRenderState;
import net.minecraft.client.render.command.ModelCommandRenderer;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.moneyakshaders.render.ExperimentalSectionRender;

/** Adds the RGB point-light channel which vanilla's scalar skull lightmap cannot carry. */
@Mixin(SkullBlockEntityRenderer.class)
public abstract class SkullBlockEntityTintMixin {
	@Unique
	private static final ThreadLocal<Integer> moneyakshaders$lightTint = ThreadLocal.withInitial(() -> -1);

	@Inject(method = "render", at = @At("HEAD"))
	private void moneyakshaders$captureLightTint(SkullBlockEntityRenderState state, MatrixStack matrices,
			OrderedRenderCommandQueue queue, CameraRenderState cameraState, CallbackInfo ci) {
		moneyakshaders$lightTint.set(ExperimentalSectionRender.blockEntityLightTint(state.pos));
	}

	@Inject(method = "render", at = @At("RETURN"))
	private void moneyakshaders$clearLightTint(SkullBlockEntityRenderState state, MatrixStack matrices,
			OrderedRenderCommandQueue queue, CameraRenderState cameraState, CallbackInfo ci) {
		moneyakshaders$lightTint.remove();
	}

	@Redirect(method = "render(Lnet/minecraft/util/math/Direction;FFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;ILnet/minecraft/client/render/block/entity/SkullBlockEntityModel;Lnet/minecraft/client/render/RenderLayer;ILnet/minecraft/client/render/command/ModelCommandRenderer$CrumblingOverlayCommand;)V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/RenderLayer;IIILnet/minecraft/client/render/command/ModelCommandRenderer$CrumblingOverlayCommand;)V"))
	private static <S> void moneyakshaders$tintSkullModel(OrderedRenderCommandQueue queue,
			Model<? super S> model, S state, MatrixStack matrices, RenderLayer renderLayer,
			int light, int overlay, int outlineColor,
			ModelCommandRenderer.CrumblingOverlayCommand crumblingOverlay) {
		queue.submitModel(model, state, matrices, renderLayer, light, overlay,
				moneyakshaders$lightTint.get(), null, outlineColor, crumblingOverlay);
	}
}
