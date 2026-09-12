package com.moneyakshaders.mixin.client;

import net.minecraft.client.option.CloudRenderMode;
import net.minecraft.client.render.CloudRenderer;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.moneyakshaders.MoneyakShadersConfig;
import com.moneyakshaders.render.FantasyClouds;

/**
 * Keep Minecraft's cloud renderer as the geometry authority: unlike the old
 * screen-space experiment it is a real world plane, so flying into clouds and
 * looking through them works exactly as it does in vanilla.
 */
@Mixin(CloudRenderer.class)
public abstract class CloudRendererCancelMixin {
	@Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true, require = 0)
	private void moneyakshaders$cancelVanillaClouds(int color, CloudRenderMode mode, float cloudHeight,
			Vec3d pos, long time, float tickProgress, CallbackInfo ci) {
		MoneyakShadersConfig cfg = MoneyakShadersConfig.get();
		if (cfg.experimentalRenderer && !cfg.fantasyClouds) {
			ci.cancel();
		}
	}

	@ModifyVariable(method = "renderClouds", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private float moneyakshaders$cloudHeight(float vanillaHeight) {
		return MoneyakShadersConfig.get().cloudHeight;
	}
}
