package com.moneyakshaders.mixin.client;

import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.moneyakshaders.MoneyakShadersConfig;

/**
 * Skips vanilla blob-shadow computation entirely (config:
 * disableEntityShadows). updateShadow samples the blocks under every entity
 * every frame to build shadow pieces - with shader packs providing real
 * shadows this is wasted CPU. The shared render state is reused across
 * entities, so the piece list is cleared before cancelling to avoid leaking
 * the previous entity's shadow.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererShadowMixin {
	@Inject(
			method = "updateShadow(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/render/entity/state/EntityRenderState;)V",
			at = @At("HEAD"),
			cancellable = true,
			require = 0
	)
	private void moneyakshaders$skipShadow(Entity entity, EntityRenderState state, CallbackInfo ci) {
		if (MoneyakShadersConfig.get().disableEntityShadows) {
			// The state object is reused across entities - wipe leftovers.
			state.shadowRadius = 0.0F;
			state.shadowPieces.clear();
			ci.cancel();
		}
	}
}
