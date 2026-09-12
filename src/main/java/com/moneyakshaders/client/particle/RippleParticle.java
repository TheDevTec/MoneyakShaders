
package com.moneyakshaders.client.particle;

import net.minecraft.client.particle.BillboardParticle;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleFactory;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.SimpleParticleType;
import net.minecraft.util.math.random.Random;

/**
 * Flat expanding ring lying ON the water surface (particle-rain style): rendered horizontally via a
 * custom {@link Rotator} (quad rotated −90° around X instead of camera-facing), animating through the
 * ripple_0..8 frames while fading out. Static — no velocity, no gravity. Frames come straight from
 * the particles atlas (directory-source stitched), no particles-json binding involved.
 */
public class RippleParticle extends BillboardParticle {
	private static final int FRAMES = 9;
	private static final BillboardParticle.Rotator FLAT =
			(quaternion, camera, tickProgress) -> quaternion.rotationX((float) (-Math.PI / 2.0));

	private final Sprite[] frames;
	private final float baseAlpha;

	protected RippleParticle(ClientWorld world, double x, double y, double z, float size, Sprite[] frames) {
		super(world, x, y, z, frames[0]);
		this.frames = frames;
		this.maxAge = FRAMES;
		this.gravityStrength = 0f;
		this.velocityX = 0;
		this.velocityY = 0;
		this.velocityZ = 0;
		this.scale = size;
		this.baseAlpha = 0.85f;
		this.alpha = this.baseAlpha;
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
		if (this.age++ >= this.maxAge) {
			this.markDead();
			return;
		}
		this.setSprite(frames[Math.min(FRAMES - 1, this.age)]); // frame = expansion of the ring
		this.alpha = this.baseAlpha * (1f - (float) this.age / this.maxAge);
	}

	public static class Factory implements ParticleFactory<SimpleParticleType> {
		private Sprite[] frames; // fetched lazily — the atlas doesn't exist at registration time

		@Override
		public Particle createParticle(SimpleParticleType type, ClientWorld world,
				double x, double y, double z, double vx, double vy, double vz, Random random) {
			if (frames == null) {
				Sprite[] f = new Sprite[FRAMES];
				for (int i = 0; i < FRAMES; i++) {
					f[i] = OplSprites.get("ripple_" + i);
					if (f[i] == null) {
						return null; // atlas not ready this early — skip the spawn
					}
				}
				frames = f;
			}
			// vx carries the requested ring size (0 → default) so callers can make big entity-splash rings.
			float size = vx > 0.01 ? (float) vx : 0.45f + random.nextFloat() * 0.25f;
			return new RippleParticle(world, x, y, z, size, frames);
		}
	}
}
