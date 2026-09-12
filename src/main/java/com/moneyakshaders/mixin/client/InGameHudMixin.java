package com.moneyakshaders.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.moneyakshaders.client.FPSOverlay;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;

@Mixin(InGameHud.class)
public abstract class InGameHudMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void moneyakshaders$renderFps(DrawContext drawContext, RenderTickCounter tickCounter, CallbackInfo ci) {
        try {
            FPSOverlay.get().onRenderDrawContext(drawContext);
        } catch (Throwable ignored) {
            // Silent fail
        }
    }
}
