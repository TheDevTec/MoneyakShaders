package com.moneyakshaders.mixin.client;

import net.minecraft.client.particle.BillboardParticle;
import net.minecraft.client.particle.BillboardParticleSubmittable;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.render.Camera;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.moneyakshaders.MoneyakShadersConfig;
import com.moneyakshaders.client.DebugStats;

/**
 * Distance cull for billboard particles (smoke, flame, redstone, water, ...).
 *
 * <p>Vanilla already frustum-culls particles (BillboardParticleRenderer only
 * renders ones whose position is inside the view frustum), so "behind you /
 * out of view" is handled. This adds a distance cap on top: particles that are
 * in view but far away barely register on screen, yet each one costs a vertex
 * build (BufferBuilder.copySlow was the #2 render cost in the profile).
 *
 * <p>Runs at the per-particle render HEAD, after vanilla's frustum check, so it
 * only removes far in-view particles. config: particleRenderDistance
 * (0 = unlimited).
 */
@Mixin(BillboardParticle.class)
public abstract class BillboardParticleCullMixin {
	@Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0)
	private void moneyakshaders$distanceCull(BillboardParticleSubmittable submittable, Camera camera, float tickDelta, CallbackInfo ci) {
		MoneyakShadersConfig config = MoneyakShadersConfig.get();
		int dist = config.particleRenderDistance;
		if (dist <= 0) {
			return;
		}
		Box box = ((Particle) (Object) this).getBoundingBox();
		Vec3d cam = camera.getCameraPos();
		double dx = (box.minX + box.maxX) * 0.5 - cam.x;
		double dy = (box.minY + box.maxY) * 0.5 - cam.y;
		double dz = (box.minZ + box.maxZ) * 0.5 - cam.z;
		if (dx * dx + dy * dy + dz * dz > (double) dist * dist) {
			if (config.debugStats) {
				DebugStats.particlesCapped.incrementAndGet();
			}
			ci.cancel();
		}
	}
}
