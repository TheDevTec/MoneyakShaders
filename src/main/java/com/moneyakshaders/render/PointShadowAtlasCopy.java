package com.moneyakshaders.render;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * Copies a bound D24 point-shadow atlas.
 *
 * Caller owns READ/DRAW framebuffer bindings and must disable scissor.
 */
final class PointShadowAtlasCopy {

	static void copy(int oldRes, int newRes, int slots) {
		if (oldRes <= 0 || newRes <= 0 || slots <= 0) {
			throw new IllegalArgumentException("atlas dimensions");
		}

		int oldWidth = oldRes * 3;
		int oldHeight = oldRes * 2 * slots;

		int newWidth = newRes * 3;
		int newHeight = newRes * 2 * slots;

		GL30.glBlitFramebuffer(
				0,
				0,
				oldWidth,
				oldHeight,
				0,
				0,
				newWidth,
				newHeight,
				GL11.GL_DEPTH_BUFFER_BIT,
				GL11.GL_NEAREST);
	}

	private PointShadowAtlasCopy() {
	}
}