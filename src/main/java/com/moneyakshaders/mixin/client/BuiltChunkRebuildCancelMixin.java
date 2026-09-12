package com.moneyakshaders.mixin.client;

import net.minecraft.client.render.chunk.ChunkBuilder;
import net.minecraft.client.render.chunk.ChunkRendererRegionBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.moneyakshaders.MoneyakShadersConfig;

/**
 * The experimental renderer owns terrain meshes and its block-entity feed reads chunk maps directly.
 * Cancelling only WorldRenderer's public scheduling methods missed rebuilds issued directly on a
 * BuiltChunk by updateChunks, so vanilla still compiled every section that our renderer compiled.
 */
@Mixin(ChunkBuilder.BuiltChunk.class)
public abstract class BuiltChunkRebuildCancelMixin {
	@Shadow
	public abstract void cancelRebuild();

	@Inject(
			method = {
					"scheduleRebuild(Lnet/minecraft/client/render/chunk/ChunkRendererRegionBuilder;)V",
					"rebuild(Lnet/minecraft/client/render/chunk/ChunkRendererRegionBuilder;)V"
			},
			at = @At("HEAD"), cancellable = true, require = 0)
	private void moneyakshaders$skipReplacedTerrainBuild(ChunkRendererRegionBuilder regionBuilder, CallbackInfo ci) {
		MoneyakShadersConfig cfg = MoneyakShadersConfig.get();
		if (cfg.experimentalRenderer && cfg.skipVanillaChunkBuilds) {
			cancelRebuild();
			ci.cancel();
		}
	}
}
