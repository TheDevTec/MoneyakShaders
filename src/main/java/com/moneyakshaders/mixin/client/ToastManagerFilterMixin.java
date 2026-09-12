package com.moneyakshaders.mixin.client;

import net.minecraft.client.toast.AdvancementToast;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.ToastManager;
import net.minecraft.client.toast.TutorialToast;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.moneyakshaders.MoneyakShadersConfig;

/**
 * Toast filtering — drop toasts whose category is disabled in the mod's config. The four switches
 * (tutorial, advancement, chat insecure, resource pack) cover the most noisy categories users hit.
 *
 * <p>{@code ToastManager.add(Toast)} is the single funnel every toast goes through; cancelling at
 * HEAD drops the toast entirely (it never appears and never enters the rotation).
 */
@Mixin(ToastManager.class)
public abstract class ToastManagerFilterMixin {
	@Inject(method = "add", at = @At("HEAD"), cancellable = true, require = 0)
	private void moneyakshaders$filterByType(Toast toast, CallbackInfo ci) {
		MoneyakShadersConfig cfg = MoneyakShadersConfig.get();
		if (toast instanceof TutorialToast) {
			if (!cfg.toastTutorial) ci.cancel();
			return;
		}
		if (toast instanceof AdvancementToast) {
			if (!cfg.toastAdvancement) ci.cancel();
			return;
		}
		if (toast instanceof SystemToast sys) {
			SystemToast.Type t = sys.getType();
			if (t == SystemToast.Type.UNSECURE_SERVER_WARNING) {
				if (!cfg.toastChatInsecure) ci.cancel();
				return;
			}
			if (t == SystemToast.Type.PACK_LOAD_FAILURE || t == SystemToast.Type.PACK_COPY_FAILURE) {
				if (!cfg.toastResourcePack) ci.cancel();
				return;
			}
		}
	}
}
