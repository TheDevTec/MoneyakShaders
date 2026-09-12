package com.moneyakshaders.mixin.client;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.moneyakshaders.client.EntityRenderTint;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemDisplayContext;

/** Tints every item quad, including layers whose baked quad has no vanilla tint index. */
@Mixin(ItemRenderer.class)
public abstract class ItemRendererEntityTintMixin {
	@WrapMethod(method = "renderItem")
	private static void moneyakshaders$tintQueuedEntityItem(ItemDisplayContext context, MatrixStack matrices,
			VertexConsumerProvider providers, int light, int overlay, int[] tints, List<BakedQuad> quads,
			RenderLayer layer, ItemRenderState.Glint glint, Operation<Void> original) {
		original.call(context, matrices, EntityRenderTint.tintItemProvider(tints, providers), light, overlay,
				tints, quads, layer, glint);
	}
}
