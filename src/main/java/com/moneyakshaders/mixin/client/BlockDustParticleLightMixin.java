package com.moneyakshaders.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.moneyakshaders.client.DynamicLightSources;

import net.minecraft.client.particle.BlockDustParticle;
import net.minecraft.client.particle.Particle;
import net.minecraft.util.math.Box;

/** Gives block-break fragments the same dynamic packed light level as nearby entities. */
@Mixin(BlockDustParticle.class)
public abstract class BlockDustParticleLightMixin {
	@ModifyReturnValue(method = "getBrightness", at = @At("RETURN"))
	private int moneyakshaders$boostBreakParticleLight(int original) {
		Box box = ((Particle) (Object) this).getBoundingBox();
		return DynamicLightSources.boost((box.minX + box.maxX) * 0.5,
				(box.minY + box.maxY) * 0.5, (box.minZ + box.maxZ) * 0.5, original);
	}
}
