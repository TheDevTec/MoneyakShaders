package com.moneyakshaders.render;

import java.nio.ByteBuffer;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL44;
import org.lwjgl.opengl.GL45;
import org.lwjgl.opengl.GLCapabilities;

import com.moneyakshaders.MoneyakShaders;

/**
 * Plan C / Phase 3.1 — GL capability probe + modern-buffer smoke test.
 *
 * <p>Confirms, on the real GL context (the user's AMD GPU), that the modern
 * fast path our renderer will rely on is available: immutable buffer storage
 * ({@code ARB_buffer_storage} / GL 4.4), Direct State Access ({@code
 * ARB_direct_state_access} / GL 4.5) and multidraw-indirect (GL 4.3) — the same
 * features Sodium/Nvidium use for their wins. Then allocates a 1 MiB
 * persistent + coherent mapped buffer (no per-frame re-upload — write straight
 * into GPU-visible memory) and checks it maps without a GL error.
 *
 * <p>Must run on the render thread with the context current. Runs once, gated
 * behind {@code experimentalRenderer}.
 */
public final class GlBootstrap {
	private static boolean done;
	private static boolean modernPathOk;

	private GlBootstrap() {
	}

	public static void probeOnce() {
		if (done) {
			return;
		}
		done = true;
		try {
			GLCapabilities caps = GL.getCapabilities();
			String vendor = GL11.glGetString(GL11.GL_VENDOR);
			String renderer = GL11.glGetString(GL11.GL_RENDERER);
			String version = GL11.glGetString(GL11.GL_VERSION);

			boolean bufferStorage = caps.OpenGL44 || caps.GL_ARB_buffer_storage;
			boolean dsa = caps.OpenGL45 || caps.GL_ARB_direct_state_access;
			boolean multidraw = caps.OpenGL43 || caps.GL_ARB_multi_draw_indirect;
			boolean bindless = caps.GL_ARB_bindless_texture;
			boolean computeShaders = caps.OpenGL43 || caps.GL_ARB_compute_shader;

			MoneyakShaders.LOGGER.info("[Plan C/GL] {} | {} | {}", vendor, renderer, version);
			MoneyakShaders.LOGGER.info("[Plan C/GL] bufferStorage={} DSA={} multidrawIndirect={} computeShaders={} bindlessTex={}",
					bufferStorage, dsa, multidraw, computeShaders, bindless);

			if (bufferStorage && dsa) {
				int flags = GL30.GL_MAP_WRITE_BIT | GL44.GL_MAP_PERSISTENT_BIT | GL44.GL_MAP_COHERENT_BIT;
				int id = GL45.glCreateBuffers();
				GL45.glNamedBufferStorage(id, 1L << 20, flags);
				ByteBuffer mapped = GL45.glMapNamedBufferRange(id, 0L, 1L << 20, flags);
				int err = GL11.glGetError();
				modernPathOk = mapped != null && err == GL11.GL_NO_ERROR;
				MoneyakShaders.LOGGER.info("[Plan C/GL] persistent+coherent 1 MiB buffer id={} mapped={} glError=0x{}",
						id, mapped != null, Integer.toHexString(err));
				GL45.glUnmapNamedBuffer(id);
				GL15.glDeleteBuffers(id);
			} else {
				MoneyakShaders.LOGGER.warn("[Plan C/GL] modern buffer path unavailable - renderer would fall back to legacy glBufferData");
			}

			MoneyakShaders.LOGGER.info("[Plan C/GL] modern fast path {} on this GPU",
					modernPathOk ? "AVAILABLE ✓" : "NOT available");
		} catch (Throwable t) {
			MoneyakShaders.LOGGER.error("[Plan C/GL] probe failed", t);
		}
	}

	public static boolean modernPathOk() {
		return modernPathOk;
	}
}
