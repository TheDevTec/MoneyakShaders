package com.moneyakshaders.mixin.client;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import com.moneyakshaders.client.AnimatedTextureScheduler;

import net.minecraft.client.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cancels only the admitted atlas blit; Animator.tick still advances to the correct current frame. */
@Mixin(SpriteContents.Animator.class)
public abstract class SpriteAnimatorUploadBudgetMixin {
	@Inject(method = "upload", at = @At("HEAD"), cancellable = true, require = 0)
	private void moneyakshaders$budgetAnimatedBlit(RenderPass pass, GpuBufferSlice uniform,
			CallbackInfo ci) {
		if (!AnimatedTextureScheduler.allow((SpriteContents.Animator) (Object) this)) ci.cancel();
	}
}
