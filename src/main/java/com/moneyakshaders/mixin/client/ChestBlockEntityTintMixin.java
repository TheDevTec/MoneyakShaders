package com.moneyakshaders.mixin.client;

import net.minecraft.client.render.block.entity.ChestBlockEntityRenderer;
import net.minecraft.client.render.block.entity.state.ChestBlockEntityRenderState;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.moneyakshaders.render.ExperimentalSectionRender;

/** Applies the coloured-light channel which vanilla's scalar block-entity lightmap cannot carry. */
@Mixin(ChestBlockEntityRenderer.class)
public abstract class ChestBlockEntityTintMixin {
	@Unique
	private int moneyakshaders$lightTint = -1;

	@Inject(method = "render", at = @At("HEAD"))
	private void moneyakshaders$captureLightTint(ChestBlockEntityRenderState state, MatrixStack matrices,
			OrderedRenderCommandQueue queue, CameraRenderState cameraState, CallbackInfo ci) {
		this.moneyakshaders$lightTint = ExperimentalSectionRender.blockEntityLightTint(state.pos);
	}

	@ModifyArg(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/RenderLayer;IIILnet/minecraft/client/texture/Sprite;ILnet/minecraft/client/render/command/ModelCommandRenderer$CrumblingOverlayCommand;)V"),
			index = 6)
	private int moneyakshaders$tintChestModel(int original) {
		return this.moneyakshaders$lightTint == -1 ? original : this.moneyakshaders$lightTint;
	}
}
