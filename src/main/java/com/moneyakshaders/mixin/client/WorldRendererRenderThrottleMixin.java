package com.moneyakshaders.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.chunk.ChunkBuilder;
import net.minecraft.client.render.chunk.NormalizedRelativePos;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.moneyakshaders.MoneyakShadersConfig;
import com.moneyakshaders.client.ChunkMeshExecutor;
import com.moneyakshaders.client.RenderTaskDeduplicator;
import com.moneyakshaders.client.UnusedSceneOptimizer;

/**
 * Reduce redundant render scheduling for the world renderer.
 * Same chunk/region re-schedules inside a short time window are coalesced,
 * and non-important section renders can be dropped when mesh backlog is high.
 */
@Mixin(WorldRenderer.class)
public abstract class WorldRendererRenderThrottleMixin {
	@Inject(method = "isTerrainRenderComplete", at = @At("HEAD"), cancellable = true, require = 0)
	private void moneyakshaders$customTerrainReady(CallbackInfoReturnable<Boolean> cir) {
		MoneyakShadersConfig config = MoneyakShadersConfig.get();
		if (!config.experimentalRenderer || !config.skipVanillaChunkBuilds) return;
		MinecraftClient client = MinecraftClient.getInstance();
		if (client != null && client.player != null
				&& com.moneyakshaders.render.ExperimentalSectionRender.isEntryRenderingReady(client.player.getBlockPos())) {
			cir.setReturnValue(true);
		}
	}

	/**
	 * 1.21.11's multiplayer entry gate does not call {@code isTerrainRenderComplete}; it asks whether
	 * the vanilla BuiltChunk at the player's exact position has left HIDDEN state. With vanilla chunk
	 * builds intentionally cancelled that can never happen, even while thousands of custom sections
	 * are already on screen, so the gate always waited for its hard timeout. Use the custom resident
	 * mesh at/next to the supplied player position as the equivalent readiness condition.
	 */
	@Inject(method = "isRenderingReady(Lnet/minecraft/util/math/BlockPos;)Z", at = @At("HEAD"), cancellable = true, require = 0)
	private void moneyakshaders$customRenderingReady(BlockPos playerPos, CallbackInfoReturnable<Boolean> cir) {
		MoneyakShadersConfig config = MoneyakShadersConfig.get();
		if (config.experimentalRenderer && config.skipVanillaChunkBuilds
				&& com.moneyakshaders.render.ExperimentalSectionRender.isEntryRenderingReady(playerPos)) {
			cir.setReturnValue(true);
		}
	}

	// Under the experimental renderer vanilla terrain is never DRAWN (we draw our own). Vanilla
	// rebuilds used to be kept alive anyway because they COLLECTED block entities — but with
	// skipVanillaChunkBuilds the WorldRendererBlockEntityFeedMixin feeds BEs straight from the chunk
	// maps, so the whole vanilla mesh pipeline is cancelled here (big CPU cut during chunk loading).
	// With the flag off, rebuilds still pass through untouched (BE collection via built sections).
	@Inject(method = "scheduleChunkRender(IIIZ)V", at = @At("HEAD"), cancellable = true, require = 0)
	private void moneyakshaders$throttleChunkRender(int chunkX, int chunkY, int chunkZ, boolean important, CallbackInfo ci) {
		MoneyakShadersConfig config = MoneyakShadersConfig.get();
		if (config.experimentalRenderer) {
			if (config.skipVanillaChunkBuilds) {
				com.moneyakshaders.render.ExperimentalSectionRender.markScheduledDirty(chunkX, chunkY, chunkZ);
				ci.cancel();
			}
			return;
		}
		if (RenderTaskDeduplicator.shouldSkipDuplicate(chunkX, chunkY, chunkZ, config.renderDuplicateSkipWindowMs)
				|| UnusedSceneOptimizer.getInstance().shouldSkipChunkRender(chunkX, chunkY, chunkZ, getCameraPosition(), important)) {
			ci.cancel();
		}
	}

