package com.moneyakshaders.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.moneyakshaders.MoneyakShadersConfig;

/**
 * Subtle first-person hand sway — when enabled, the held-item matrix gets a small extra translation
 * driven by the player's horizontal velocity (so the arm "lags" behind acceleration) plus a slow
 * sine bob, on top of vanilla's existing bobView. Tiny amplitude — meant to read as polish, not
 * distract. Disabled by default.
 */
@Mixin(HeldItemRenderer.class)
public abstract class HeldItemRendererSwayMixin {
	private static long moneyakshaders$startMs = System.currentTimeMillis();

	@Inject(method = "renderItem(FLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/network/ClientPlayerEntity;I)V",
			at = @At("HEAD"), require = 0)
	private void moneyakshaders$applyHandSway(float tickProgress, MatrixStack matrices,
			OrderedRenderCommandQueue queue, ClientPlayerEntity player, int light, CallbackInfo ci) {
		if (!MoneyakShadersConfig.get().handSway || player == null) {
			return;
		}
		// Subtle sway: a slow horizontal sine + a velocity-derived offset that lags the player's
		// motion. Amplitudes < 1 px in screen space — felt more than seen.
		float t = (System.currentTimeMillis() - moneyakshaders$startMs) / 1000.0f;
		float bobX = (float) Math.sin(t * 1.6f) * 0.006f;
		float bobY = (float) Math.cos(t * 2.1f) * 0.004f;
		double vx = player.getX() - player.lastX;
		double vz = player.getZ() - player.lastZ;
		float speed = (float) Math.min(0.25, Math.sqrt(vx * vx + vz * vz));
		matrices.translate(bobX + speed * 0.05f, bobY - speed * 0.02f, 0f);
	}
}
