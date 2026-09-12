package com.moneyakshaders.client;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.moneyakshaders.mixin.client.RenderLayerInvoker;
import com.moneyakshaders.mixin.client.RenderPipelinesInvoker;

import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Frosted text-display background: it keeps normal depth testing but never writes depth.
 * Water, stained glass and later entity layers therefore remain visible through the panel.
 */
public final class DepthSafeTextDisplayLayer {
	private static final RenderLayer LAYER = create();

	private DepthSafeTextDisplayLayer() {
	}

	public static RenderLayer get() {
		return LAYER;
	}

	/** Forces registration before Minecraft precompiles the complete pipeline list. */
	public static void bootstrap() {
		// Class initialization creates and registers LAYER.
	}

	private static RenderLayer create() {
		RenderPipeline pipeline = RenderPipelinesInvoker.moneyakshaders$register(
				new NoDepthWritePipeline(RenderPipelines.RENDERTYPE_TEXT_BG));
		RenderSetup setup = RenderSetup.builder(pipeline)
				.texture("Sampler0", FrostedTextSnapshot.ID)
				.useLightmap()
				.translucent()
				.build();
		return RenderLayerInvoker.moneyakshaders$of("moneyakshaders_text_background_depth_safe", setup);
	}

	private static final class NoDepthWritePipeline extends RenderPipeline {
		private NoDepthWritePipeline(RenderPipeline source) {
			super(
					Identifier.of("moneyakshaders", "pipeline/text_background_depth_safe"),
					Identifier.of("moneyakshaders", "core/frosted_text_background"),
					Identifier.of("moneyakshaders", "core/frosted_text_background"), source.getShaderDefines(),
					samplers(source), source.getUniforms(), source.getBlendFunction(),
					source.getDepthTestFunction(), source.getPolygonMode(), source.isCull(),
					source.isWriteColor(), source.isWriteAlpha(), false, source.getColorLogic(),
					source.getVertexFormat(), source.getVertexFormatMode(),
					source.getDepthBiasScaleFactor(), source.getDepthBiasConstant(), source.getSortKey());
		}

		private static List<String> samplers(RenderPipeline source) {
			List<String> result = new ArrayList<>(source.getSamplers());
			if (!result.contains("Sampler0")) result.add("Sampler0");
			return List.copyOf(result);
		}
	}
}
