package com.moneyakshaders.mixin.client;

import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.moneyakshaders.client.CreativeInventoryPrewarmer;
import com.moneyakshaders.client.DynamicLightSources;
import com.moneyakshaders.client.FontGlyphPrewarmer;
import com.moneyakshaders.client.ItemPrewarmer;
import com.moneyakshaders.client.PersistedChatHistory;
import com.moneyakshaders.client.WaterEffects;

/** Rebuild the dynamic-light source list + batched chat flush once per client tick (20 Hz). */
@Mixin(MinecraftClient.class)
public abstract class DynamicLightTickMixin {
	@org.spongepowered.asm.mixin.Unique
	private static int moneyakshaders$shotTicks;
	@org.spongepowered.asm.mixin.Unique
	private static long moneyakshaders$lifeTicks;

	@Inject(method = "tick", at = @At("TAIL"), require = 0)
	private void moneyakshaders$updateDynamicLights(CallbackInfo ci) {
		MinecraftClient mc = (MinecraftClient) (Object) this;
		DynamicLightSources.update(mc);
		WaterEffects.tick(mc);
		CreativeInventoryPrewarmer.tick(mc);
		ItemPrewarmer.tick(mc);
		FontGlyphPrewarmer.tick(mc);
		PersistedChatHistory.maybeFlush();
		// Section disk cache: must run even with a null world so a full disconnect (world → null) still
		// flushes the cache — the renderer's dimension-change flush point stops running once world is null.
		com.moneyakshaders.render.ExperimentalSectionRender.tickDiskCache(mc);
		// DEV harness: periodic screenshot so the (headless-driven) dev client's view can be inspected.
		if (com.moneyakshaders.MoneyakShadersConfig.get().debugAutoScreenshot && mc.world != null) {
			mc.options.pauseOnLostFocus = false; // headless-driven window is never focused
			// Never dismiss a player-owned screen here. In particular, closing the chat
			// every client tick makes the dev harness unusable for live visual feedback.
			if (++moneyakshaders$shotTicks >= 60) {
				moneyakshaders$shotTicks = 0;
				try {
					net.minecraft.client.util.ScreenshotRecorder.saveScreenshot(
							mc.runDirectory, "opl_debug.png", mc.getFramebuffer(), 1, t -> {});
				} catch (Throwable ignored) {
				}
			}
			// Auto-close: (a) when the driver drops a "_stopclient" file into the run dir, or (b) after a
			// 20-minute safety lifetime — so an orphaned headless dev client can never hang around.
			if (++moneyakshaders$lifeTicks % 100 == 0) {
				try {
					java.io.File rel = new java.io.File(mc.runDirectory, "_reload");
				if (rel.exists()) {
					rel.delete();
					com.moneyakshaders.MoneyakShaders.LOGGER.info("[Optimized Loading] dev harness: client resource reload");
					mc.reloadResources();
				}
				java.io.File stop = new java.io.File(mc.runDirectory, "_stopclient");
					if (stop.exists()) {
						stop.delete();
						com.moneyakshaders.MoneyakShaders.LOGGER.info("[Optimized Loading] dev harness: stop file found, scheduling client stop");
						mc.scheduleStop();
					}
				} catch (Throwable ignored) {
				}
			}
			if (moneyakshaders$lifeTicks > 20L * 60L * 20L) {
				com.moneyakshaders.MoneyakShaders.LOGGER.info("[Optimized Loading] dev harness: max lifetime reached, scheduling client stop");
				mc.scheduleStop();
			}
		}
	}

	/** Force-write the chat history when the game shuts down so the final messages aren't lost. */
	@Inject(method = "close", at = @At("HEAD"), require = 0)
	private void moneyakshaders$flushChatOnClose(CallbackInfo ci) {
		PersistedChatHistory.flushNow();
		// Section disk cache: quitting the game while still in a world never passes the world→null tick,
		// so without this the cache only survived a clean "back to title" disconnect.
		com.moneyakshaders.render.ExperimentalSectionRender.flushDiskCacheOnQuit((MinecraftClient) (Object) this);
	}
}
