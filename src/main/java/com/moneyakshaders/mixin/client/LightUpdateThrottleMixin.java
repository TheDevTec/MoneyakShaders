package com.moneyakshaders.mixin.client;

import net.minecraft.client.render.WorldRenderer;
import net.minecraft.world.chunk.light.LightingProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.moneyakshaders.MoneyakShadersConfig;

/**
 * Vanilla calls {@code LightingProvider.doLightUpdates()} every single frame
 * on the render thread ("runLightUpdates" in the profiler). Under heavy light
 * churn (redstone-clocked builds, animated maps, breaking light sources) this
 * is the single biggest light-engine FPS cost on the client.
 *
 * <p>The custom renderer must publish every completed vanilla light pass. Delaying these calls made
 * neighbouring chunk meshes sample different light generations and produced exact section borders.
 * Expensive work is culled by whole surface/underground domains instead of by time.
 */
@Mixin(WorldRenderer.class)
public abstract class LightUpdateThrottleMixin {
	@Redirect(
			method = "render",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/world/chunk/light/LightingProvider;doLightUpdates()I"
			),
			require = 0
	)
	private int moneyakshaders$throttleLightUpdates(LightingProvider provider) {
		MoneyakShadersConfig config = MoneyakShadersConfig.get();
		if (com.moneyakshaders.client.ClientLightDispatcher.asyncActive()) {
			// The async light engine owns the update cadence; this call gets
			// deflected to the light thread anyway.
			return provider.doLightUpdates();
		}
		return moneyakshaders$runAndPublishLightPass(provider, config);
	}

	private static int moneyakshaders$runAndPublishLightPass(LightingProvider provider,
			MoneyakShadersConfig config) {
		int updated = provider.doLightUpdates();
		if (config.experimentalRenderer) {
			com.moneyakshaders.render.ExperimentalSectionRender.onClientLightPassCompleted();
		}
		return updated;
	}
}
