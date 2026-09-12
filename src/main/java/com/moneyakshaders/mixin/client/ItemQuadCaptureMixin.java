package com.moneyakshaders.mixin.client;

import java.util.List;

import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.moneyakshaders.client.EntityShadowCapture;

/**
 * Captures item geometry into {@link EntityShadowCapture} so dropped items, items in item frames,
 * and third-person held items cast real shadows. {@code renderBakedItemQuads} is the single choke
 * point where every item's baked quads are emitted to a vertex consumer with the final transform
 * applied — hooking it captures all of them at once. The capture flag is only on during MC's world
 * entity pass, so HUD / inventory item rendering is never captured.
 */
@Mixin(ItemRenderer.class)
public abstract class ItemQuadCaptureMixin {
	@Inject(method = "renderBakedItemQuads", at = @At("HEAD"), require = 0)
	private static void moneyakshaders$captureItemShadow(MatrixStack matrices, VertexConsumer vertexConsumer,
			List<BakedQuad> quads, int[] tints, int light, int overlay, CallbackInfo ci) {
		if (!EntityShadowCapture.active) {
			return;
		}
		Matrix4f m = matrices.peek().getPositionMatrix();
		for (int i = 0; i < quads.size(); i++) {
			EntityShadowCapture.captureItemQuad(m, quads.get(i));
		}
	}
}
