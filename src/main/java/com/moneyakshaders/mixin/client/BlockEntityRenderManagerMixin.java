package com.moneyakshaders.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.moneyakshaders.MoneyakShadersConfig;
import com.moneyakshaders.client.BlockEntityCuller;
import com.moneyakshaders.client.DynamicLightSources;
import com.moneyakshaders.client.EntityRenderTint;
import com.moneyakshaders.render.BakedBlockEntities;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.render.block.entity.BlockEntityRenderManager;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.state.BlockEntityRenderState;
import net.minecraft.client.render.command.ModelCommandRenderer;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Culls block entities before their render state is even extracted.
 * WorldRenderer null-checks the result of getRenderState (verified in
 * 1.21.11 bytecode), so returning null cleanly skips both extraction and
 * rendering for that block entity this frame.
 *
 * <p>Renderers that draw outside their bounding box (beacon beams, end
 * gateways) are never culled - their visuals are visible from far away
 * even when the block itself is hidden.
 */
@Mixin(BlockEntityRenderManager.class)
public abstract class BlockEntityRenderManagerMixin {
	@Shadow
	public abstract <E extends BlockEntity, S extends BlockEntityRenderState> BlockEntityRenderer<E, S> get(E blockEntity);

	@Shadow
	public abstract <E extends BlockEntity, S extends BlockEntityRenderState> BlockEntityRenderer<E, S> getByRenderState(S renderState);

	/** Carries coloured placed/dynamic light through every normal block-entity/RP command path. */
	@WrapMethod(method = "render")
	private void moneyakshaders$blockEntityTintScope(BlockEntityRenderState state, MatrixStack matrices,
			OrderedRenderCommandQueue queue, CameraRenderState cameraState, Operation<Void> original) {
		BlockPos pos = state.pos;
		EntityRenderTint.beginBlockEntityAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
		try {
			original.call(state, matrices, queue, cameraState);
		} finally {
			EntityRenderTint.end();
		}
	}

	/**
	 * Static block-entity bake (opt-in). On the first draw of a bakeable block entity, capture its
	 * tessellated geometry into {@link BakedBlockEntities} (vanilla still draws it this frame); on every
	 * later frame, replay the captured geometry straight into the queue and cancel the vanilla renderer,
	 * skipping the per-frame model pose + tessellation. See {@link BakedBlockEntities} for what's baked.
	 */
	@Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0)
	private void moneyakshaders$bakeBlockEntity(BlockEntityRenderState renderState, MatrixStack matrices,
			OrderedRenderCommandQueue queue, CameraRenderState cameraRenderState, CallbackInfo ci) {
		if (!MoneyakShadersConfig.get().blockEntityBake || !BakedBlockEntities.eligible(renderState.type)) {
			return;
		}
		if (BakedBlockEntities.replayIfBaked(renderState, matrices, queue)) {
			ci.cancel();
		} else {
			// First sighting: capture once (vanilla draw this frame proceeds — no blink).
			BakedBlockEntities.capture(this.getByRenderState(renderState), renderState, matrices, cameraRenderState);
		}
	}

	@Inject(method = "getRenderState", at = @At("HEAD"), cancellable = true, require = 0)
	private void moneyakshaders$cullBlockEntity(BlockEntity blockEntity, float tickProgress,
			ModelCommandRenderer.CrumblingOverlayCommand crumblingOverlay,
			CallbackInfoReturnable<BlockEntityRenderState> cir) {
		BlockEntityRenderer<BlockEntity, ?> renderer = this.get(blockEntity);
		if (renderer == null || renderer.rendersOutsideBoundingBox()) {
			return;
		}
		if (!BlockEntityCuller.shouldRender(blockEntity)) {
			cir.setReturnValue(null);
		}
	}

	/**
	 * Dynamic lighting for block entities (signs, banners, chests, …): lift the render state's
	 * lightmap by nearby dynamic sources, same as {@code EntityRendererLightCacheMixin} does for
	 * entities — so a placed/held/dropped torch actually lights a sign next to it. Cheap no-op when
	 * the feature is off or no source reaches the block entity.
	 */
	@Inject(method = "getRenderState", at = @At("RETURN"), require = 0)
	private void moneyakshaders$boostBlockEntityLight(BlockEntity blockEntity, float tickProgress,
			ModelCommandRenderer.CrumblingOverlayCommand crumblingOverlay,
			CallbackInfoReturnable<BlockEntityRenderState> cir) {
		BlockEntityRenderState state = cir.getReturnValue();
		if (state == null) {
			return;
		}
		BlockPos p = state.pos;
		state.lightmapCoordinates = DynamicLightSources.boost(
				p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, state.lightmapCoordinates);
	}
}
