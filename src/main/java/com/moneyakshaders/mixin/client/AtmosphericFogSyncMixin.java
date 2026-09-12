package com.moneyakshaders.mixin.client;

import com.moneyakshaders.MoneyakShadersConfig;
import com.moneyakshaders.render.ExperimentalSectionRender;
import net.minecraft.block.enums.CameraSubmersionType;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.fog.AtmosphericFogModifier;
import net.minecraft.client.render.fog.FogData;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep vanilla models in the same atmospheric volume as custom terrain, including during rain. */
@Mixin(AtmosphericFogModifier.class)
public abstract class AtmosphericFogSyncMixin {
    @Inject(method = "applyStartEndModifier", at = @At("TAIL"))
    private void moneyakshaders$syncAtmosphere(FogData data, Camera camera, ClientWorld world,
            float viewDistance, RenderTickCounter ticks, CallbackInfo ci) {
        if (!MoneyakShadersConfig.get().experimentalRenderer
                || camera.getSubmersionType() != CameraSubmersionType.NONE) return;
        float edge = ExperimentalSectionRender.atmosphericFogEdge();
        if (edge <= 0f) return;
        // Vanilla weather can move environmentalStart behind the camera. That fog was applied
        // only to entities/BEs, tinting even sheltered models blue while adjacent terrain stayed clear.
        data.environmentalStart = Math.max(0f, edge - 48f);
        data.environmentalEnd = edge + 4f;
    }
}
