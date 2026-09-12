package com.moneyakshaders.client;

import com.moneyakshaders.MoneyakShadersConfig;

/**
 * Iris-aware shader optimization coordinator.
 * Integrates uniform batching, state changes, and GPU monitoring.
 */
public final class IrisShaderOptimizer {
	private static volatile IrisShaderOptimizer instance;

	private final IrisUniformBatcher uniformBatcher;
	private final ShaderStateBatcher stateBatcher;
	private final GPUSyncMonitor gpuMonitor;

	private IrisShaderOptimizer() {
		this.uniformBatcher = IrisUniformBatcher.getInstance();
		this.stateBatcher = ShaderStateBatcher.getInstance();
		this.gpuMonitor = GPUSyncMonitor.getInstance();
	}

	public static IrisShaderOptimizer getInstance() {
		IrisShaderOptimizer result = instance;
		if (result == null) {
			synchronized (IrisShaderOptimizer.class) {
				result = instance;
				if (result == null) {
					result = instance = new IrisShaderOptimizer();
				}
			}
		}
		return result;
	}

	/**
	 * Apply Iris shader optimizations before rendering a frame.
	 * Flushes pending uniforms and monitors GPU state.
	 */
	public void optimizeFrame() {
		MoneyakShadersConfig config = MoneyakShadersConfig.get();

		// Flush any pending uniform batches
		uniformBatcher.flushBatch("frame");

		// Update GPU busy state
		gpuMonitor.recordGPUSync();

		// Log if GPU is saturated (debug)
		if (gpuMonitor.isGPUBusy()) {
			// GPU is busy - reduce mesh generation intensity
			if (ChunkMeshExecutor.getQueueSize() > config.sectionRenderBackpressureThreshold) {
				// Already have backpressure active
			}
		}
	}

	public IrisUniformBatcher getUniformBatcher() {
		return uniformBatcher;
	}

	public ShaderStateBatcher getStateBatcher() {
		return stateBatcher;
	}

	public GPUSyncMonitor getGPUMonitor() {
		return gpuMonitor;
	}
}
