package com.moneyakshaders.mixin.client;

import java.util.Arrays;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.moneyakshaders.client.gui.MoneyakShadersOptions;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.option.VideoOptionsScreen;
import net.minecraft.client.gui.widget.OptionListWidget;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.SimpleOption;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Semi-OptiFine: put all Optimized Loading settings directly in vanilla Video Settings, and remove
 * the vanilla options the mod replaces (smooth lighting, mipmap levels, entity shadows, texture
 * filtering, anisotropic) so they don't fight the mod's own controls.
 */
@Mixin(VideoOptionsScreen.class)
public abstract class VideoOptionsScreenMixin {
	/** Drop the vanilla quality + interface options the mod owns; keep the rest. */
	@ModifyReturnValue(method = "getQualityOptions", at = @At("RETURN"), require = 0)
	private static SimpleOption<?>[] moneyakshaders$dropReplacedOptions(SimpleOption<?>[] original) {
		try {
			GameOptions o = MinecraftClient.getInstance().options;
			return Arrays.stream(original)
					.filter(s -> s != o.getAo()                  // smooth lighting
							// vanilla Mipmap Levels stays VISIBLE: it controls whether the atlas is
							// built with a mip chain at all — the mod's "Terrain mipmaps"/"Anisotropic"
							// sampler settings need it > 0 to have any data to work with.
							&& s != o.getEntityShadows()           // entity shadows
							&& s != o.getTextureFiltering()        // texture filtering
							&& s != o.getMaxAnisotropy()           // anisotropic filtering
							&& s != o.getImprovedTransparency()    // overridden by Plan C translucent pass
							&& s != o.getCutoutLeaves())           // overridden by leaf-canopy depth (MAT_LEAVES_DEEP)
					.toArray(SimpleOption[]::new);
		} catch (Throwable t) {
			com.moneyakshaders.MoneyakShaders.LOGGER.error("[Optimized Loading] video option filter failed", t);
			return original; // never break the screen
		}
	}

	/** Drop "Vignette" from the Interface section (the mod's post-process handles vignette). */
	@ModifyReturnValue(method = "getInterfaceOptions", at = @At("RETURN"), require = 0)
	private static SimpleOption<?>[] moneyakshaders$dropInterfaceOptions(SimpleOption<?>[] original) {
		try {
			GameOptions o = MinecraftClient.getInstance().options;
			SimpleOption<Boolean> vignette = o.getVignette();
			if (vignette.getValue()) {
				vignette.setValue(false); // turn vanilla vignette off by default; mod post-process handles it
			}
			return Arrays.stream(original).filter(s -> s != vignette).toArray(SimpleOption[]::new);
		} catch (Throwable t) {
			com.moneyakshaders.MoneyakShaders.LOGGER.error("[Optimized Loading] interface option filter failed", t);
			return original;
		}
	}

	@Inject(method = "addOptions", at = @At("TAIL"), require = 0)
	private void moneyakshaders$addModOptions(CallbackInfo ci) {
		try {
			OptionListWidget body = ((GameOptionsScreenAccessor)(Object)this).moneyakshaders$getBody();
			if (body == null) return;

			MoneyakShadersOptions.addTo(body);
			MoneyakShadersOptions.addCinematicOptions(body);
		} catch (Throwable t) {
			com.moneyakshaders.MoneyakShaders.LOGGER.error(
					"[Optimized Loading] failed to add options to Video Settings", t);
		}
	}
}
