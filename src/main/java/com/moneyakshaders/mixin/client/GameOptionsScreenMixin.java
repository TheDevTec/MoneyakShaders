
package com.moneyakshaders.mixin.client;

import net.minecraft.client.gui.screen.option.GameOptionsScreen;
import net.minecraft.client.gui.screen.option.VideoOptionsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.moneyakshaders.MoneyakShadersConfig;

/**
 * Persist the Optimized Loading config when the user leaves Video Settings (where the mod's options
 * now live). {@code close()} has already applied the pending widget values to the live config by
 * its TAIL, so we just write it to disk. Gated to the video screen so other options screens don't
 * trigger a needless save.
 */
@Mixin(GameOptionsScreen.class)
public abstract class GameOptionsScreenMixin {
	@Inject(method = "close", at = @At("TAIL"), require = 0)
	private void moneyakshaders$saveModConfig(CallbackInfo ci) {
		if ((Object) this instanceof VideoOptionsScreen) {
			MoneyakShadersConfig.save();
		}
	}
}
