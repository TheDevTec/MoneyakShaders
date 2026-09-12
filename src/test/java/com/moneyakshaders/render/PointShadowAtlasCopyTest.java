package com.moneyakshaders.render;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.BufferUtils;

/** Real GPU test in a hidden context; optional on machines without a graphics driver. */
public final class PointShadowAtlasCopyTest {
	public static void main(String[] args) {
		if (!GLFW.glfwInit()) throw new AssertionError("GLFW initialization failed");
		GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
		long window = GLFW.glfwCreateWindow(32, 32, "Atlas regression", 0, 0);
		if (window == 0) throw new AssertionError("No hidden GPU context");
		try {
			GLFW.glfwMakeContextCurrent(window);
			GL.createCapabilities();
			com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
			verifyTerrainShaders();
			verifyAtmosphere();
			verifyGlState();
			verifyArena();
			verify(16, 8); verify(8, 16); verify(8, 8);
			System.out.println("PointShadowAtlasCopy GPU tests PASS (48 faces, down/up/same resolution)");
		} finally { GLFW.glfwDestroyWindow(window); GLFW.glfwTerminate(); }
	}
	private static void verifyGlState() {
		var src = GL11.GL_SRC_ALPHA;
		RenderGlState.blend(src, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
		org.lwjgl.opengl.GL40.glBlendFuncSeparatei(0, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ONE);
		RenderGlState.blend(src, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
		if (GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB) != src
				|| GL11.glGetInteger(GL14.GL_BLEND_DST_RGB) != GL11.GL_ONE_MINUS_SRC_ALPHA)
			throw new AssertionError("MRT blend state leaked into vanilla overlay");
		RenderGlState.colorMask(true, true, true, true);
		GL11.glColorMask(false, false, false, false);
		RenderGlState.colorMask(true, true, true, true);
		var mask = BufferUtils.createByteBuffer(4);
		GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask);
		for (int i=0; i<4; i++) if (mask.get(i)==0) throw new AssertionError("Color mask cache mismatch");
		System.out.println("MRT/vanilla GL state restoration PASS");
	}

private static void verifyAtmosphere() {
	int program = GL20.glCreateProgram();
	int vao = GL30.glGenVertexArrays();
	int fbo = GL30.glGenFramebuffers();
	int depth = GL11.glGenTextures();
	int shadow = GL11.glGenTextures();
	int waterShadow = GL11.glGenTextures();
	int output = GL11.glGenTextures();

	try {
		int vs = compileShader(GL20.GL_VERTEX_SHADER, PostProcess.VS);
		int fs = compileShader(GL20.GL_FRAGMENT_SHADER, AtmosphericShafts.FS);

		GL20.glAttachShader(program, vs);
		GL20.glAttachShader(program, fs);
		GL20.glDeleteShader(vs);
		GL20.glDeleteShader(fs);

		GL20.glLinkProgram(program);
		if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0)
			throw new AssertionError(GL20.glGetProgramInfoLog(program));

		GL20.glUseProgram(program);
		GL30.glBindVertexArray(vao);

		GL11.glBindTexture(GL11.GL_TEXTURE_2D, output);
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, 1, 1, 0,
				GL11.GL_RGBA, GL11.GL_FLOAT, (java.nio.ByteBuffer)null);

		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
		GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
				GL11.GL_TEXTURE_2D, output, 0);

		if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE)
			throw new AssertionError("Atmosphere test FBO incomplete");

		GL13.glActiveTexture(GL13.GL_TEXTURE0);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, depth);
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R32F, 1, 1, 0,
				GL11.GL_RED, GL11.GL_FLOAT, new float[] { 0.55f });
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
		GL20.glUniform1i(GL20.glGetUniformLocation(program, "uDepth"), 0);

		GL13.glActiveTexture(GL13.GL_TEXTURE1);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, shadow);
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL14.GL_DEPTH_COMPONENT24, 1, 1, 0,
				GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, new float[] { 1f });
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE, GL30.GL_COMPARE_REF_TO_TEXTURE);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_FUNC, GL11.GL_LEQUAL);

		for (int i = 0; i < 3; i++)
			GL20.glUniform1i(GL20.glGetUniformLocation(program, "uShadow" + i), 1);

	GL13.glActiveTexture(GL13.GL_TEXTURE2);
	GL11.glBindTexture(GL11.GL_TEXTURE_2D, waterShadow);

	var whitePixel = BufferUtils.createByteBuffer(1);
	whitePixel.put((byte)255).flip();

	GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R8, 1, 1, 0,
			GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, whitePixel);
	GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
	GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);

	for (int i = 0; i < 3; i++)
		GL20.glUniform1i(GL20.glGetUniformLocation(program, "uWaterShadow" + i), 2);

		var identity = new org.joml.Matrix4f();
		try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
			var matrix = identity.get(stack.mallocFloat(16));

			GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program, "uInvProj"), false, matrix);
			matrix.rewind();
			GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program, "uInvView"), false, matrix);

			for (int i = 0; i < 3; i++) {
				matrix.rewind();
				GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program, "uLightMVP" + i), false, matrix);
			}
		}

		GL20.glUniform3f(GL20.glGetUniformLocation(program, "uShadowOffset0"), 0, 0, 0);
		GL20.glUniform3f(GL20.glGetUniformLocation(program, "uShadowOffset1"), 0, 0, 0);
		GL20.glUniform3f(GL20.glGetUniformLocation(program, "uShadowOffset2"), 0, 0, 0);

		GL20.glUniform3f(GL20.glGetUniformLocation(program, "uCameraWorld"), 0, 64, 0);
		GL20.glUniform3f(GL20.glGetUniformLocation(program, "uLightDir"), 0.25f, 0.9f, 0.3f);
		GL20.glUniform3f(GL20.glGetUniformLocation(program, "uColor"), 1f, 0.9f, 0.75f);

		GL20.glUniform1f(GL20.glGetUniformLocation(program, "uStrength"), 1f);
		GL20.glUniform1f(GL20.glGetUniformLocation(program, "uMoon"), 0f);
		GL20.glUniform1f(GL20.glGetUniformLocation(program, "uWater"), 0f);
		GL20.glUniform1f(GL20.glGetUniformLocation(program, "uTime"), 0f);
		GL20.glUniform1f(GL20.glGetUniformLocation(program, "uNear"), 0.05f);
		GL20.glUniform1f(GL20.glGetUniformLocation(program, "uFar"), 256f);
		GL20.glUniform1f(GL20.glGetUniformLocation(program, "uDensity"), 1f);
		GL20.glUniform1f(GL20.glGetUniformLocation(program, "uRainFactor"), 0f);
		GL20.glUniform1i(GL20.glGetUniformLocation(program, "uDebug"), 0);

		GL20.glUniform1f(GL20.glGetUniformLocation(program, "uCascadeEnd0"), 48f);
		GL20.glUniform1f(GL20.glGetUniformLocation(program, "uCascadeEnd1"), 112f);
		GL20.glUniform1f(GL20.glGetUniformLocation(program, "uCascadeEnd2"), 224f);
		GL20.glUniform1f(GL20.glGetUniformLocation(program, "uCascadeBlend0"), 6f);
		GL20.glUniform1f(GL20.glGetUniformLocation(program, "uCascadeBlend1"), 11f);

		GL11.glViewport(0, 0, 1, 1);
		GL11.glDisable(GL11.GL_DEPTH_TEST);
		GL11.glDisable(GL11.GL_BLEND);
		GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);

		float[] pixel = new float[4];
		GL11.glReadPixels(0, 0, 1, 1, GL11.GL_RGBA, GL11.GL_FLOAT, pixel);

		if (!Float.isFinite(pixel[0]) || !Float.isFinite(pixel[1]) || !Float.isFinite(pixel[2]))
			throw new AssertionError("Atmosphere produced non-finite result: "
					+ java.util.Arrays.toString(pixel));

		if (GL11.glGetError() != GL11.GL_NO_ERROR)
			throw new AssertionError("Atmosphere GL error");

		System.out.println("Atmosphere 3-CSM volumetric shader execution PASS: "
				+ java.util.Arrays.toString(pixel));
	} finally {
		GL20.glUseProgram(0);
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
		GL30.glBindVertexArray(0);

		GL11.glDeleteTextures(depth);
		GL11.glDeleteTextures(shadow);
		GL11.glDeleteTextures(waterShadow);
		GL11.glDeleteTextures(output);
		GL30.glDeleteFramebuffers(fbo);
		GL30.glDeleteVertexArrays(vao);
		GL20.glDeleteProgram(program);
	}
}
	private static void verifyArena() {
		GlArena arena = new GlArena(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, 4096);
		try {
			long first = arena.alloc(256), second = arena.alloc(256);
			var data = BufferUtils.createByteBuffer(256);
			for (int i = 0; i < 256; i++) data.put((byte) i);
			data.flip();
			arena.upload(first, data);
			arena.uploadSlice(second, data, 0, 128);
			arena.uploadSlice(second + 128, data, 128, 128);
			arena.free(first, 256);
			if (arena.alloc(128) != first) throw new AssertionError("best-fit reused freed GPU range");
			var readback = BufferUtils.createByteBuffer(256);
			arena.read(second, readback);
			for (int i = 0; i < 256; i++) if (readback.get(i) != (byte) i)
				throw new AssertionError("arena overlap corrupted live GPU data");
			if (GL11.glGetError() != GL11.GL_NO_ERROR) throw new AssertionError("GPU arena GL error");
			System.out.println("GPU arena upload/reuse/readback PASS");
		} finally { org.lwjgl.opengl.GL15.glDeleteBuffers(arena.buffer()); }
	}
