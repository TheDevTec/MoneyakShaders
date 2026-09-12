package com.moneyakshaders.mixin.client;

import java.util.Queue;

import net.minecraft.client.render.chunk.AbstractChunkRenderData;
import net.minecraft.client.render.chunk.ChunkBuilder;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.moneyakshaders.MoneyakShadersConfig;
import com.moneyakshaders.client.FrameWorkBudget;

/**
 * Caps how many chunk-section mesh uploads run per frame. Vanilla's
 * {@code ChunkBuilder.upload()} drains the ENTIRE upload queue every frame, so
 * a sudden burst of rebuilds (a piston array shoving dozens of blocks, a map
 * of new chunks appearing, an explosion) uploads them all in one frame =
 * a lag spike. Bounding the uploads per frame spreads the burst over the next
 * few frames; the leftover meshes upload next frame (upload() runs each
 * frame), so terrain just finishes a frame or two later instead of stalling.
 *
 * <p>This is the same technique Sodium uses. The stale-render-data queue is
 * still drained fully (freeing GPU buffers is cheap). config:
 * maxChunkUploadsPerFrame, 0 = vanilla (drain all).
 */
@Mixin(ChunkBuilder.class)
public abstract class ChunkBuilderUploadThrottleMixin {
	@Shadow
	@Final
	private Queue<Runnable> uploadQueue;

	@Shadow
	@Final
	Queue<AbstractChunkRenderData> renderQueue;

	@Inject(method = "upload", at = @At("HEAD"), cancellable = true, require = 0)
	private void moneyakshaders$throttleUploads(CallbackInfo ci) {
		MoneyakShadersConfig cfg = MoneyakShadersConfig.get();
		boolean budget = cfg.frameBudgetEnabled;
		int hardCap = cfg.maxChunkUploadsPerFrame;
		if (!budget && hardCap <= 0) {
			return; // vanilla behaviour: drain everything
		}
		int uploaded = 0;
		Runnable task;
		if (budget) FrameWorkBudget.startBucket(FrameWorkBudget.BUCKET_MESH_UPLOAD);
		try {
			while ((task = this.uploadQueue.poll()) != null) {
				task.run();
				uploaded++;
				// Time budget preferred; hardCap keeps a safety cap when budget is off or misconfigured.
				if (budget) {
					if (!FrameWorkBudget.hasBudget(FrameWorkBudget.BUCKET_MESH_UPLOAD)) break;
				} else if (uploaded >= hardCap) {
					break;
				}
			}
		} finally {
			if (budget) FrameWorkBudget.endBucket(FrameWorkBudget.BUCKET_MESH_UPLOAD);
		}
		// Freeing replaced render data is cheap - always drain it.
		AbstractChunkRenderData data;
		while ((data = this.renderQueue.poll()) != null) {
			data.close();
		}
		ci.cancel();
	}
}
