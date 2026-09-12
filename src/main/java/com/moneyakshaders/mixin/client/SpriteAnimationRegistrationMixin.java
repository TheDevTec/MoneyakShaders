package com.moneyakshaders.mixin.client;

import com.moneyakshaders.client.AnimatedTextureScheduler;
import com.mojang.blaze3d.buffers.GpuBufferSlice;

import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteContents;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Connects real stitched sprites/animators to the runtime animation scheduler. */
@Mixin(Sprite.class)
public abstract class SpriteAnimationRegistrationMixin {
	@Shadow @Final private Identifier atlasId;
	@Shadow @Final private SpriteContents contents;

	@Inject(method = "createAnimator", at = @At("RETURN"), require = 0)
	private void moneyakshaders$registerAnimator(GpuBufferSlice uniformSlice, int mipLevels,
			CallbackInfoReturnable<SpriteContents.Animator> cir) {
		AnimatedTextureScheduler.register(cir.getReturnValue(), atlasId, contents.getId(),
				contents.getWidth(), contents.getHeight());
	}

	@Inject(method = "getTextureSpecificVertexConsumer", at = @At("HEAD"), require = 0)
	private void moneyakshaders$markSpriteUsed(VertexConsumer vertexConsumer,
			CallbackInfoReturnable<VertexConsumer> cir) {
		AnimatedTextureScheduler.markUsed(contents.getId());
	}
}
