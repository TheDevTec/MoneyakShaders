package com.moneyakshaders.render;

import com.mojang.blaze3d.opengl.GlStateManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

public final class RenderGlState {

	private static boolean depthTest;
	private static int depthFunc = GL11.GL_LESS;
	private static boolean depthMask = true;

	private static boolean blend;
	private static int blendSrcRgb = GL11.GL_ONE;
	private static int blendDstRgb = GL11.GL_ZERO;
	private static int blendSrcAlpha = GL11.GL_ONE;
	private static int blendDstAlpha = GL11.GL_ZERO;

	private static boolean cull;

	private static boolean polygonOffset;
	private static float polygonFactor;
	private static float polygonUnits;

	private static boolean scissor;

	private static int activeTexture = GL13.GL_TEXTURE0;

	private static final int[] textures = new int[12];

	private RenderGlState() {
	}

	public static boolean depthTest() {
		return depthTest;
	}

	public static int depthFunc() {
		return depthFunc;
	}

	public static boolean depthMask() {
		return depthMask;
	}

	public static boolean blendEnabled() {
		return blend;
	}

	public static int blendSrcRgb() {
		return blendSrcRgb;
	}

	public static int blendDstRgb() {
		return blendDstRgb;
	}

	public static int blendSrcAlpha() {
		return blendSrcAlpha;
	}

	public static int blendDstAlpha() {
		return blendDstAlpha;
	}

	public static boolean cullEnabled() {
		return cull;
	}

	public static boolean polygonOffsetEnabled() {
		return polygonOffset;
	}

	public static float polygonOffsetFactor() {
		return polygonFactor;
	}

	public static float polygonOffsetUnits() {
		return polygonUnits;
	}

	public static boolean scissorEnabled() {
		return scissor;
	}

	public static int activeTexture() {
		return activeTexture;
	}

	public static int texture2D(int unit) {
		if (unit < 0 || unit >= textures.length)
			return 0;

		return textures[unit];
	}

	public static void trackDepthTest(boolean value) {
		depthTest = value;
	}

	public static void trackDepthFunc(int value) {
		depthFunc = value;
	}

	public static void trackDepthMask(boolean value) {
		depthMask = value;
	}

	public static void trackBlend(boolean value) {
		blend = value;
	}

	public static void trackBlendFunc(
			int srcRgb,
			int dstRgb,
			int srcAlpha,
			int dstAlpha) {

		blendSrcRgb = srcRgb;
		blendDstRgb = dstRgb;
		blendSrcAlpha = srcAlpha;
		blendDstAlpha = dstAlpha;
	}

	public static void trackCull(boolean value) {
		cull = value;
	}

	public static void trackPolygonOffset(boolean value) {
		polygonOffset = value;
	}

	public static void trackPolygonOffset(
			float factor,
			float units) {

		polygonFactor = factor;
		polygonUnits = units;
	}

	public static void trackScissor(boolean value) {
		scissor = value;
	}

	public static void trackActiveTexture(int texture) {
		activeTexture = texture;
	}

	public static void trackTexture(int texture) {
		int unit = activeTexture - GL13.GL_TEXTURE0;

		if (unit >= 0 && unit < textures.length)
			textures[unit] = texture;
	}

	public static void trackDeletedTexture(int texture) {
		for (int i = 0; i < textures.length; i++) {
			if (textures[i] == texture)
				textures[i] = 0;
		}
	}

	/**
	 * Indexed OIT blending can bypass Minecraft's cache.
	 * Force cache + actual GL state back into agreement.
	 */
	public static void blend(
			int src,
			int dst,
			int srcAlpha,
			int dstAlpha) {

		GlStateManager._blendFuncSeparate(
				src == GL11.GL_ONE ? GL11.GL_ZERO : GL11.GL_ONE,
				dst,
				srcAlpha,
				dstAlpha);

		GlStateManager._blendFuncSeparate(
				src,
				dst,
				srcAlpha,
				dstAlpha);
	}

	/**
	 * Force the actual GL color mask even if another Minecraft pipeline
	 * changed it through raw GL without updating GlStateManager's cache.
	 */
	public static void colorMask(
			boolean r,
			boolean g,
			boolean b,
			boolean a) {

		GlStateManager._colorMask(!r, !g, !b, !a);
		GlStateManager._colorMask(r, g, b, a);
	}
}