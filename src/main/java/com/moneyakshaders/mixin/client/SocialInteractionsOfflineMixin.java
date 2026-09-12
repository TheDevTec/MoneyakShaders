
package com.moneyakshaders.mixin.client;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.SocialInteractionsManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Prevents the offline Loom client from doing a failing Mojang block-list HTTPS request on the render
 * thread for every received chat message. A JFR capture showed the full TLS handshake below
 * {@code MinecraftClient.shouldBlockMessages} and a 401 for the synthetic {@code FabricMC} token.
 * Production/authenticated clients are deliberately untouched so Mojang blocking and the social screen
 * retain their normal privacy semantics.
 */
@Mixin(SocialInteractionsManager.class)
public abstract class SocialInteractionsOfflineMixin {
	@Shadow @Final
	private MinecraftClient client;

	@Inject(method = "isPlayerBlocked", at = @At("HEAD"), cancellable = true, require = 0)
	private void moneyakshaders$skipOfflineRemoteBlockList(java.util.UUID uuid,
			CallbackInfoReturnable<Boolean> cir) {
		if (!FabricLoader.getInstance().isDevelopmentEnvironment() || client == null || client.getSession() == null) {
			return;
		}
		String token = client.getSession().getAccessToken();
		if (token == null || token.isBlank() || "FabricMC".equals(token)) {
			cir.setReturnValue(false);
		}
	}
}