private static void verifyTerrainShaders() {
	verifyProgram(TerrainShaders.VS, TerrainShaders.FS);
	verifyProgram(PostProcess.VS, AtmosphericShafts.FS);
	verifyProgram(PostProcess.VS, PostProcess.COMPOSITE_FS);
	verifyProgram(TerrainShaders.SHADOW_VS, TerrainShaders.SHADOW_FS);
	verifyProgram(TerrainShaders.BOX_VS, TerrainShaders.BOX_FS);
	verifyProgram(TerrainShaders.ITEM_SHADOW_VS, TerrainShaders.ITEM_SHADOW_FS);
	verifyInstanceData();
	System.out.println("Production terrain, shadow and atmosphere shaders compile/link PASS");
}
	private static int compileShader(int type, String source) {
		int shader = org.lwjgl.opengl.GL20.glCreateShader(type);
		org.lwjgl.opengl.GL20.glShaderSource(shader, source);
		org.lwjgl.opengl.GL20.glCompileShader(shader);
		if (org.lwjgl.opengl.GL20.glGetShaderi(shader, org.lwjgl.opengl.GL20.GL_COMPILE_STATUS) == 0) {
			String error = org.lwjgl.opengl.GL20.glGetShaderInfoLog(shader);
			org.lwjgl.opengl.GL20.glDeleteShader(shader);
			throw new AssertionError(error);
		}
		return shader;
	}
	private static void verifyProgram(String vertex, String fragment) {
		int program = org.lwjgl.opengl.GL20.glCreateProgram();
		try {
			int vs = compileShader(org.lwjgl.opengl.GL20.GL_VERTEX_SHADER, vertex);
			org.lwjgl.opengl.GL20.glAttachShader(program, vs);
			org.lwjgl.opengl.GL20.glDeleteShader(vs);
			int fs = compileShader(org.lwjgl.opengl.GL20.GL_FRAGMENT_SHADER, fragment);
			org.lwjgl.opengl.GL20.glAttachShader(program, fs);
			org.lwjgl.opengl.GL20.glDeleteShader(fs);
			org.lwjgl.opengl.GL20.glLinkProgram(program);
			if (org.lwjgl.opengl.GL20.glGetProgrami(program, org.lwjgl.opengl.GL20.GL_LINK_STATUS) == 0)
				throw new AssertionError(org.lwjgl.opengl.GL20.glGetProgramInfoLog(program));
		} finally { org.lwjgl.opengl.GL20.glDeleteProgram(program); }
	}
	/** Execute the production vertex program for two consecutive section instances. */
	private static void verifyInstanceData() {
		int program = org.lwjgl.opengl.GL20.glCreateProgram();
		int vao = GL30.glGenVertexArrays();
		int input = org.lwjgl.opengl.GL15.glGenBuffers(), output = org.lwjgl.opengl.GL15.glGenBuffers();
		try {
			int vs = compileShader(org.lwjgl.opengl.GL20.GL_VERTEX_SHADER, TerrainShaders.VS);
			org.lwjgl.opengl.GL20.glAttachShader(program, vs);
			org.lwjgl.opengl.GL20.glDeleteShader(vs);
			GL30.glTransformFeedbackVaryings(program, new String[] {"vWorldRel", "vSceneVisibility"}, GL30.GL_INTERLEAVED_ATTRIBS);
			org.lwjgl.opengl.GL20.glLinkProgram(program);
			if (org.lwjgl.opengl.GL20.glGetProgrami(program, org.lwjgl.opengl.GL20.GL_LINK_STATUS) == 0)
				throw new AssertionError(org.lwjgl.opengl.GL20.glGetProgramInfoLog(program));
			GL30.glBindVertexArray(vao);
			org.lwjgl.opengl.GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, input);
			org.lwjgl.opengl.GL15.glBufferData(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER,
					new float[] {1, 2, 3, 0.25f, 4, 5, 6, 0.75f}, org.lwjgl.opengl.GL15.GL_STATIC_DRAW);
			org.lwjgl.opengl.GL20.glVertexAttribPointer(5, 4, GL11.GL_FLOAT, false, 16, 0L);
			org.lwjgl.opengl.GL20.glEnableVertexAttribArray(5);
			org.lwjgl.opengl.GL33.glVertexAttribDivisor(5, 1);
			org.lwjgl.opengl.GL15.glBindBuffer(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, output);
			org.lwjgl.opengl.GL15.glBufferData(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 32L, org.lwjgl.opengl.GL15.GL_STREAM_READ);
			GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0, output);
			org.lwjgl.opengl.GL20.glUseProgram(program);
			GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);
			GL30.glBeginTransformFeedback(GL11.GL_POINTS);
			org.lwjgl.opengl.GL31.glDrawArraysInstanced(GL11.GL_POINTS, 0, 1, 2);
			GL30.glEndTransformFeedback();
			float[] actual = new float[8], expected = {-7, -6, -5, 0.25f, -4, -3, -2, 0.75f};
			org.lwjgl.opengl.GL15.glGetBufferSubData(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0, actual);
			if (!java.util.Arrays.equals(actual, expected)) throw new AssertionError("Section instance stride/appearance mismatch");
			if (GL11.glGetError() != GL11.GL_NO_ERROR) throw new AssertionError("Instance GPU error");
			System.out.println("GPU section instance position/appearance PASS");
		} finally {
			GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
			org.lwjgl.opengl.GL20.glUseProgram(0);
			GL30.glBindVertexArray(0);
			org.lwjgl.opengl.GL15.glDeleteBuffers(input);
			org.lwjgl.opengl.GL15.glDeleteBuffers(output);
			GL30.glDeleteVertexArrays(vao);
			org.lwjgl.opengl.GL20.glDeleteProgram(program);
		}
	}
	private static void verify(int fromRes, int toRes) {
		int[] from = atlas(fromRes), to = atlas(toRes);
		try {
			GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, from[0]);
			GL11.glEnable(GL11.GL_SCISSOR_TEST);
			for (int face = 0; face < 48; face++) {
				int x = face % 3, y = face / 3;
				GL11.glScissor(x * fromRes, y * fromRes, fromRes, fromRes);
				GL11.glClearDepth((face + 1) / 50.0);
				GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
			}
			GL11.glDisable(GL11.GL_SCISSOR_TEST);
			GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, from[0]);
			GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, to[0]);
			PointShadowAtlasCopy.copy(fromRes, toRes, 8);
			GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, to[0]);
			var values = BufferUtils.createFloatBuffer(toRes * 3 * toRes * 16);
			GL11.glReadPixels(0, 0, toRes * 3, toRes * 16, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, values);
			for (int y = 0; y < toRes * 16; y++) for (int x = 0; x < toRes * 3; x++) {
				float expected = ((y / toRes) * 3 + x / toRes + 1) / 50f;
				if (Math.abs(values.get(y * toRes * 3 + x) - expected) > 0.00001f)
					throw new AssertionError("Depth mismatch at " + x + "," + y);
			}
			int error = GL11.glGetError();
			if (error != GL11.GL_NO_ERROR) throw new AssertionError("GL error " + error);
		} finally {
			GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
			GL30.glDeleteFramebuffers(from[0]); GL30.glDeleteFramebuffers(to[0]);
			GL11.glDeleteTextures(from[1]); GL11.glDeleteTextures(to[1]);
		}
	}
	private static int[] atlas(int res) {
		int fbo = GL30.glGenFramebuffers(), tex = GL11.glGenTextures();
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL14.GL_DEPTH_COMPONENT24, res * 3, res * 16, 0,
				GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (java.nio.ByteBuffer) null);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
		GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
		GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, tex, 0);
		GL11.glDrawBuffer(GL11.GL_NONE); GL11.glReadBuffer(GL11.GL_NONE);
		if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE)
			throw new AssertionError("Incomplete depth atlas");
		return new int[] {fbo, tex};
	}
}
