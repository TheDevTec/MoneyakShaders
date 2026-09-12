package com.moneyakshaders.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.moneyakshaders.MoneyakShaders;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Stability guard for the "Ticking Particle" crash. A particle whose {@code tick()} throws takes the
 * whole game down. The concrete case seen: vanilla {@code FireworksSparkParticle.addExplosionParticle}
 * does {@code ((Explosion) particleManager.addParticle(FIREWORK, …)).setTrail(…)} with NO null check —
 * and {@code addParticle} returns null when the FIREWORK particle factory is missing from the sprite
 * manager (a resource pack that ships a broken particle definition leaves it unregistered). It is NOT
 * this mod's particle cap: {@code addParticle(ParticleEffect,…)} still returns the created particle even
 * when the cap cancels the add. Rather than crash, wrap the per-particle tick so one bad particle is
 * just marked dead and dropped; logged once so it's diagnosable without spamming.
 */
@Mixin(ParticleRenderer.class)
public abstract class ParticleRendererTickGuardMixin {
	@Unique
	private static boolean moneyakshaders$loggedParticleTickError;

	@WrapMethod(method = "tickParticle")
	private void moneyakshaders$guardParticleTick(Particle particle, Operation<Void> original) {
		try {
			original.call(particle);
		} catch (Throwable t) {
			particle.markDead();
			if (!moneyakshaders$loggedParticleTickError) {
				moneyakshaders$loggedParticleTickError = true;
				MoneyakShaders.LOGGER.warn("[Optimized Loading] dropped a particle whose tick() threw — kept the game "
						+ "alive instead of crashing (likely a resource-pack particle with no registered factory)", t);
			}
		}
	}
}
