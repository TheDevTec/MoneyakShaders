package com.moneyakshaders.mixin;

import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.moneyakshaders.MoneyakShadersConfig;

/**
 * Vanilla sizes the shared background worker pool as
 * {@code clamp(cores - 1, 1, max.bg.threads)} - the system property can only
 * shrink the pool, never grow it past {@code cores - 1}. This lets users with
 * SMT/many-core machines override the size exactly (config: backgroundThreads).
 */
@Mixin(Util.class)
public abstract class UtilMixin {
	@Inject(method = "getAvailableBackgroundThreads", at = @At("RETURN"), cancellable = true)
	private static void moneyakshaders$overrideWorkerPoolSize(CallbackInfoReturnable<Integer> cir) {
		MoneyakShadersConfig config = MoneyakShadersConfig.get();
		int configured = config.backgroundThreads;
		if (configured > 0) {
			cir.setReturnValue(Math.min(255, configured));
			return;
		}
		// A dedicated terrain mesh pool runs alongside Util's shared worker pool.  The config
		// already computes a balanced automatic size, but the old mixin only honoured explicit
		// user overrides, leaving the real pool at vanilla's cores-1 and oversubscribing chunk
		// streaming heavily.  Apply the automatic value to the actual executor as well.
		if (config.autoBalanceBackgroundThreads
				&& net.fabricmc.loader.api.FabricLoader.getInstance().getEnvironmentType()
						== net.fabricmc.api.EnvType.CLIENT) {
			cir.setReturnValue(config.effectiveBackgroundThreads());
		}
	}
}
