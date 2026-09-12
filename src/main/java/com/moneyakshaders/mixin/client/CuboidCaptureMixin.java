package com.moneyakshaders.mixin.client;

import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import com.moneyakshaders.client.EntityShadowCapture;

/**
 * Captures each posed model cuboid (head/body/limbs/armor/resource-pack parts) into
 * {@link EntityShadowCapture} during the world entity pass, so entities cast real model-shaped
 * shadows. The capture flag is only on while MC renders world entities (set by the renderer
 * around the entity pass), so GUI / first-person models are not captured.
 */
@Mixin(targets = "net.minecraft.client.model.ModelPart$Cuboid")
public abstract class CuboidCaptureMixin {
	@WrapMethod(method = "renderCuboid")
	private void moneyakshaders$captureForShadow(MatrixStack.Entry entry, VertexConsumer vertices,
			int light, int overlay, int color, Operation<Void> original) {
		// Wrap the complete method: the bulk-render HEAD injector may cancel its body. A second
		// HEAD injector depended on mixin ordering and lost the shadow on the optimized path.
		if (EntityShadowCapture.active) {
			ModelPart.Cuboid cu = (ModelPart.Cuboid) (Object) this;
			EntityShadowCapture.captureCuboid(entry.getPositionMatrix(), cu.minX, cu.minY, cu.minZ, cu.maxX, cu.maxY, cu.maxZ);
		}
		original.call(entry, vertices, light, overlay, color);
	}
}
