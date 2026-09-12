package com.moneyakshaders.mixin.client;

import net.minecraft.client.particle.ParticleSpriteManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.moneyakshaders.OplParticles;
import com.moneyakshaders.client.particle.MistParticle;
import com.moneyakshaders.client.particle.RippleParticle;

/**
 * Attaches client factories + sprite sets for the mod's custom particles (no Fabric API, so this is
 * the manual equivalent of ParticleFactoryRegistry). The private sprite-aware {@code register}
 * overload (opened by the access widener) wires the {@code assets/moneyakshaders/particles/*.json}
 * frame lists into a SpriteProvider that the factories receive.
 */
@Mixin(ParticleSpriteManager.class)
public abstract class ParticleSpriteManagerMixin {
	@Inject(method = "init", at = @At("TAIL"), require = 0)
	private void moneyakshaders$registerCustomParticles(CallbackInfo ci) {
		ParticleSpriteManager self = (ParticleSpriteManager) (Object) this;
		// Plain (non-sprite-aware) factories: the particles fetch their frames straight from the
		// particle atlas (directory-source stitched), so no particles-json/SpriteProvider binding.
		self.register(OplParticles.RIPPLE, new RippleParticle.Factory());
		self.register(OplParticles.MIST, new MistParticle.Factory());
		self.register(OplParticles.FOAM, new com.moneyakshaders.client.particle.FoamParticle.Factory());
		self.register(OplParticles.SPRAY, new com.moneyakshaders.client.particle.SprayParticle.Factory());
	}
}
