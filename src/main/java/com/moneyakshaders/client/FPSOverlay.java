package com.moneyakshaders.client;

import com.moneyakshaders.MoneyakShadersConfig;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

/**
 * Minimal FPS overlay. Lightweight and toggleable.
 */
public final class FPSOverlay {
    private static volatile FPSOverlay instance;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private int frames = 0;
    private long lastMillis = System.currentTimeMillis();
    private int lastFps = 0;

    private FPSOverlay() {}

    /**
     * "FPS: N" plus, when the async light engine is on, its live activity so
     * you can see the light engine is event-driven: standing still in a static
     * scene (even a cave full of light blocks) settles to enq/s 0, upd/s 0.
     */
    private static String buildOverlayText(MoneyakShadersConfig cfg, int fps) {
        StringBuilder sb = new StringBuilder(80);
        sb.append("FPS: ").append(fps);
        if (cfg.asyncLightUpdates) {
            sb.append("  |  Light enq/s: ").append(ClientLightDispatcher.getEnqueuedPerSec())
              .append("  upd/s: ").append(ClientLightDispatcher.getUpdatesPerSec());
        }
        if (cfg.frameProfilerEnabled) {
            sb.append(String.format("  |  ms p50=%.1f p95=%.1f p99=%.1f max=%.1f",
                    FrameProfiler.p50Ms(), FrameProfiler.p95Ms(),
                    FrameProfiler.p99Ms(), FrameProfiler.maxMs()));
        }
        if (cfg.frameBudgetEnabled) {
			sb.append(String.format("  |  budget %d%% snap×%d=%.1f/%.1f up=%.1f/%.1fms",
					FrameWorkBudget.currentScalePct(),
					FrameWorkBudget.snapshotScalePct(),
					FrameWorkBudget.consumedMs(FrameWorkBudget.BUCKET_SNAPSHOT),
					FrameWorkBudget.allowanceMs(FrameWorkBudget.BUCKET_SNAPSHOT),
					FrameWorkBudget.consumedMs(FrameWorkBudget.BUCKET_MESH_UPLOAD),
                    FrameWorkBudget.allowanceMs(FrameWorkBudget.BUCKET_MESH_UPLOAD)));
        }
        if (cfg.chunkLoadTimelineEnabled && ChunkLoadTimeline.lastSampleCount() > 0) {
			sb.append(String.format("  |  chunk p95/p99 snap=%.0f/%.0f mesh=%.0f/%.0f up=%.0f/%.0f draw=%.0f/%.0fms",
					ChunkLoadTimeline.p95Ms(ChunkLoadTimeline.PHASE_SNAPSHOT_DONE),
					ChunkLoadTimeline.p99Ms(ChunkLoadTimeline.PHASE_SNAPSHOT_DONE),
					ChunkLoadTimeline.p95Ms(ChunkLoadTimeline.PHASE_MESH_DONE),
					ChunkLoadTimeline.p99Ms(ChunkLoadTimeline.PHASE_MESH_DONE),
					ChunkLoadTimeline.p95Ms(ChunkLoadTimeline.PHASE_UPLOAD_DONE),
					ChunkLoadTimeline.p99Ms(ChunkLoadTimeline.PHASE_UPLOAD_DONE),
					ChunkLoadTimeline.p95Ms(ChunkLoadTimeline.PHASE_FIRST_DRAWN),
					ChunkLoadTimeline.p99Ms(ChunkLoadTimeline.PHASE_FIRST_DRAWN)));
        }
        if (cfg.chunkLoadTimelineEnabled) {
            sb.append("  |  pipe s=")
              .append(com.moneyakshaders.render.ExperimentalSectionRender.pendingSnapshotCount())
              .append(" m=").append(com.moneyakshaders.render.ExperimentalSectionRender.meshInFlightCount())
              .append(" u=").append(com.moneyakshaders.render.ExperimentalSectionRender.uploadBacklogCount())
              .append(" disk=").append(com.moneyakshaders.render.ExperimentalSectionRender.pendingDiskCacheInstallCount());
			if (com.moneyakshaders.render.ExperimentalSectionRender.diskCacheLoadInFlight()) {
				sb.append("*");
			}
			sb.append(" pool=").append(com.moneyakshaders.render.ExperimentalSectionRender.pooledSnapshotCount());
			sb.append("  |  underground active/sleep/evict=")
					.append(Math.max(0, com.moneyakshaders.render.ExperimentalSectionRender.sectionCount()
							- com.moneyakshaders.render.ExperimentalSectionRender.sleepingSectionCount()))
					.append('/').append(com.moneyakshaders.render.ExperimentalSectionRender.sleepingSectionCount())
					.append('/').append(com.moneyakshaders.render.ExperimentalSectionRender.evictedUndergroundCount())
					.append(" gpu=").append(com.moneyakshaders.render.ExperimentalSectionRender.sleepingGpuBytes() >> 20).append("MB");
        }
        return sb.toString();
    }

