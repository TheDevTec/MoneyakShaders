package com.moneyakshaders.mixin.client;

import com.moneyakshaders.render.RenderGlState;
import com.mojang.blaze3d.opengl.GlStateManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GlStateManager.class)
public abstract class GlStateManagerMixin {

	@Inject(method = "_enableDepthTest", at = @At("RETURN"))
	private static void moneyak$enableDepthTest(CallbackInfo ci) {
		RenderGlState.trackDepthTest(true);
	}

	@Inject(method = "_disableDepthTest", at = @At("RETURN"))
	private static void moneyak$disableDepthTest(CallbackInfo ci) {
		RenderGlState.trackDepthTest(false);
	}

	@Inject(method = "_depthFunc", at = @At("RETURN"))
	private static void moneyak$depthFunc(int func, CallbackInfo ci) {
		RenderGlState.trackDepthFunc(func);
	}

	@Inject(method = "_depthMask", at = @At("RETURN"))
	private static void moneyak$depthMask(boolean mask, CallbackInfo ci) {
		RenderGlState.trackDepthMask(mask);
	}

	@Inject(method = "_enableBlend", at = @At("RETURN"))
	private static void moneyak$enableBlend(CallbackInfo ci) {
		RenderGlState.trackBlend(true);
	}

	@Inject(method = "_disableBlend", at = @At("RETURN"))
	private static void moneyak$disableBlend(CallbackInfo ci) {
		RenderGlState.trackBlend(false);
	}

	@Inject(method = "_blendFuncSeparate", at = @At("RETURN"))
	private static void moneyak$blendFuncSeparate(
			int srcRgb,
			int dstRgb,
			int srcAlpha,
			int dstAlpha,
			CallbackInfo ci) {

		RenderGlState.trackBlendFunc(
				srcRgb,
				dstRgb,
				srcAlpha,
				dstAlpha);
	}

	@Inject(method = "_enableCull", at = @At("RETURN"))
	private static void moneyak$enableCull(CallbackInfo ci) {
		RenderGlState.trackCull(true);
	}

	@Inject(method = "_disableCull", at = @At("RETURN"))
	private static void moneyak$disableCull(CallbackInfo ci) {
		RenderGlState.trackCull(false);
	}

	@Inject(method = "_enablePolygonOffset", at = @At("RETURN"))
	private static void moneyak$enablePolygonOffset(CallbackInfo ci) {
		RenderGlState.trackPolygonOffset(true);
	}

	@Inject(method = "_disablePolygonOffset", at = @At("RETURN"))
	private static void moneyak$disablePolygonOffset(CallbackInfo ci) {
		RenderGlState.trackPolygonOffset(false);
	}

	@Inject(method = "_polygonOffset", at = @At("RETURN"))
	private static void moneyak$polygonOffset(
			float factor,
			float units,
			CallbackInfo ci) {

		RenderGlState.trackPolygonOffset(factor, units);
	}

	@Inject(method = "_enableScissorTest", at = @At("RETURN"))
	private static void moneyak$enableScissor(CallbackInfo ci) {
		RenderGlState.trackScissor(true);
	}

	@Inject(method = "_disableScissorTest", at = @At("RETURN"))
	private static void moneyak$disableScissor(CallbackInfo ci) {
		RenderGlState.trackScissor(false);
	}

	@Inject(method = "_activeTexture", at = @At("RETURN"))
	private static void moneyak$activeTexture(
			int texture,
			CallbackInfo ci) {

		RenderGlState.trackActiveTexture(texture);
	}

	@Inject(method = "_bindTexture", at = @At("RETURN"))
	private static void moneyak$bindTexture(
			int texture,
			CallbackInfo ci) {

		RenderGlState.trackTexture(texture);
	}

	@Inject(method = "_deleteTexture", at = @At("RETURN"))
	private static void moneyak$deleteTexture(
			int texture,
			CallbackInfo ci) {

		RenderGlState.trackDeletedTexture(texture);
	}
}