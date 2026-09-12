package com.moneyakshaders.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.moneyakshaders.MoneyakShadersConfig;
import com.moneyakshaders.client.PhysicsOptimizationGuard;
import com.moneyakshaders.client.TeleportationDetector;
import com.moneyakshaders.client.TeleportationDetector.TeleportationAnomaly;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.Vec3d;

/**
 * Monitor player movement for optimization-related anomalies.
 * If teleportation detected, trigger safety shutdown of aggressive optimizations.
 *
 * <p>Hook: ClientPlayerEntity.tick() - called every frame for local player.
 */
@Mixin(ClientPlayerEntity.class)
public class PlayerMovementMonitorMixin {
	private static final boolean ENABLE_TELEPORT_MONITOR = true;

	@Inject(method = "tick", at = @At("HEAD"))
	private void onTick(CallbackInfo ci) {
		if (!ENABLE_TELEPORT_MONITOR) {
			return;
		}

		ClientPlayerEntity player = (ClientPlayerEntity) (Object) this;
		Vec3d playerPos = new Vec3d(player.getX(), player.getY(), player.getZ());

		// Check for teleportation anomalies
		TeleportationDetector detector = TeleportationDetector.getInstance();
		TeleportationAnomaly anomaly = detector.checkPlayerMovement("player", playerPos);

		// If critical anomaly detected, disable aggressive optimizations
		if (anomaly.isCritical) {
			PhysicsOptimizationGuard guard = PhysicsOptimizationGuard.getInstance();
			guard.disableAggressiveOptimizations();

			// Log warning
			String message = String.format(
					"[MoneyakShaders] TELEPORTATION ANOMALY DETECTED: %s at %.1f, %.1f, %.1f - SAFETY MODE ACTIVATED",
					anomaly.name(), player.getX(), player.getY(), player.getZ());
			System.err.println(message);
		}
	}
}
