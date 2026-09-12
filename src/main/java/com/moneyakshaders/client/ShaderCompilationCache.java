package com.moneyakshaders.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache and pre-compile shaders asynchronously.
 * Eliminates shader compilation stalls during gameplay.
 *
 * <p>Queues shader compilation tasks to worker threads. Main thread
 * retrieves cached compiled shaders without waiting for compilation.
 */
public final class ShaderCompilationCache {
	private static volatile ShaderCompilationCache instance;

	private final ConcurrentHashMap<String, CompiledShader> shaderCache = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, Long> compilationQueue = new ConcurrentHashMap<>();

	private ShaderCompilationCache() {
	}

	public static ShaderCompilationCache getInstance() {
		if (instance == null) {
			synchronized (ShaderCompilationCache.class) {
				if (instance == null) {
					instance = new ShaderCompilationCache();
				}
			}
		}
		return instance;
	}

	/**
	 * Schedule async shader compilation.
	 */
	public void queueShaderCompilation(String shaderId, String vertexSource, String fragmentSource) {
		long queueTime = System.currentTimeMillis();
		compilationQueue.put(shaderId, queueTime);
		ChunkMeshExecutor.executeBackground(() -> compileShader(shaderId, vertexSource, fragmentSource, queueTime));
	}

	private void compileShader(String shaderId, String vertexSource, String fragmentSource, long queueTime) {
		try {
			// Simulate shader compilation (in real impl, would call GL shader API)
			simulateCompilation(vertexSource, fragmentSource);

			long compilationTime = System.currentTimeMillis() - queueTime;
			CompiledShader compiled = new CompiledShader(shaderId, compilationTime, true);
			shaderCache.put(shaderId, compiled);
		} catch (Exception e) {
			shaderCache.put(shaderId, new CompiledShader(shaderId, 0, false));
		} finally {
			compilationQueue.remove(shaderId);
		}
	}

	private void simulateCompilation(String vertexSource, String fragmentSource) {
		// Syntax validation
		validateShaderSource(vertexSource);
		validateShaderSource(fragmentSource);

		// In real implementation:
		// - Compile vertex shader with GL
		// - Compile fragment shader with GL
		// - Link program
		// - Validate
	}

	private void validateShaderSource(String source) {
		// Basic syntax validation
		if (source == null || source.isEmpty()) {
			throw new IllegalArgumentException("Shader source is empty");
		}
		if (!source.contains("void")) {
			throw new IllegalArgumentException("Invalid shader: missing entry point");
		}
	}

	/**
	 * Get compiled shader (main thread safe, cached).
	 */
	public CompiledShader getCompiledShader(String shaderId) {
		return shaderCache.get(shaderId);
	}

	/**
	 * Check if shader is compiled (non-blocking).
	 */
	public boolean isShaderCompiled(String shaderId) {
		return shaderCache.containsKey(shaderId);
	}

	/**
	 * Check if shader compilation is in progress.
	 */
	public boolean isCompiling(String shaderId) {
		return compilationQueue.containsKey(shaderId);
	}

	/**
	 * Batch pre-compile shader variants.
	 */
	public void precompileVariants(String baseId, List<ShaderVariant> variants) {
		variants.forEach(v -> queueShaderCompilation(baseId + "_" + v.name, v.vertexSource, v.fragmentSource));
	}

	/**
	 * Get compilation statistics.
	 */
	public CompilationStats getStats() {
		List<Long> compilationTimes = new ArrayList<>();
		int successCount = 0;
		int failureCount = 0;

		for (CompiledShader shader : shaderCache.values()) {
			compilationTimes.add(shader.compilationTimeMs);
			if (shader.success) {
				successCount++;
			} else {
				failureCount++;
			}
		}

		long avgTime = compilationTimes.isEmpty() ? 0 : compilationTimes.stream().mapToLong(Long::longValue).sum()
				/ compilationTimes.size();

		return new CompilationStats(successCount, failureCount, avgTime, compilationQueue.size());
	}

	/**
	 * Clear shader cache to free memory.
	 */
	public void clearCache() {
		shaderCache.clear();
		compilationQueue.clear();
	}

	/**
	 * Compiled shader info (immutable).
	 */
	public static final class CompiledShader {
		public final String id;
		public final long compilationTimeMs;
		public final boolean success;

		public CompiledShader(String id, long compilationTimeMs, boolean success) {
			this.id = id;
			this.compilationTimeMs = compilationTimeMs;
			this.success = success;
		}

		public boolean isReady() {
			return success;
		}
	}

	/**
	 * Shader variant for batch compilation.
	 */
	public static final class ShaderVariant {
		public final String name;
		public final String vertexSource;
		public final String fragmentSource;

		public ShaderVariant(String name, String vertexSource, String fragmentSource) {
			this.name = name;
			this.vertexSource = vertexSource;
			this.fragmentSource = fragmentSource;
		}
	}

	/**
	 * Compilation statistics.
	 */
	public static final class CompilationStats {
		public final int successCount;
		public final int failureCount;
		public final long avgCompilationTimeMs;
		public final int pendingCount;

		public CompilationStats(int successCount, int failureCount, long avgCompilationTimeMs, int pendingCount) {
			this.successCount = successCount;
			this.failureCount = failureCount;
			this.avgCompilationTimeMs = avgCompilationTimeMs;
			this.pendingCount = pendingCount;
		}

		public int getTotalCount() {
			return successCount + failureCount;
		}

		public float getSuccessRate() {
			int total = getTotalCount();
			return total == 0 ? 0 : (float) successCount / total;
		}
	}
}
