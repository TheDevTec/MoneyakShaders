package com.moneyakshaders.render;

import org.lwjgl.opengl.GL20;

import com.moneyakshaders.MoneyakShaders;

final class GlShader {
	private GlShader() {}

	static int build(String vertexSource, String fragmentSource) {
		int vertex = compile(GL20.GL_VERTEX_SHADER, vertexSource, "vertex");
		if (vertex == 0) return 0;

		int fragment = compile(GL20.GL_FRAGMENT_SHADER, fragmentSource, "fragment");
		if (fragment == 0) {
			GL20.glDeleteShader(vertex);
			return 0;
		}

		int program = GL20.glCreateProgram();
		if (program == 0) {
			GL20.glDeleteShader(vertex);
			GL20.glDeleteShader(fragment);
			return 0;
		}

		GL20.glAttachShader(program, vertex);
		GL20.glAttachShader(program, fragment);
		GL20.glLinkProgram(program);

		boolean linked = GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL20.GL_TRUE;
		String log = GL20.glGetProgramInfoLog(program);
		if (log != null && !log.isBlank()) {
			if (linked) MoneyakShaders.LOGGER.debug("[Plan C/GL] shader link log:\n{}", log);
			else MoneyakShaders.LOGGER.error("[Plan C/GL] shader link failed:\n{}", log);
		}

		GL20.glDetachShader(program, vertex);
		GL20.glDetachShader(program, fragment);
		GL20.glDeleteShader(vertex);
		GL20.glDeleteShader(fragment);

		if (!linked) {
			GL20.glDeleteProgram(program);
			return 0;
		}
		return program;
	}

	private static int compile(int type, String source, String name) {
		if (source == null || source.isBlank()) {
			MoneyakShaders.LOGGER.error("[Plan C/GL] {} shader source is empty", name);
			return 0;
		}

		int shader = GL20.glCreateShader(type);
		if (shader == 0) {
			MoneyakShaders.LOGGER.error("[Plan C/GL] couldn't create {} shader", name);
			return 0;
		}

		GL20.glShaderSource(shader, source);
		GL20.glCompileShader(shader);

		boolean compiled = GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL20.GL_TRUE;
		String log = GL20.glGetShaderInfoLog(shader);
		if (log != null && !log.isBlank()) {
			if (compiled) MoneyakShaders.LOGGER.debug("[Plan C/GL] {} shader log:\n{}", name, log);
			else {
				MoneyakShaders.LOGGER.error("[Plan C/GL] {} shader compile failed:\n{}", name, log);
				logSource(name, source);
			}
		}

		if (!compiled) {
			GL20.glDeleteShader(shader);
			return 0;
		}
		return shader;
	}

	private static void logSource(String name, String source) {
		String[] lines = source.split("\n", -1);
		StringBuilder out = new StringBuilder(source.length() + lines.length * 8);
		for (int i = 0; i < lines.length; i++)
			out.append(String.format("%4d | %s%n", i + 1, lines[i]));
		MoneyakShaders.LOGGER.error("[Plan C/GL] {} shader source:\n{}", name, out);
	}
}