package com.moneyakshaders.mixin.client;

import net.minecraft.client.render.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.moneyakshaders.client.IrisShaderOptimizer;

/**
 * Hook Iris shader optimization into the world render cycle.
 * Applies batching and GPU monitoring during chunk rebuild scheduling.
 */
@Mixin(WorldRenderer.class)
public abstract class WorldRendererIrisOptimizationMixin {
	@Inject(method = "scheduleChunkRender(III)V", at = @At("TAIL"), require = 0)
	private void moneyakshaders$applyIrisOptimizations(int chunkX, int chunkY, int chunkZ, CallbackInfo ci) {
		// Optimize shader uniforms after scheduling chunks
		IrisShaderOptimizer.getInstance().optimizeFrame();
	}
}
