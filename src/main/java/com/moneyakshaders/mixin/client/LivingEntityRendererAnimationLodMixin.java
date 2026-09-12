package com.moneyakshaders.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.moneyakshaders.client.EntityLODOptimizer;

import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.model.EntityModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Reuses the context-model pose before feature renderers copy it into armor/equipment models. */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererAnimationLodMixin {
	@WrapOperation(
			method = "render(Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/render/state/CameraRenderState;)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/entity/model/EntityModel;setAngles(Ljava/lang/Object;)V"),
			require = 0)
	private void moneyakshaders$animationLodForFeatures(EntityModel<?> model, Object state,
			Operation<Void> original) {
		if (!EntityLODOptimizer.applyPose(model, state, () -> original.call(model, state))) {
			original.call(model, state);
		}
	}
}
