package com.moneyakshaders.mixin.client;

import net.minecraft.client.render.entity.AbstractBoatEntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.moneyakshaders.MoneyakShaders;
import com.moneyakshaders.MoneyakShadersConfig;

/**
 * Boats render a "water mask" — a depth-writing patch at the water surface that hides the water
 * which would otherwise show inside the open hull. Under our experimental renderer that mask is
 * both pointless (we draw water ourselves) and harmful: it writes depth in the TRANSLUCENT-pass
 * window, and our water (drawn afterwards with depth-test-on / depth-write-off) then fails the
 * depth test across that whole patch → the water around a boat vanishes ("see straight through, as
 * if there's no water"). Glass behind it disappears the same way. Suppressing the mask under our
 * renderer fixes it; the worst cosmetic cost is a sliver of water surface visible inside the hull.
 */
@Mixin(AbstractBoatEntityRenderer.class)
public abstract class BoatWaterMaskMixin {
	private static boolean moneyakshaders$loggedOnce;

	@Inject(method = "renderWaterMask", at = @At("HEAD"), cancellable = true, require = 0)
	private void moneyakshaders$skipWaterMask(CallbackInfo ci) {
		if (MoneyakShadersConfig.get().experimentalRenderer) {
			if (!moneyakshaders$loggedOnce) {
				moneyakshaders$loggedOnce = true;
				MoneyakShaders.LOGGER.info("[Plan C/GL] boat water-mask suppressed under experimental renderer");
			}
			ci.cancel();
		}
	}
}
