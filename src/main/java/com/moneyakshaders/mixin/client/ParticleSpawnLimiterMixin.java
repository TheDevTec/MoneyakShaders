package com.moneyakshaders.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.client.render.Camera;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.moneyakshaders.MoneyakShadersConfig;

/**
 * Drops particles that would spawn farther from the camera than the
 * configured limit (config: particleSpawnDistanceLimit, default 48 blocks)
 * before they are even created - they never get ticked or rendered.
 * Returning null is an existing vanilla code path (particle group caps and
 * missing factories return null too), so callers handle it.
 */
@Mixin(ParticleManager.class)
public abstract class ParticleSpawnLimiterMixin {
	@Inject(
			method = "addParticle(Lnet/minecraft/particle/ParticleEffect;DDDDDD)Lnet/minecraft/client/particle/Particle;",
			at = @At("HEAD"),
			cancellable = true,
			require = 0
	)
	private void moneyakshaders$limitSpawnDistance(ParticleEffect effect, double x, double y, double z,
			double velocityX, double velocityY, double velocityZ, CallbackInfoReturnable<Particle> cir) {
		MoneyakShadersConfig config = MoneyakShadersConfig.get();
		int limit = config.particleSpawnDistanceLimit;
		if (limit <= 0) {
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.gameRenderer == null) {
			return;
		}
		Camera camera = client.gameRenderer.getCamera();
		if (camera == null || !camera.isReady()) {
			return;
		}
		Vec3d cameraPos = camera.getCameraPos();
		double dx = x - cameraPos.x;
		double dy = y - cameraPos.y;
		double dz = z - cameraPos.z;
		if (dx * dx + dy * dy + dz * dz > (double) limit * limit) {
			if (config.debugStats) {
				com.moneyakshaders.client.DebugStats.particlesDropped.incrementAndGet();
			}
			cir.setReturnValue(null);
		}
	}
}