    private long lastSnapshotMs = 0L;

    public static FPSOverlay get() {
        if (instance == null) {
            synchronized (FPSOverlay.class) {
                if (instance == null) instance = new FPSOverlay();
            }
        }
        return instance;
    }

    /**
     * Call from new-style HUD render hook (DrawContext, RenderTickCounter).
     */
    public void onRenderDrawContext(DrawContext drawContext) {
        MoneyakShadersConfig cfg = MoneyakShadersConfig.get();

        // Frame-time histogram + adaptive budget reset — spec §22 fáze 1. Runs even if the HUD is off
        // so the budget still governs upload throttling.
        if (cfg.frameProfilerEnabled || cfg.frameBudgetEnabled) {
            FrameProfiler.tickFrameEnd();
        }
		if (cfg.frameBudgetEnabled) {
			FrameWorkBudget.beginFrame();
		}
		com.moneyakshaders.render.StaticDynamicCasters.beginFrame();
		// Percentiles govern the frame budget even when the user intentionally hides the HUD.
		// Keeping this above the early return makes p95/p99 telemetry a runtime mechanism rather
		// than a debug-overlay side effect.
		long now = System.currentTimeMillis();
		if ((cfg.frameProfilerEnabled || cfg.frameBudgetEnabled) && now - lastSnapshotMs >= 1000L) {
			FrameProfiler.snapshot();
			if (cfg.chunkLoadTimelineEnabled) ChunkLoadTimeline.snapshot();
			lastSnapshotMs = now;
		}

		if (!cfg.fpsHudEnabled) return;

		frames++;
		if (now - lastMillis >= 1000L) {
            lastFps = frames;
            frames = 0;
            lastMillis = now;
        }
		DebugStats.maybeDump();

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.textRenderer == null) return;

        String text = buildOverlayText(cfg, lastFps);

        try {
            drawContext.drawText(client.textRenderer, text, 2, 2, TEXT_COLOR, true);
            drawContext.drawDeferredElements();
        } catch (Throwable t) {
            // Fallback: set window title with FPS
            try {
                client.getWindow().setTitle("Optimized Loading - " + text);
            } catch (Throwable ignored) {
                // Silent fail
            }
        }
    }

    /**
     * Legacy method for older MatrixStack-based rendering (fallback).
     */
    public void onRender(MatrixStack matrices) {
        MoneyakShadersConfig cfg = MoneyakShadersConfig.get();
        if (!cfg.fpsHudEnabled) return;

        frames++;
        long now = System.currentTimeMillis();
        if (now - lastMillis >= 1000L) {
            lastFps = frames;
            frames = 0;
            lastMillis = now;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.textRenderer == null) return;

        String text = buildOverlayText(cfg, lastFps);

        try {
            matrices.push();
            matrices.translate(2.0f, 2.0f, 0.0f);
            
            var matrix = matrices.peek().getPositionMatrix();
            VertexConsumerProvider vertexConsumers = client.getBufferBuilders().getEntityVertexConsumers();
            client.textRenderer.draw(text, 0f, 0f, TEXT_COLOR, false, matrix, vertexConsumers, TextRenderer.TextLayerType.NORMAL, 0, 15728880);
            
            matrices.pop();
        } catch (Throwable t) {
            try {
                client.getWindow().setTitle("Optimized Loading - " + text);
            } catch (Throwable ignored) {
                // Silent fail
            }
        }
    }
}


