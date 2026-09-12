package com.moneyakshaders;

import net.minecraft.particle.SimpleParticleType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

/**
 * The mod's custom particle types (registered in the main initializer, before registries freeze).
 * Client-side factories/sprites are attached by {@code ParticleSpriteManagerMixin}; sprite frames
 * live in {@code assets/moneyakshaders/particles/*.json} + {@code textures/particle/*.png}.
 */
public final class OplParticles {
	/** Flat expanding ring on a water surface — raindrop impacts, entity splashes (particle-rain style). */
	public static final SimpleParticleType RIPPLE = new Type(false);
	/** Waterfall plunge foam — flat swirling foam disc on the pool surface. */
	public static final SimpleParticleType FOAM = new Type(false);

	/** Soft drifting ground mist during rain. */
	public static final SimpleParticleType MIST = new Type(false);

	/** Waterfall spray — billboard plume rising above the plunge point. */
	public static final SimpleParticleType SPRAY = new Type(false);

	private OplParticles() {
	}

	public static void register() {
		Registry.register(Registries.PARTICLE_TYPE, Identifier.of("moneyakshaders", "ripple"), RIPPLE);
		Registry.register(Registries.PARTICLE_TYPE, Identifier.of("moneyakshaders", "mist"), MIST);
		Registry.register(Registries.PARTICLE_TYPE, Identifier.of("moneyakshaders", "foam"), FOAM);
		Registry.register(Registries.PARTICLE_TYPE, Identifier.of("moneyakshaders", "spray"), SPRAY);
	}

	/** SimpleParticleType's constructor is protected — trivial subclass to instantiate it. */
	private static final class Type extends SimpleParticleType {
		Type(boolean alwaysShow) {
			super(alwaysShow);
		}
	}
}
