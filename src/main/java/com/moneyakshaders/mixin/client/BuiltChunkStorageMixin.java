package com.moneyakshaders.mixin.client;

import net.minecraft.client.render.BuiltChunkStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.moneyakshaders.MoneyakShadersConfig;
import com.moneyakshaders.client.ChunkMeshExecutor;

/**
 * Apply soft backpressure to client chunk rebuild requests when the dedicated
 * mesh queue is already large. Low-priority rebuilds can be skipped instead of
 * adding more work that will only be executed later.
 */
@Mixin(BuiltChunkStorage.class)
public abstract class BuiltChunkStorageMixin {
	@Inject(method = "scheduleRebuild(IIIZ)V", at = @At("HEAD"), cancellable = true, require = 0)
	private void moneyakshaders$maybeDelayRebuild(int x, int y, int z, boolean important, CallbackInfo ci) {
		MoneyakShadersConfig config = MoneyakShadersConfig.get();
		if (!important && config.chunkRebuildDropLowPriorityWhenQueueHigh) {
			int threshold = config.chunkRebuildBackpressureThreshold;
			if (threshold > 0 && ChunkMeshExecutor.isQueueAboveThreshold(threshold)) {
				ci.cancel();
			}
		}
	}
}
