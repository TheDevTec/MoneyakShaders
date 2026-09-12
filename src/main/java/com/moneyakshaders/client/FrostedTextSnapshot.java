package com.moneyakshaders.client;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.moneyakshaders.MoneyakShadersConfig;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.texture.GlTexture;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

/**
 * Retains the completed world colour from the preceding frame for the frosted
 * text-display shader.
 *
 * Sampling a separate texture avoids undefined feedback from reading the same
 * attachment that the text background is currently writing.
 */
public final class FrostedTextSnapshot {

	public static final Identifier ID =
			Identifier.of(
					"moneyakshaders",
					"dynamic/frosted_text_snapshot");

	private static NativeImageBackedTexture texture;

	private static int copyFbo;

	private static int width;
	private static int height;

	private FrostedTextSnapshot() {
	}

	public static void capture(MinecraftClient client) {
		if (!MoneyakShadersConfig.get().depthSafeTextDisplays
				|| client == null) {
			return;
		}

		Framebuffer framebuffer =
				client.getFramebuffer();

		if (framebuffer == null
				|| !(framebuffer.getColorAttachment()
						instanceof GlTexture source)) {
			return;
		}

		int w =
				framebuffer.textureWidth;

		int h =
				framebuffer.textureHeight;

		if (w <= 0
				|| h <= 0
				|| source.getGlId() <= 0) {
			return;
		}

		ensureTexture(
				client,
				w,
				h);

		if (texture == null
				|| !(texture.getGlTexture()
						instanceof GlTexture destination)) {
			return;
		}

		/*
		 * Read the ACTUAL GL state here.
		 *
		 * Minecraft's modern GPU pipeline can perform framebuffer/texture
		 * changes outside GlStateManager's cached state, especially around
		 * framebuffer recreation / window resize.
		 */
		int previousRead =
				GL11.glGetInteger(
						GL30.GL_READ_FRAMEBUFFER_BINDING);

		int previousDraw =
				GL11.glGetInteger(
						GL30.GL_DRAW_FRAMEBUFFER_BINDING);

		int previousActive =
				GL11.glGetInteger(
						GL13.GL_ACTIVE_TEXTURE);

		GlStateManager._activeTexture(
				GL13.GL_TEXTURE0);

		int previousTexture =
				GL11.glGetInteger(
						GL11.GL_TEXTURE_BINDING_2D);

		if (copyFbo == 0) {
			copyFbo =
					GL30.glGenFramebuffers();
		}

		/*
		 * Use our temporary FBO solely as the READ framebuffer.
		 */
		GlStateManager._glBindFramebuffer(
				GL30.GL_READ_FRAMEBUFFER,
				copyFbo);

		GL30.glFramebufferTexture2D(
				GL30.GL_READ_FRAMEBUFFER,
				GL30.GL_COLOR_ATTACHMENT0,
				GL11.GL_TEXTURE_2D,
				source.getGlId(),
				0);

		GL11.glReadBuffer(
				GL30.GL_COLOR_ATTACHMENT0);

		/*
		 * Copy the completed world colour directly into the persistent
		 * frosted-text snapshot texture.
		 */
		GlStateManager._bindTexture(
				destination.getGlId());

		GL11.glCopyTexSubImage2D(
				GL11.GL_TEXTURE_2D,
				0,
				0,
				0,
				0,
				0,
				w,
				h);

		/*
		 * Restore texture state.
		 */
		GlStateManager._bindTexture(
				previousTexture);

		GlStateManager._activeTexture(
				previousActive);

		/*
		 * READ/DRAW framebuffer bindings are independent, so restore both.
		 */
		GlStateManager._glBindFramebuffer(
				GL30.GL_READ_FRAMEBUFFER,
				previousRead);

		GlStateManager._glBindFramebuffer(
				GL30.GL_DRAW_FRAMEBUFFER,
				previousDraw);
	}

	private static void ensureTexture(
			MinecraftClient client,
			int w,
			int h) {

		if (texture != null
				&& width == w
				&& height == h) {
			return;
		}

		/*
		 * Keep this allocation path state-neutral. This method runs again
		 * whenever the window/main framebuffer changes size.
		 */
		int previousActive =
				GL11.glGetInteger(
						GL13.GL_ACTIVE_TEXTURE);

		GlStateManager._activeTexture(
				GL13.GL_TEXTURE0);

		int previousTexture =
				GL11.glGetInteger(
						GL11.GL_TEXTURE_BINDING_2D);

		texture =
				new NativeImageBackedTexture(
						"moneyakshaders_frosted_text_snapshot",
						w,
						h,
						false);

		width =
				w;

		height =
				h;

		client.getTextureManager()
				.registerTexture(
						ID,
						texture);

		if (texture.getGlTexture()
				instanceof GlTexture gl) {

			GlStateManager._bindTexture(
					gl.getGlId());

			GL11.glTexParameteri(
					GL11.GL_TEXTURE_2D,
					GL11.GL_TEXTURE_MIN_FILTER,
					GL11.GL_LINEAR);

			GL11.glTexParameteri(
					GL11.GL_TEXTURE_2D,
					GL11.GL_TEXTURE_MAG_FILTER,
					GL11.GL_LINEAR);

			/*
			 * GL_CLAMP is invalid in the OpenGL core profile.
			 *
			 * Using it here generated exactly two GL_INVALID_ENUM errors:
			 * one for WRAP_S and one for WRAP_T.
			 */
			GL11.glTexParameteri(
					GL11.GL_TEXTURE_2D,
					GL11.GL_TEXTURE_WRAP_S,
					GL12.GL_CLAMP_TO_EDGE);

GL11.glTexParameteri(
		GL11.GL_TEXTURE_2D,
		GL11.GL_TEXTURE_WRAP_T,
		GL12.GL_CLAMP_TO_EDGE);
		}

		/*
		 * Do not leave texture 0 bound. Restore the actual texture that was
		 * active before snapshot recreation.
		 */
		GlStateManager._bindTexture(
				previousTexture);

		GlStateManager._activeTexture(
				previousActive);
	}
}