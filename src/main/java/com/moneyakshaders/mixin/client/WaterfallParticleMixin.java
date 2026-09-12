package com.moneyakshaders.mixin.client;

import net.minecraft.fluid.FlowableFluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.moneyakshaders.MoneyakShadersConfig;

/**
 * Ambient waterfall particles (Particular-style). Vanilla already calls {@code randomDisplayTick}
 * on random nearby fluid positions each client tick, so we piggyback on that sampling instead of
 * doing our own scan: for FLOWING water we add droplets that peel off and streak down the column,
 * and rising splash mist where the fall lands. Source (still) water and lava are untouched.
 */
@Mixin(FluidState.class)
public abstract class WaterfallParticleMixin {
	@Inject(method = "randomDisplayTick", at = @At("TAIL"), require = 0)
	private void moneyakshaders$waterfall(World world, BlockPos pos, Random random, CallbackInfo ci) {
		if (!MoneyakShadersConfig.get().waterfallParticles) {
			return;
		}
		FluidState self = (FluidState) (Object) this;
		if (!self.isIn(FluidTags.WATER) || !self.get(FlowableFluid.FALLING)) {
			return; // horizontal outflow at the lip is moving water, but not a falling column
		}
		double x = pos.getX(), y = pos.getY(), z = pos.getZ();
		if (!world.getBlockState(pos.down()).isAir()) {
			return; // only water with air below it is actually falling
		}

		// Falling sheet: a dense spray of droplets streaking down, with gravity so they trail.
		int drops = 2 + random.nextInt(3);
		for (int k = 0; k < drops; k++) {
			double px = x + 0.2 + random.nextDouble() * 0.6;
			double pz = z + 0.2 + random.nextDouble() * 0.6;
			double py = y + random.nextDouble() * 0.7;
			double vx = (random.nextDouble() - 0.5) * 0.08;
			double vz = (random.nextDouble() - 0.5) * 0.08;
			world.addParticleClient(ParticleTypes.SPLASH, px, py, pz, vx, -0.2 - random.nextDouble() * 0.25, vz);
		}
		if (random.nextInt(3) == 0) {
			world.addParticleClient(ParticleTypes.FALLING_WATER,
					x + 0.3 + random.nextDouble() * 0.4, y + random.nextDouble() * 0.6, z + 0.3 + random.nextDouble() * 0.4,
					0.0, 0.0, 0.0);
		}
		// The plunge churn (swirling clouds/spray where the fall lands) is CONTINUOUS now — driven every
		// tick from WaterEffects.waterfallChurn — because this randomDisplayTick sampling only hits a
		// given block occasionally, which made the splash look like sporadic one-shots.
	}
}
