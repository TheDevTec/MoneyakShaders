package com.moneyakshaders.mixin.client;

import net.minecraft.client.gl.GpuSampler;
import net.minecraft.client.render.BlockRenderLayerGroup;
import net.minecraft.client.render.SectionRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.moneyakshaders.MoneyakShadersConfig;
import com.moneyakshaders.render.ExperimentalSectionRender;

/**
 * Plan C / Phase 3.6 — our pipeline REPLACES vanilla terrain, drawn in the real
 * world pass. {@code SectionRenderState.renderSection} is the (void) draw of the
 * chunk terrain layers. At the OPAQUE call we draw our solid terrain; at the
 * TRANSLUCENT call captures the matrices and schedules our water/glass pass. The
 * pass is composed at the world-render tail, after particles, so depth can place
 * particles behind translucent surfaces instead of always above them. Vanilla's
 * own draw is cancelled for every layer group.
 */
@Mixin(SectionRenderState.class)
public abstract class SectionRenderCancelMixin {
	@Inject(method = "renderSection", at = @At("HEAD"), cancellable = true, require = 0)
	private void moneyakshaders$suppressVanillaTerrain(BlockRenderLayerGroup group, GpuSampler sampler, CallbackInfo ci) {
		if (MoneyakShadersConfig.get().experimentalRenderer) {
			if (group == BlockRenderLayerGroup.OPAQUE) {
				ExperimentalSectionRender.renderOpaque();
			} else if (group == BlockRenderLayerGroup.TRANSLUCENT) {
				ExperimentalSectionRender.renderWater();
			}
			ci.cancel();
		}
	}
}
