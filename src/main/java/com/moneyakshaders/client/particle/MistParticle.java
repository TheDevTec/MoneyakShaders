
package com.moneyakshaders.client.particle;

import net.minecraft.client.particle.BillboardParticle;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleFactory;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.SimpleParticleType;
import net.minecraft.util.math.random.Random;

/**
 * Post-rain ground fog: a FLAT wide sheet of mist hugging the ground at ankle height (rotated
 * horizontal like the ripple particle — not a floating cloud ball), drifting slowly, living
 * 15–40 s with a long fade in/out. Spawned by WaterEffects only over LAND, only after rain ends.
 */
public class MistParticle extends BillboardParticle {
	private static final BillboardParticle.Rotator FLAT =
			(quaternion, camera, tickProgress) -> quaternion.rotationX((float) (-Math.PI / 2.0));

	private final float peakAlpha;

	protected MistParticle(ClientWorld world, double x, double y, double z, Sprite sprite, Random random) {
		super(world, x, y, z, sprite);
		this.maxAge = 300 + random.nextInt(500); // 15–40 s
		this.gravityStrength = 0f;
		this.velocityX = (random.nextDouble() - 0.5) * 0.006;
		this.velocityY = 0;
		this.velocityZ = (random.nextDouble() - 0.5) * 0.006;
		this.scale = 5.0f + random.nextFloat() * 4.0f; // wide patches, large covered area
		this.peakAlpha = 0.14f + random.nextFloat() * 0.08f;
		this.alpha = 0f;
		this.zRotation = random.nextFloat() * (float) Math.PI * 2f;
		this.lastZRotation = this.zRotation;
	}

	@Override
	public BillboardParticle.Rotator getRotator() {
		return FLAT; // lies horizontally — knee-height fog layer, not a cloud ball
	}

	@Override
	protected BillboardParticle.RenderType getRenderType() {
		return BillboardParticle.RenderType.PARTICLE_ATLAS_TRANSLUCENT;
	}

	@Override
	public void tick() {
		super.tick();
		// long fade in (first 20 %), long fade out (last 30 %)
		float t = (float) this.age / this.maxAge;
		float in = Math.min(1f, t / 0.20f);
		float out = Math.min(1f, (1f - t) / 0.30f);
		this.alpha = this.peakAlpha * Math.min(in, out);
	}

	public static class Factory implements ParticleFactory<SimpleParticleType> {
		@Override
		public Particle createParticle(SimpleParticleType type, ClientWorld world,
				double x, double y, double z, double vx, double vy, double vz, Random random) {
			Sprite sprite = OplSprites.get("mist");
			return sprite == null ? null : new MistParticle(world, x, y, z, sprite, random);
		}
	}
}
