package com.moneyakshaders.mixin.client;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.moneyakshaders.MoneyakShaders;
import com.moneyakshaders.client.FrostedTextSnapshot;
import com.moneyakshaders.render.ExperimentalSectionRender;
import com.moneyakshaders.render.PostProcess;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.memory.ObjectAllocator;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Etapa A — run the full-frame post-processing pass after the world is fully drawn
 * (TAIL of WorldRenderer.render), before the hand/GUI.
 *
 * Must list ALL of render's params + CallbackInfo
 * (this MC/mixin rejects a handler that omits trailing params).
 */
@Mixin(WorldRenderer.class)
public abstract class PostProcessMixin {

	private static void moneyakshaders$checkGl(String where) {
		int err;

		while ((err = GL11.glGetError()) != GL11.GL_NO_ERROR) {
			MoneyakShaders.LOGGER.info(
					"[Plan C/Tail] {} -> GL error 0x{}",
					where,
					Integer.toHexString(err));
		}
	}

	@Inject(
			method = "render",
			at = @At("TAIL"),
			require = 0)
	private void moneyakshaders$postProcess(
			ObjectAllocator allocator,
			RenderTickCounter tickCounter,
			boolean renderBlockOutline,
			Camera camera,
			Matrix4f positionMatrix,
			Matrix4f basicProjectionMatrix,
			Matrix4f projectionMatrix,
			GpuBufferSlice fogBuffer,
			Vector4f fogColor,
			boolean renderSky,
			CallbackInfo ci) {

		moneyakshaders$checkGl(
				"WorldRenderer TAIL ENTER");

		MinecraftClient client =
				MinecraftClient.getInstance();

		/*
		 * The frame graph has now completed particles/weather.
		 *
		 * Compose custom translucent terrain here so effects behind water or
		 * stained glass inherit the surface instead of floating above it.
		 */
		moneyakshaders$checkGl(
				"BEFORE renderLateTranslucent");

		ExperimentalSectionRender.renderLateTranslucent();

		moneyakshaders$checkGl(
				"AFTER renderLateTranslucent");

		/*
		 * PostProcess performs either the optional cinematic effects,
		 * the mandatory depth-aware underwater fog, or both.
		 *
		 * It returns immediately when neither is active.
		 */
		moneyakshaders$checkGl(
				"BEFORE PostProcess");

		PostProcess.run(client);

		moneyakshaders$checkGl(
				"AFTER PostProcess");

		/*
		 * Capture after the complete world (including water/glass/entities)
		 * for the next frame's text-display frosted panel.
		 *
		 * Hands and GUI are intentionally excluded.
		 */
		moneyakshaders$checkGl(
				"BEFORE FrostedTextSnapshot.capture");

		FrostedTextSnapshot.capture(client);

		moneyakshaders$checkGl(
				"AFTER FrostedTextSnapshot.capture");

		moneyakshaders$checkGl(
				"WorldRenderer TAIL LEAVE");
	}
}