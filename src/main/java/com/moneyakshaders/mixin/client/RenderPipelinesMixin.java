package com.moneyakshaders.mixin.client;

import net.minecraft.client.gl.RenderPipelines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.moneyakshaders.client.DepthSafeTextDisplayLayer;

@Mixin(RenderPipelines.class)
public abstract class RenderPipelinesMixin {
	@Inject(method = "<clinit>", at = @At("TAIL"))
	private static void moneyakshaders$registerDepthSafeTextPipeline(CallbackInfo ci) {
		DepthSafeTextDisplayLayer.bootstrap();
	}
}
