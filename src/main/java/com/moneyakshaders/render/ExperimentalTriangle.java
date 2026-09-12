package com.moneyakshaders.render;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import com.moneyakshaders.MoneyakShaders;

/**
 * Plan C / Phase 3 — "hello triangle": the smallest end-to-end proof that our
 * own GL pipeline works on the live context (shader compiles+links, VAO/VBO
 * bind, draw call fires, no GL error). Draws one clip-space triangle (no
 * matrices/textures yet) so the only thing under test is the pipeline itself.
 *
 * <p>Next: replace this with the real terrain mesh + camera MVP + block atlas.
 * Render-thread only; lazy GL init on first {@link #render}.
 */
public final class ExperimentalTriangle {
	private static final String VERTEX = "#version 330 core\n"
			+ "layout(location=0) in vec2 aPos;\n"
			+ "layout(location=1) in vec3 aColor;\n"
			+ "out vec3 vColor;\n"
			+ "void main(){ vColor = aColor; gl_Position = vec4(aPos, 0.0, 1.0); }\n";
	// Solid magenta so a single framebuffer read-back is an unambiguous proof.
	private static final String FRAGMENT = "#version 330 core\n"
			+ "in vec3 vColor; out vec4 fragColor;\n"
			+ "void main(){ fragColor = vec4(1.0, 0.0, 1.0, 1.0); }\n";

	private static boolean initialised;
	private static int program;
	private static int vao;
	private static int vbo;
	private static boolean loggedDraw;

	private ExperimentalTriangle() {
	}

	public static void render() {
		if (!initialised) {
			initialised = true;
			init();
		}
		if (program == 0) {
			return;
		}
		boolean depthWasOn = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
		GL11.glDisable(GL11.GL_DEPTH_TEST);

		GL20.glUseProgram(program);
		GL30.glBindVertexArray(vao);
		GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
		GL30.glBindVertexArray(0);
		GL20.glUseProgram(0);

		if (depthWasOn) {
			GL11.glEnable(GL11.GL_DEPTH_TEST);
		}
		if (!loggedDraw) {
			loggedDraw = true;
			int err = GL11.glGetError();
			int[] vp = new int[4];
			GL11.glGetIntegerv(GL11.GL_VIEWPORT, vp);
			ByteBuffer px = MemoryUtil.memAlloc(4);
			GL11.glReadPixels(vp[0] + vp[2] / 2, vp[1] + vp[3] / 2, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, px);
			int r = px.get(0) & 0xFF, g = px.get(1) & 0xFF, b = px.get(2) & 0xFF;
			MemoryUtil.memFree(px);
			boolean magenta = r > 200 && g < 60 && b > 200;
			MoneyakShaders.LOGGER.info("[Plan C/GL] triangle drawn glError=0x{}  centerPixel=({},{},{})  -> rendered to framebuffer: {}",
					Integer.toHexString(err), r, g, b, magenta ? "YES ✓ (magenta)" : "no");
		}
	}

	private static void init() {
		try {
			program = GlShader.build(VERTEX, FRAGMENT);
			if (program == 0) {
				return;
			}
			// 3 verts: x,y, r,g,b
			float[] data = {
					-0.8f, -0.8f, 1f, 0f, 0f,
					0.8f, -0.8f, 0f, 1f, 0f,
					0.0f, 0.8f, 0f, 0f, 1f,
			};
			vao = GL30.glGenVertexArrays();
			vbo = GL15.glGenBuffers();
			GL30.glBindVertexArray(vao);
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
			FloatBuffer fb = MemoryUtil.memAllocFloat(data.length);
			fb.put(data).flip();
			GL15.glBufferData(GL15.GL_ARRAY_BUFFER, fb, GL15.GL_STATIC_DRAW);
			MemoryUtil.memFree(fb);
			GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 20, 0L);
			GL20.glEnableVertexAttribArray(0);
			GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, 20, 8L);
			GL20.glEnableVertexAttribArray(1);
			GL30.glBindVertexArray(0);
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
			MoneyakShaders.LOGGER.info("[Plan C/GL] hello-triangle pipeline ready (vao={} vbo={})", vao, vbo);
		} catch (Throwable t) {
			MoneyakShaders.LOGGER.error("[Plan C/GL] triangle init failed", t);
			program = 0;
		}
	}
}
