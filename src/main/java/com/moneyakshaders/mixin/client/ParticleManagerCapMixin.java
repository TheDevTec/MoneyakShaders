package com.moneyakshaders.mixin.client;

import java.util.Map;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.client.particle.ParticleRenderer;
import net.minecraft.client.particle.ParticleTextureSheet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.moneyakshaders.MoneyakShadersConfig;
import com.moneyakshaders.client.DebugStats;

/**
 * Global hard cap on the number of active particles. Vanilla only enforces
 * per-group caps, so a big burst (explosions, dense potion/campfire clouds)
 * can pile up thousands of particles that all get ticked and rendered every
 * frame - the lag spike. When the total is at the cap, new particles are
 * dropped before they are queued (config: maxActiveParticles, 0 = vanilla).
 */
@Mixin(ParticleManager.class)
public abstract class ParticleManagerCapMixin {
	@Shadow
	private Map<ParticleTextureSheet, ParticleRenderer<?>> particles;

	@Inject(method = "addParticle(Lnet/minecraft/client/particle/Particle;)V", at = @At("HEAD"), cancellable = true, require = 0)
	private void moneyakshaders$capParticles(Particle particle, CallbackInfo ci) {
		MoneyakShadersConfig config = MoneyakShadersConfig.get();
		int cap = config.maxActiveParticles;
		if (cap <= 0) {
			return;
		}
		int total = 0;
		for (ParticleRenderer<?> renderer : particles.values()) {
			total += renderer.size();
			if (total >= cap) {
				if (config.debugStats) {
					DebugStats.particlesCapped.incrementAndGet();
				}
				ci.cancel();
				return;
			}
		}
	}
}
