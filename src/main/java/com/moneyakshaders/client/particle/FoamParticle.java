
package com.moneyakshaders.client.particle;

import net.minecraft.client.particle.BillboardParticle;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleFactory;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.SimpleParticleType;
import net.minecraft.util.math.random.Random;

/**
 * Waterfall plunge foam (custom, in the same spirit as the water-impact ripples the user liked):
 * a flat swirling foam disc lying ON the plunge pool, slowly rotating while it expands and fades.
 * Frames foam_0..4 carry the swirl pattern; zRotation spins it for the churning-vortex look.
 */
public class FoamParticle extends BillboardParticle {
	private static final int FRAMES = 5;
	private static final BillboardParticle.Rotator FLAT =
			(quaternion, camera, tickProgress) -> quaternion.rotationX((float) (-Math.PI / 2.0));

	private final Sprite[] frames;
	private final float baseAlpha;
	private final float spin;

	protected FoamParticle(ClientWorld world, double x, double y, double z, float size, Sprite[] frames, Random random) {
		super(world, x, y, z, frames[0]);
		this.frames = frames;
		this.maxAge = 24 + random.nextInt(16);
		this.gravityStrength = 0f;
		this.velocityX = 0;
		this.velocityY = 0;
		this.velocityZ = 0;
		this.scale = size;
		this.baseAlpha = 0.75f;
		this.alpha = this.baseAlpha;
		this.spin = (random.nextBoolean() ? 1f : -1f) * (0.12f + random.nextFloat() * 0.1f);
		this.zRotation = random.nextFloat() * (float) Math.PI * 2f;
		this.lastZRotation = this.zRotation;
	}

	@Override
	public BillboardParticle.Rotator getRotator() {
		return FLAT;
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
		this.lastZRotation = this.zRotation;
		if (this.age++ >= this.maxAge) {
			this.markDead();
			return;
		}
		this.zRotation += this.spin;
		float t = (float) this.age / this.maxAge;
		this.setSprite(frames[Math.min(FRAMES - 1, (int) (t * FRAMES))]);
		this.scale *= 1.012f; // slow expansion
		this.alpha = this.baseAlpha * (1f - t * t);
	}

	public static class Factory implements ParticleFactory<SimpleParticleType> {
		private Sprite[] frames;

		@Override
		public Particle createParticle(SimpleParticleType type, ClientWorld world,
				double x, double y, double z, double vx, double vy, double vz, Random random) {
			if (frames == null) {
				Sprite[] f = new Sprite[FRAMES];
				for (int i = 0; i < FRAMES; i++) {
					f[i] = OplSprites.get("foam_" + i);
					if (f[i] == null) {
						return null;
					}
				}
				frames = f;
			}
			float size = vx > 0.01 ? (float) vx : 0.7f + random.nextFloat() * 0.5f;
			return new FoamParticle(world, x, y, z, size, frames, random);
		}
	}
}