	@Inject(method = "scheduleChunkRender(III)V", at = @At("HEAD"), cancellable = true, require = 0)
	private void moneyakshaders$throttleChunkRender(int chunkX, int chunkY, int chunkZ, CallbackInfo ci) {
		MoneyakShadersConfig config = MoneyakShadersConfig.get();
		// Vanilla calls this up to ~11k times a second while a world streams in, and with the
		// experimental renderer on every one of them is cancelled two lines below. An AtomicLong CAS
		// per call is real bus traffic for a number nobody reads unless the debug overlay is up.
		if (config.debugStats) {
			com.moneyakshaders.client.DebugStats.scheduleRenderCalls.incrementAndGet();
		}
		if (config.experimentalRenderer) {
			if (config.skipVanillaChunkBuilds) {
				com.moneyakshaders.render.ExperimentalSectionRender.markScheduledDirty(chunkX, chunkY, chunkZ);
				ci.cancel();
			}
			return;
		}
		if (RenderTaskDeduplicator.shouldSkipDuplicate(chunkX, chunkY, chunkZ, config.renderDuplicateSkipWindowMs)
				|| UnusedSceneOptimizer.getInstance().shouldSkipChunkRender(chunkX, chunkY, chunkZ, getCameraPosition(), false)) {
			if (config.debugStats) {
				com.moneyakshaders.client.DebugStats.scheduleRenderSkipped.incrementAndGet();
			}
			ci.cancel();
		}
	}

	@Inject(method = "scheduleChunkRenders(IIIIII)V", at = @At("HEAD"), cancellable = true, require = 0)
	private void moneyakshaders$throttleChunkRenders(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, CallbackInfo ci) {
		MoneyakShadersConfig config = MoneyakShadersConfig.get();
		if (config.experimentalRenderer) {
			if (config.skipVanillaChunkBuilds) {
				for (int sx = minX >> 4; sx <= maxX >> 4; sx++) {
					for (int sy = minY >> 4; sy <= maxY >> 4; sy++) {
						for (int sz = minZ >> 4; sz <= maxZ >> 4; sz++) {
							com.moneyakshaders.render.ExperimentalSectionRender.markScheduledDirty(sx, sy, sz);
						}
					}
				}
				ci.cancel();
			}
			return;
		}
		if (RenderTaskDeduplicator.shouldSkipDuplicateRegion(minX, minY, minZ, maxX, maxY, maxZ, config.renderDuplicateSkipWindowMs)) {
			ci.cancel();
		}
	}

	@Inject(method = "scheduleChunkRenders3x3x3(III)V", at = @At("HEAD"), cancellable = true, require = 0)
	private void moneyakshaders$throttleChunkRenders3x3x3(int centerX, int centerY, int centerZ, CallbackInfo ci) {
		MoneyakShadersConfig config = MoneyakShadersConfig.get();
		if (config.experimentalRenderer) {
			if (config.skipVanillaChunkBuilds) { ci.cancel(); } // light dispatcher owns this 3x3x3 signal
			return;
		}
		if (RenderTaskDeduplicator.shouldSkipDuplicateRegion(centerX - 1, centerY - 1, centerZ - 1, centerX + 1, centerY + 1, centerZ + 1, config.renderDuplicateSkipWindowMs)) {
			ci.cancel();
		}
	}

	private Vec3d getCameraPosition() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.player == null) {
			return null;
		}
		return new Vec3d(client.player.getX(), client.player.getY(), client.player.getZ());
	}

	@Inject(method = "scheduleChunkTranslucencySort(Lnet/minecraft/client/render/chunk/ChunkBuilder$BuiltChunk;Lnet/minecraft/client/render/chunk/NormalizedRelativePos;Lnet/minecraft/util/math/Vec3d;ZZ)V", at = @At("HEAD"), cancellable = true, require = 0)
	private void moneyakshaders$throttleChunkTranslucencySort(ChunkBuilder.BuiltChunk chunk, NormalizedRelativePos relativePos, Vec3d cameraPos, boolean needsUpdate, boolean ignoreCameraAlignment, CallbackInfo ci) {
		MoneyakShadersConfig config = MoneyakShadersConfig.get();
		if (!chunk.hasTranslucentLayer() || RenderTaskDeduplicator.shouldSkipDuplicateObject(chunk, config.renderDuplicateSkipWindowMs)) {
			ci.cancel();
		}
	}

	@Inject(method = "scheduleSectionRender(Lnet/minecraft/util/math/BlockPos;Z)V", at = @At("HEAD"), cancellable = true, require = 0)
	private void moneyakshaders$throttleSectionRender(BlockPos pos, boolean important, CallbackInfo ci) {
		MoneyakShadersConfig config = MoneyakShadersConfig.get();
		if (config.experimentalRenderer) {
			if (config.skipVanillaChunkBuilds) {
				com.moneyakshaders.render.ExperimentalSectionRender.markScheduledDirty(
						pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
				ci.cancel();
			}
			return;
		}
		if (!important && config.sectionRenderDropLowPriorityWhenQueueHigh
				&& ChunkMeshExecutor.isQueueAboveThreshold(config.sectionRenderBackpressureThreshold)) {
			ci.cancel();
		}
	}
}
