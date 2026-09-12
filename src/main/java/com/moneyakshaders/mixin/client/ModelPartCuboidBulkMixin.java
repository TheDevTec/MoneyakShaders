package com.moneyakshaders.mixin.client;

import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.moneyakshaders.client.BufferBuilderBulkAccess;
import com.moneyakshaders.client.DebugStats;

/**
 * Keeps vanilla's live ModelPart animation hierarchy, but compiles each immutable cuboid once and
 * submits all of its vertices through one BufferBuilder reservation. Unsupported consumer wrappers
 * retain the exact vanilla path.
 */
@Mixin(ModelPart.Cuboid.class)
public abstract class ModelPartCuboidBulkMixin {
	@Unique private float[] moneyakshaders$vertices;
	@Unique private int moneyakshaders$vertexCount;

	@Inject(method = "renderCuboid", at = @At("HEAD"), cancellable = true)
	private void moneyakshaders$renderBulk(MatrixStack.Entry entry, VertexConsumer consumer,
			int light, int overlay, int color, CallbackInfo ci) {
		if (!(consumer instanceof BufferBuilderBulkAccess bulk)) {
			return;
		}
		if (moneyakshaders$vertices == null) {
			moneyakshaders$compile((ModelPart.Cuboid) (Object) this);
		}
		if (moneyakshaders$vertexCount == 0) {
			ci.cancel();
			return;
		}
		if (bulk.moneyakshaders$appendTransformed(moneyakshaders$vertices, moneyakshaders$vertexCount,
				entry.getPositionMatrix(), entry.getNormalMatrix(), light, overlay, color)) {
			DebugStats.animatedBulkCuboids.incrementAndGet();
			DebugStats.animatedBulkVertices.addAndGet(moneyakshaders$vertexCount);
			ci.cancel();
		}
	}

	@Unique
	private void moneyakshaders$compile(ModelPart.Cuboid cuboid) {
		int count = 0;
		for (ModelPart.Quad side : cuboid.sides) {
			count += side.vertices().length;
		}
		float[] packed = new float[count * 8];
		int o = 0;
		for (ModelPart.Quad side : cuboid.sides) {
			Vector3fc normal = side.direction();
			for (ModelPart.Vertex vertex : side.vertices()) {
				packed[o++] = vertex.worldX();
				packed[o++] = vertex.worldY();
				packed[o++] = vertex.worldZ();
				packed[o++] = vertex.u();
				packed[o++] = vertex.v();
				packed[o++] = normal.x();
				packed[o++] = normal.y();
				packed[o++] = normal.z();
			}
		}
		moneyakshaders$vertices = packed;
		moneyakshaders$vertexCount = count;
	}
}
