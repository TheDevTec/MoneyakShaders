package com.moneyakshaders.mixin.client;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.moneyakshaders.MoneyakShadersConfig;
import com.moneyakshaders.render.ExperimentalSectionRender;

import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.memory.ObjectAllocator;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Plan C — capture MC's exact world-render state so the custom renderer projects,
 * depth-tests and fogs identically to vanilla. {@code WorldRenderer.render} HEAD
 * gives, in one place (and same frame as our terrain draw later in the framegraph):
 * the camera rotation ({@code positionMatrix}), the real bobbed/distorted projection
 * ({@code basicProjectionMatrix} - the one terrain AND entities actually draw with),
 * the camera position, and the fog colour.
 */
@Mixin(WorldRenderer.class)
public abstract class WorldRendererMatrixCaptureMixin {
	@Inject(method = "render", at = @At("HEAD"), require = 0)
	private void moneyakshaders$captureWorldState(ObjectAllocator allocator, RenderTickCounter tickCounter,
			boolean renderBlockOutline, Camera camera, Matrix4f positionMatrix, Matrix4f basicProjectionMatrix,
			Matrix4f projectionMatrix, GpuBufferSlice fogBuffer, Vector4f fogColor, boolean renderSky, CallbackInfo ci) {
		if (MoneyakShadersConfig.get().experimentalRenderer) {
			Vec3d pos = camera.getCameraPos();
			ExperimentalSectionRender.captureWorldState(positionMatrix, basicProjectionMatrix,
					pos.x, pos.y, pos.z, fogColor);
		}
	}
}
