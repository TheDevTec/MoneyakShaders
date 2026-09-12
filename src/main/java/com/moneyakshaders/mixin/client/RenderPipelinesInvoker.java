package com.moneyakshaders.mixin.client;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.gl.RenderPipelines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(RenderPipelines.class)
public interface RenderPipelinesInvoker {
	@Invoker("register")
	static RenderPipeline moneyakshaders$register(RenderPipeline pipeline) {
		throw new AssertionError();
	}
}
