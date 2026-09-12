package com.moneyakshaders.render;

import java.nio.ByteBuffer;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import com.mojang.blaze3d.opengl.GlStateManager;

import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.texture.GlTexture;

final class WaterSurfaceResources {
	private static final int WAVE_SIZE = 256;
	private static int sceneLinearColorTex, sceneLinearDepthTex, waterNormalTex;
	private static int copyReadFbo, copyDrawFbo;
	private static int width, height, depthInternalFormat;

	private WaterSurfaceResources() {}

	static int sceneColorTexture() {
		return sceneLinearColorTex;
	}

	static int sceneDepthTexture() {
		return sceneLinearDepthTex;
	}

	static int sceneLinearColorTexture() {
		return sceneLinearColorTex;
	}

	static int sceneLinearDepthTexture() {
		return sceneLinearDepthTex;
	}

	static int waveTexture() {
		ensureWaterNormalTexture();
		return waterNormalTex;
	}

	static int waterNormalTexture() {
		ensureWaterNormalTexture();
		return waterNormalTex;
	}

	static int waterDetailTexture() {
		ensureWaterNormalTexture();
		return waterNormalTex;
	}

	static boolean capture(Framebuffer framebuffer) {
		return captureHdr(framebuffer);
	}

	static boolean captureHdr(Framebuffer framebuffer) {
		if (framebuffer == null || framebuffer.textureWidth <= 0 || framebuffer.textureHeight <= 0) return false;
		if (!(framebuffer.getColorAttachment() instanceof GlTexture color)
				|| !(framebuffer.getDepthAttachment() instanceof GlTexture depth)) return false;

		int colorId = color.getGlId(), depthId = depth.getGlId();
		if (colorId <= 0 || depthId <= 0) return false;

		int w = framebuffer.textureWidth, h = framebuffer.textureHeight;
		int sourceDepthFormat = textureInternalFormat(depthId);
		if (sourceDepthFormat == 0) return false;

		ensureTargets(w, h, sourceDepthFormat);
		if (sceneLinearColorTex == 0 || sceneLinearDepthTex == 0) return false;

		int prevReadFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
		int prevDrawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
		if (copyReadFbo == 0) copyReadFbo = GL30.glGenFramebuffers();
		if (copyDrawFbo == 0) copyDrawFbo = GL30.glGenFramebuffers();

		int depthAttachment = depthAttachment(sourceDepthFormat);

		GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, copyReadFbo);
		GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, colorId, 0);
		GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, depthAttachment, GL11.GL_TEXTURE_2D, depthId, 0);
		GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);

		GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, copyDrawFbo);
		GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, sceneLinearColorTex, 0);
		GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, depthAttachment, GL11.GL_TEXTURE_2D, sceneLinearDepthTex, 0);
		GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);

		boolean complete = GL30.glCheckFramebufferStatus(GL30.GL_READ_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE
				&& GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE;

		if (complete) {
			GL30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
			GL30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
		}

		GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevReadFbo);
		GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDrawFbo);
		return complete;
	}

	private static void ensureTargets(int w, int h, int sourceDepthFormat) {
		ensureWaterNormalTexture();
		if (sceneLinearColorTex != 0 && sceneLinearDepthTex != 0
				&& width == w && height == h && depthInternalFormat == sourceDepthFormat) return;

		width = w;
		height = h;
		depthInternalFormat = sourceDepthFormat;
		if (sceneLinearColorTex == 0) sceneLinearColorTex = GL11.glGenTextures();
		if (sceneLinearDepthTex == 0) sceneLinearDepthTex = GL11.glGenTextures();

		int prevActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
		GlStateManager._activeTexture(GL13.GL_TEXTURE0 + 10);
		int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

		GlStateManager._bindTexture(sceneLinearColorTex);
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, w, h, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (ByteBuffer)null);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

		GlStateManager._bindTexture(sceneLinearDepthTex);
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, sourceDepthFormat, w, h, 0,
				depthExternalFormat(sourceDepthFormat), depthExternalType(sourceDepthFormat), (ByteBuffer)null);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

		GlStateManager._bindTexture(prevTex);
		GlStateManager._activeTexture(prevActive);
	}

	private static void ensureWaterNormalTexture() {
		if (waterNormalTex != 0) return;

		float[] heightMap = new float[WAVE_SIZE * WAVE_SIZE];
		for (int y = 0; y < WAVE_SIZE; y++) {
			for (int x = 0; x < WAVE_SIZE; x++) {
				float u = x / (float)WAVE_SIZE, v = y / (float)WAVE_SIZE;
				float value = 0f, amplitude = 0.58f, weight = 0f;
				for (int octave = 0, cells = 4; octave < 6; octave++, cells <<= 1) {
					value += tileNoise(u, v, cells, 0x51f15e5d + octave * 0x1f123bb5) * amplitude;
					weight += amplitude;
					amplitude *= 0.5f;
				}
				heightMap[y * WAVE_SIZE + x] = value / Math.max(weight, 0.0001f);
			}
		}

		ByteBuffer pixels = MemoryUtil.memAlloc(WAVE_SIZE * WAVE_SIZE * 4);
		try {
			for (int y = 0; y < WAVE_SIZE; y++) {
				for (int x = 0; x < WAVE_SIZE; x++) {
					float h = heightMap[y * WAVE_SIZE + x];
					float hx = heightMap[y * WAVE_SIZE + ((x + 1) & (WAVE_SIZE - 1))]
							- heightMap[y * WAVE_SIZE + ((x - 1 + WAVE_SIZE) & (WAVE_SIZE - 1))];
					float hz = heightMap[((y + 1) & (WAVE_SIZE - 1)) * WAVE_SIZE + x]
							- heightMap[((y - 1 + WAVE_SIZE) & (WAVE_SIZE - 1)) * WAVE_SIZE + x];
					float nx = clamp(-hx * 4.4f, -1f, 1f);
					float nz = clamp(-hz * 4.4f, -1f, 1f);
					float detail = tileNoise(x / (float)WAVE_SIZE, y / (float)WAVE_SIZE, 32, 0x6d2b79f5) * 0.5f + 0.5f;

					pixels.put(toByte(nx * 0.5f + 0.5f));
					pixels.put(toByte(nz * 0.5f + 0.5f));
					pixels.put(toByte(detail));
					pixels.put(toByte(h * 0.5f + 0.5f));
				}
			}
			pixels.flip();

			waterNormalTex = GL11.glGenTextures();
			int prevActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
			GlStateManager._activeTexture(GL13.GL_TEXTURE0 + 5);
			int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

			GlStateManager._bindTexture(waterNormalTex);
			GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, WAVE_SIZE, WAVE_SIZE, 0,
					GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
			GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);

			GlStateManager._bindTexture(prevTex);
			GlStateManager._activeTexture(prevActive);
		} finally {
			MemoryUtil.memFree(pixels);
		}
	}

	private static int textureInternalFormat(int texture) {
		int prevActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
		GlStateManager._activeTexture(GL13.GL_TEXTURE0 + 10);
		int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
		GlStateManager._bindTexture(texture);
		int format = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_INTERNAL_FORMAT);
		GlStateManager._bindTexture(prevTex);
		GlStateManager._activeTexture(prevActive);
		return format;
	}

	private static int depthAttachment(int format) {
		return format == GL30.GL_DEPTH24_STENCIL8 || format == GL30.GL_DEPTH32F_STENCIL8
				? GL30.GL_DEPTH_STENCIL_ATTACHMENT
				: GL30.GL_DEPTH_ATTACHMENT;
	}

	private static int depthExternalFormat(int format) {
		return format == GL30.GL_DEPTH24_STENCIL8 || format == GL30.GL_DEPTH32F_STENCIL8
				? GL30.GL_DEPTH_STENCIL
				: GL11.GL_DEPTH_COMPONENT;
	}

	private static int depthExternalType(int format) {
		if (format == GL30.GL_DEPTH_COMPONENT32F) return GL11.GL_FLOAT;
		if (format == GL14.GL_DEPTH_COMPONENT16) return GL11.GL_UNSIGNED_SHORT;
		if (format == GL14.GL_DEPTH_COMPONENT24 || format == GL14.GL_DEPTH_COMPONENT32) return GL11.GL_UNSIGNED_INT;
		if (format == GL30.GL_DEPTH24_STENCIL8) return GL30.GL_UNSIGNED_INT_24_8;
		if (format == GL30.GL_DEPTH32F_STENCIL8) return GL30.GL_FLOAT_32_UNSIGNED_INT_24_8_REV;
		return GL11.GL_FLOAT;
	}

	private static float tileNoise(float u, float v, int cells, int seed) {
		float x = u * cells, y = v * cells;
		int x0 = floorMod((int)Math.floor(x), cells), y0 = floorMod((int)Math.floor(y), cells);
		int x1 = (x0 + 1) % cells, y1 = (y0 + 1) % cells;
		float tx = smooth(x - (float)Math.floor(x)), ty = smooth(y - (float)Math.floor(y));
		float a = hash(x0, y0, seed), b = hash(x1, y0, seed);
		float c = hash(x0, y1, seed), d = hash(x1, y1, seed);
		return lerp(lerp(a, b, tx), lerp(c, d, tx), ty) * 2f - 1f;
	}

	private static float hash(int x, int y, int seed) {
		int h = seed ^ x * 0x632be5ab ^ y * 0x85157af5;
		h ^= h >>> 16;
		h *= 0x7feb352d;
		h ^= h >>> 15;
		h *= 0x846ca68b;
		h ^= h >>> 16;
		return (h & 0x7fffffff) / 2147483647f;
	}

	private static byte toByte(float value) {
		return (byte)Math.round(clamp(value, 0f, 1f) * 255f);
	}

	private static int floorMod(int a, int b) {
		int r = a % b;
		return r < 0 ? r + b : r;
	}

	private static float smooth(float t) {
		return t * t * (3f - 2f * t);
	}

	private static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}

	private static float clamp(float v, float min, float max) {
		return v < min ? min : Math.min(v, max);
	}
}