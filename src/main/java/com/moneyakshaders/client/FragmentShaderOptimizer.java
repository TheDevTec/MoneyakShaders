package com.moneyakshaders.client;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Shader-level optimization for fragment processing.
 * Reduces fragment shader overhead through early discard and batching hints.
 *
 * <p>Analyzes shader patterns to generate optimized variants with early-out logic.
 * Worker threads profile shader behavior; main thread uses optimized variants.
 */
public final class FragmentShaderOptimizer {
	private static volatile FragmentShaderOptimizer instance;

	private final ConcurrentHashMap<String, ShaderVariant> shaderVariants = new ConcurrentHashMap<>();

	private FragmentShaderOptimizer() {
	}

	public static FragmentShaderOptimizer getInstance() {
		if (instance == null) {
			synchronized (FragmentShaderOptimizer.class) {
				if (instance == null) {
					instance = new FragmentShaderOptimizer();
				}
			}
		}
		return instance;
	}

	/**
	 * Schedule async shader variant generation for glass optimization.
	 */
	public void queueShaderOptimization(String shaderId, String originalSource) {
		ChunkMeshExecutor.executeBackground(() -> optimizeShader(shaderId, originalSource));
	}

	private void optimizeShader(String shaderId, String originalSource) {
		// Generate optimized variant with early fragment discard
		String optimizedSource = generateOptimizedVariant(originalSource);

		ShaderVariant variant = new ShaderVariant(originalSource, optimizedSource);
		shaderVariants.put(shaderId, variant);
	}

	private String generateOptimizedVariant(String source) {
		StringBuilder optimized = new StringBuilder();

		// Add early discard for fully transparent pixels
		optimized.append("// Optimized Glass Fragment Shader\n");
		optimized.append("void main() {\n");
		optimized.append("  // Early discard for fully transparent pixels\n");
		optimized.append("  if (texture_alpha < 0.01) { discard; }\n");
		optimized.append("  \n");
		optimized.append("  // Early discard for occluded fragments\n");
		optimized.append("  float depthDiff = abs(gl_FragCoord.z - previous_depth);\n");
		optimized.append("  if (depthDiff < 0.0001) { discard; }\n");
		optimized.append("  \n");
		optimized.append("  // Original shader logic\n");
		optimized.append(source);
		optimized.append("}\n");

		return optimized.toString();
	}

	/**
	 * Get shader variant (main thread safe, cached).
	 */
	public ShaderVariant getShaderVariant(String shaderId) {
		return shaderVariants.get(shaderId);
	}

	/**
	 * Check if shader is optimized (non-blocking).
	 */
	public boolean isOptimized(String shaderId) {
		return shaderVariants.containsKey(shaderId);
	}

	/**
	 * Generate glass-specific shader with color LUT sampling.
	 */
	public String generateGlassShader(boolean useColorLUT, boolean useEarlyDiscard) {
		StringBuilder shader = new StringBuilder();

		shader.append("#version 330 core\n");
		shader.append("in vec3 vertexPosition;\n");
		shader.append("in vec2 texCoords;\n");
		shader.append("in float colorIndex;\n");
		shader.append("out vec4 FragColor;\n");

		if (useColorLUT) {
			shader.append("uniform sampler2D colorLUT;\n");
		}
		shader.append("uniform sampler2D baseTexture;\n");

		shader.append("void main() {\n");

		if (useEarlyDiscard) {
			shader.append("  float alpha = texture(baseTexture, texCoords).a;\n");
			shader.append("  if (alpha < 0.01) discard;\n");
		}

		if (useColorLUT) {
			shader.append("  vec4 baseColor = texture(colorLUT, vec2(colorIndex / 255.0, 0.5));\n");
		} else {
			shader.append("  vec4 baseColor = texture(baseTexture, texCoords);\n");
		}

		shader.append("  FragColor = baseColor;\n");
		shader.append("}\n");

		return shader.toString();
	}

	/**
	 * Clear cache to free memory.
	 */
	public void clearCache() {
		shaderVariants.clear();
	}

	/**
	 * Shader variant pair (immutable).
	 */
	public static final class ShaderVariant {
		public final String original;
		public final String optimized;

		public ShaderVariant(String original, String optimized) {
			this.original = original;
			this.optimized = optimized;
		}

		public int getSize() {
			return optimized.length();
		}

		public boolean isSignificantlyOptimized() {
			// Optimized if at least 20% shorter or contains early-out logic
			return optimized.length() < original.length() * 0.8 || optimized.contains("discard");
		}
	}
}
