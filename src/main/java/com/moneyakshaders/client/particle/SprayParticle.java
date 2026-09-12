package com.moneyakshaders.client.particle;

import net.minecraft.client.particle.BillboardParticle;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleFactory;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.SimpleParticleType;
import net.minecraft.util.math.random.Random;

/**
 * Waterfall churn foam: chunky white cloud puffs that HUG the pool surface at the plunge point and
 * slowly swirl around it (the user's reference: solid foam banks riding the water line, not rising
 * spray, not flat rings). Each puff orbits the column base with a wobbling radius and a gentle
 * vertical bob a few tenths of a block above the surface.
 */
public class SprayParticle extends BillboardParticle {
	private final double centerX, centerZ; // plunge column centre we orbit around
	private final double baseY;            // water surface height we hug
	private double orbitAngle;
	private double orbitRadius;
	private final double orbitSpeed;       // rad/tick, signed = swirl direction
	private final double radiusPulse;      // slow in/out breathing of the orbit
	private final float baseAlpha;
	private final float baseScale;

	protected SprayParticle(ClientWorld world, double x, double y, double z,
			double cx, double cz, float strength, Sprite sprite, Random random) {
		super(world, x, y, z, sprite);
		this.centerX = cx;
		this.centerZ = cz;
		this.baseY = y;
		this.orbitAngle = Math.atan2(z - cz, x - cx);
		this.orbitRadius = Math.max(0.25, Math.hypot(x - cx, z - cz));
		this.orbitSpeed = (random.nextBoolean() ? 1 : -1) * (0.05 + random.nextDouble() * 0.07);
		this.radiusPulse = random.nextDouble() * Math.PI * 2.0;
		this.maxAge = 40 + random.nextInt(35);
		this.gravityStrength = 0f;
		this.velocityX = 0;
		this.velocityY = 0;
		this.velocityZ = 0;
		this.baseScale = 0.85f + strength * 0.55f + random.nextFloat() * 0.3f;
		this.scale = baseScale * 0.6f; // pops in small, settles fast
		this.baseAlpha = 0.88f + random.nextFloat() * 0.1f;
		this.alpha = this.baseAlpha;
	}

	@Override
	protected BillboardParticle.RenderType getRenderType() {
		return BillboardParticle.RenderType.PARTICLE_ATLAS_TRANSLUCENT;
	}

	@Override
	public void tick() {
		this.lastX = this.x;
		this.lastY = this.y;
		this.lastZ = this.z;
		if (this.age++ >= this.maxAge) {
			this.markDead();
			return;
		}
		float t = (float) this.age / this.maxAge;
		// swirl: orbit the plunge centre, radius breathing in/out, tiny surface bob — never rises
		this.orbitAngle += orbitSpeed;
		double r = orbitRadius + 0.18 * Math.sin(this.age * 0.11 + radiusPulse);
		this.x = centerX + Math.cos(orbitAngle) * r;
		this.z = centerZ + Math.sin(orbitAngle) * r;
		this.y = baseY + 0.05 * Math.sin(this.age * 0.23 + radiusPulse);
		this.scale = baseScale * Math.min(1f, 0.6f + t * 2.5f);
		// solid through the middle of its life, quick fade at the end
		this.alpha = t < 0.75f ? baseAlpha : baseAlpha * (1f - (t - 0.75f) / 0.25f);
	}

	public static class Factory implements ParticleFactory<SimpleParticleType> {
		private Sprite sprite;

		@Override
		public Particle createParticle(SimpleParticleType type, ClientWorld world,
				double x, double y, double z, double vx, double vy, double vz, Random random) {
			if (sprite == null) {
				sprite = OplSprites.get("spray");
				if (sprite == null) {
					return null;
				}
			}
			// vx/vz smuggle the plunge-column centre, vy the fall strength (0..1)
			return new SprayParticle(world, x, y, z, vx, vz, (float) vy, sprite, random);
		}
	}
}
