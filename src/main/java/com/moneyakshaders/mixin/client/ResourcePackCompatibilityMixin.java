package com.moneyakshaders.mixin.client;

import net.minecraft.resource.ResourcePackCompatibility;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.moneyakshaders.MoneyakShadersConfig;

/**
 * Legacy resource-pack backend (plan Part E, phase E5): packs made for OLDER (or newer) game
 * versions are normally flagged incompatible — the client silently STRIPS them from options at
 * startup and nags with "made for an older version" otherwise. OptiFine-era entity packs are
 * exactly such packs, and their content (textures/properties) still works fine. This marks every
 * pack compatible so they load and stay enabled; actual content problems remain handled per-file
 * (missing textures fall back, bad properties are skipped by the ETF engine).
 */
@Mixin(ResourcePackCompatibility.class)
public abstract class ResourcePackCompatibilityMixin {
	@Inject(method = "from", at = @At("HEAD"), cancellable = true, require = 0)
	private static void moneyakshaders$forceCompatible(CallbackInfoReturnable<ResourcePackCompatibility> cir) {
		if (MoneyakShadersConfig.get().forceOldPackCompat) {
			cir.setReturnValue(ResourcePackCompatibility.COMPATIBLE);
		}
	}
}
