package com.moneyakshaders.mixin.client;

import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.fog.FogData;
import net.minecraft.client.render.fog.WaterFogModifier;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.block.enums.CameraSubmersionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.moneyakshaders.client.UnderwaterFogSync;

/**
 * Makes vanilla's underwater fog (applied to entities, block entities, hand…) use the SAME
 * start/end/colour as the experimental renderer's terrain fog. Without this, vanilla's dense dark
 * fog turned nearby mobs/chests into blue silhouettes while the custom-fogged terrain around them
 * stayed clear — the "blue box around entities under water" report.
 */
@Mixin(WaterFogModifier.class)
public abstract class WaterFogModifierMixin {
	@Inject(method = "applyStartEndModifier", at = @At("TAIL"), require = 0)
	private void moneyakshaders$matchTerrainFog(FogData data, Camera camera, ClientWorld world,
			float viewDistance, RenderTickCounter tickCounter, CallbackInfo ci) {
		if (UnderwaterFogSync.isActive() && camera.getSubmersionType() == CameraSubmersionType.WATER) {
			// The final fullscreen pass fogs every world pipeline uniformly. Disable the per-shader
			// environmental component here to avoid double fog on vanilla entity shaders; the normal
			// render-distance edge fog remains intact.
			data.environmentalStart = 100000f;
			data.environmentalEnd = 100001f;
			data.skyEnd = 100001f;
			data.cloudEnd = 100001f;
		}
	}

	@Inject(method = "getFogColor", at = @At("HEAD"), cancellable = true, require = 0)
	private void moneyakshaders$matchTerrainFogColor(ClientWorld world, Camera camera, int viewDistance,
			float skyDarkness, CallbackInfoReturnable<Integer> cir) {
		if (UnderwaterFogSync.isActive() && camera.getSubmersionType() == CameraSubmersionType.WATER) {
			cir.setReturnValue(UnderwaterFogSync.colorArgb());
		}
	}
}
