package com.moneyakshaders.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.moneyakshaders.client.EntityLODOptimizer;

import net.minecraft.client.model.Model;
import net.minecraft.client.render.command.ModelCommandRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Applies the pose LOD at the deferred model-command execution point used by body and feature models. */
@Mixin(ModelCommandRenderer.class)
public abstract class ModelCommandRendererAnimationLodMixin {
	@WrapOperation(
			method = "render(Lnet/minecraft/client/render/command/OrderedRenderCommandQueueImpl$ModelCommand;Lnet/minecraft/client/render/RenderLayer;Lnet/minecraft/client/render/VertexConsumer;Lnet/minecraft/client/render/OutlineVertexConsumerProvider;Lnet/minecraft/client/render/VertexConsumerProvider$Immediate;)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/model/Model;setAngles(Ljava/lang/Object;)V"))
	private void moneyakshaders$animationLodForCommand(Model<?> model, Object state,
			Operation<Void> original) {
		if (!EntityLODOptimizer.applyPose(model, state, () -> original.call(model, state))) {
			original.call(model, state);
		}
	}
}
