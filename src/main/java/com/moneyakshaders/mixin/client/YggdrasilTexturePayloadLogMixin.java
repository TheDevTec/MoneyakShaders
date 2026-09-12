package com.moneyakshaders.mixin.client;

import java.util.concurrent.atomic.AtomicInteger;

import com.mojang.authlib.yggdrasil.YggdrasilMinecraftSessionService;
import com.moneyakshaders.MoneyakShaders;

import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Authlib already treats malformed server-supplied texture properties as an empty skin result, but
 * logs a full ERROR stack trace for every bad skull/profile. Keep that safe fallback while replacing
 * the noisy expected-data exception with a bounded warning. Valid signed and unsigned payloads never
 * pass through this redirect.
 */
@Mixin(value = YggdrasilMinecraftSessionService.class, remap = false)
public abstract class YggdrasilTexturePayloadLogMixin {
	private static final AtomicInteger moneyakshaders$malformedTexturePayloads = new AtomicInteger();

	@Redirect(
			method = "unpackTextures",
			at = @At(value = "INVOKE", target = "Lorg/slf4j/Logger;error(Ljava/lang/String;Ljava/lang/Throwable;)V"),
			require = 0,
			remap = false)
	private void moneyakshaders$compactMalformedTextureWarning(Logger logger, String message, Throwable failure) {
		int count = moneyakshaders$malformedTexturePayloads.incrementAndGet();
		if (count <= 3 || count % 100 == 0) {
			MoneyakShaders.LOGGER.warn(
					"[Optimized Loading] Ignored malformed server texture payload #{} ({})",
					count, failure == null ? "invalid data" : failure.getMessage());
		}
	}
}
