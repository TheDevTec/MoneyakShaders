package com.moneyakshaders.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.moneyakshaders.client.EntityRenderTint;
import com.moneyakshaders.render.ExperimentalSectionRender;

import net.minecraft.client.particle.BillboardParticle;
import net.minecraft.client.particle.BlockDustParticle;
import net.minecraft.client.particle.Particle;
import net.minecraft.util.math.Box;

/** Multiplies only block-break billboard vertices by the surrounding placed/dynamic light hue. */
@Mixin(BillboardParticle.class)
public abstract class BlockDustParticleTintMixin {
	@ModifyExpressionValue(method = "renderVertex", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/util/math/ColorHelper;fromFloats(FFFF)I"))
	private int moneyakshaders$tintBreakParticle(int original) {
		if (!((Object) this instanceof BlockDustParticle)) return original;
		Box box = ((Particle) (Object) this).getBoundingBox();
		int tint = ExperimentalSectionRender.lightTintAt((box.minX + box.maxX) * 0.5,
				(box.minY + box.maxY) * 0.5, (box.minZ + box.maxZ) * 0.5);
		return EntityRenderTint.multiply(original, tint);
	}
}
