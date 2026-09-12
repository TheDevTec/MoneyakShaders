package com.moneyakshaders.mixin;

import net.minecraft.particle.ParticleTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.moneyakshaders.OplParticles;

/**
 * Registers the mod's custom particle types at the END of vanilla's own particle registration
 * (ParticleTypes static init) — the only pre-freeze window available without Fabric API (mod
 * entrypoints run after registries are frozen). Appending AFTER all vanilla entries keeps vanilla
 * raw ids untouched, so joining unmodded servers stays compatible.
 */
@Mixin(ParticleTypes.class)
public abstract class ParticleTypesRegisterMixin {
	@Inject(method = "<clinit>", at = @At("TAIL"), require = 0)
	private static void moneyakshaders$registerCustomTypes(CallbackInfo ci) {
		OplParticles.register();
	}
}
