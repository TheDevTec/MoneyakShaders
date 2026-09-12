package com.moneyakshaders.mixin.client;

import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.chunk.ChunkBuilder;
import net.minecraft.client.render.chunk.ChunkRendererRegionBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.moneyakshaders.MoneyakShaders;
import com.moneyakshaders.MoneyakShadersConfig;

/**
 * Turns the synchronous chunk-mesh compile that vanilla runs on the render
 * thread into an async schedule on our dedicated mesh pool.
 *
 * <p>In {@code updateChunks}, vanilla compiles "important" nearby sections
 * inline ({@code ChunkBuilder.rebuild}, profiler key
 * "compileSectionSynchronously") whenever the Chunk Builder option is set to
 * "By Player" or "Nearby", or when a section flags an important rebuild. That
 * inline compile is the render-thread stall F3 reports as "rendering" while
 * chunks load or blocks change. Redirecting it to {@code scheduleRebuild}
 * (the exact call vanilla already uses for the async path right below) keeps
 * the render thread free; the following {@code cancelRebuild()} runs the same
 * as on the async branch.
 */
@Mixin(WorldRenderer.class)
public abstract class WorldRendererSyncRebuildMixin {
	@Unique
	private static boolean moneyakshaders$loggedAsyncRebuild;

	@Redirect(
			method = "updateChunks",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/render/chunk/ChunkBuilder;rebuild(Lnet/minecraft/client/render/chunk/ChunkBuilder$BuiltChunk;Lnet/minecraft/client/render/chunk/ChunkRendererRegionBuilder;)V"
			),
			require = 0
	)
	private void moneyakshaders$rebuildAsync(ChunkBuilder chunkBuilder, ChunkBuilder.BuiltChunk builtChunk, ChunkRendererRegionBuilder regionBuilder) {
		MoneyakShadersConfig config = MoneyakShadersConfig.get();
		if (config.forceAsyncChunkRebuilds) {
			if (config.debugStats) {
				com.moneyakshaders.client.DebugStats.syncRedirects.incrementAndGet();
			}
			if (!moneyakshaders$loggedAsyncRebuild) {
				moneyakshaders$loggedAsyncRebuild = true;
				MoneyakShaders.LOGGER.info("[Optimized Loading] Synchronous chunk rebuilds redirected to the async mesh pool (render thread will not stall on meshing)");
			}
			builtChunk.scheduleRebuild(regionBuilder);
		} else {
			chunkBuilder.rebuild(builtChunk, regionBuilder);
		}
	}
}
