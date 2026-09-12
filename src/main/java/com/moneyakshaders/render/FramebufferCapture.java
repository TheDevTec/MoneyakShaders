package com.moneyakshaders.render;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;

import javax.imageio.ImageIO;

import org.lwjgl.opengl.GL11;
import org.lwjgl.system.MemoryUtil;

import com.moneyakshaders.MoneyakShaders;

/**
 * Plan C — dumps the current GL framebuffer to a PNG so the render output can
 * be inspected directly (more reliable than screen-grabbing the dev window).
 * Render-thread only.
 */
public final class FramebufferCapture {
	private FramebufferCapture() {
	}

	public static void capture(String path) {
		try {
			int[] vp = new int[4];
			GL11.glGetIntegerv(GL11.GL_VIEWPORT, vp);
			int w = vp[2];
			int h = vp[3];
			if (w <= 0 || h <= 0) {
				return;
			}
			ByteBuffer buf = MemoryUtil.memAlloc(w * h * 4);
			GL11.glReadPixels(vp[0], vp[1], w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
			BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					int i = (x + y * w) * 4;
					int r = buf.get(i) & 0xFF;
					int g = buf.get(i + 1) & 0xFF;
					int b = buf.get(i + 2) & 0xFF;
					img.setRGB(x, h - 1 - y, (r << 16) | (g << 8) | b); // GL origin is bottom-left
				}
			}
			MemoryUtil.memFree(buf);
			File f = new File(path);
			ImageIO.write(img, "png", f);
			MoneyakShaders.LOGGER.info("[Plan C/GL] framebuffer captured -> {} ({}x{})", f.getAbsolutePath(), w, h);
		} catch (Throwable t) {
			MoneyakShaders.LOGGER.error("[Plan C/GL] capture failed", t);
		}
	}
}
