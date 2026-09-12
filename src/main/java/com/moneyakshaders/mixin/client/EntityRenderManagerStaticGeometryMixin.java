package com.moneyakshaders.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.moneyakshaders.client.EntityLODOptimizer;
import com.moneyakshaders.client.EntityRenderTint;
import com.moneyakshaders.render.StaticEntityGeometryCache;

import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.EntityRenderManager;
import net.minecraft.client.render.entity.state.ArmorStandEntityRenderState;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.entity.state.DisplayEntityRenderState;
import net.minecraft.client.render.entity.state.ItemFrameEntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Captures and replays immutable decoration-entity geometry around the vanilla manager boundary. */
@Mixin(EntityRenderManager.class)
public abstract class EntityRenderManagerStaticGeometryMixin {
	@Inject(method = "getAndUpdateRenderState", at = @At("RETURN"))
	private <E extends Entity> void moneyakshaders$associateStaticEntity(E entity, float tickProgress,
			CallbackInfoReturnable<EntityRenderState> cir) {
		EntityLODOptimizer.associateState(cir.getReturnValue(), entity);
		StaticEntityGeometryCache.associate(cir.getReturnValue(), entity);
		com.moneyakshaders.client.EntityShadowCapture.associate(cir.getReturnValue(), entity);
		EntityRenderTint.associate(cir.getReturnValue(), entity);
	}

	@WrapMethod(method = "render")
	private <S extends EntityRenderState> void moneyakshaders$renderStaticGeometry(S state,
			CameraRenderState cameraState, double offsetX, double offsetY, double offsetZ,
			MatrixStack matrices, OrderedRenderCommandQueue queue, Operation<Void> original) {
		com.moneyakshaders.client.EntityShadowCapture.beginEntityScope(state, offsetX, offsetY, offsetZ);
		EntityRenderTint.begin(state, state.x, state.y + state.height * 0.5, state.z);
		try {
			if (state instanceof ArmorStandEntityRenderState armorStandState
					&& StaticEntityGeometryCache.renderArmorStand(
							armorStandState, offsetX, offsetY, offsetZ, matrices, queue,
							captureQueue -> original.call(state, cameraState, offsetX, offsetY, offsetZ, matrices, captureQueue))) {
				return;
			}
			if (state instanceof ItemFrameEntityRenderState itemFrameState
					&& StaticEntityGeometryCache.renderItemFrame(
							itemFrameState, offsetX, offsetY, offsetZ, matrices, queue,
							captureQueue -> original.call(state, cameraState, offsetX, offsetY, offsetZ, matrices, captureQueue))) {
				return;
			}
			if (state instanceof DisplayEntityRenderState displayState
					&& StaticEntityGeometryCache.renderDisplay(
							displayState, offsetX, offsetY, offsetZ, matrices, queue,
							captureQueue -> original.call(state, cameraState, offsetX, offsetY, offsetZ, matrices, captureQueue))) {
				return;
			}
			original.call(state, cameraState, offsetX, offsetY, offsetZ, matrices, queue);
		} finally {
			EntityRenderTint.end();
			com.moneyakshaders.client.EntityShadowCapture.endEntityScope();
		}
	}
}
