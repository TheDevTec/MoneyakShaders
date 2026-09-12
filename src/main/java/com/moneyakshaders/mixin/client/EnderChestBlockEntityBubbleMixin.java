package com.moneyakshaders.mixin.client;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.EnderChestBlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.moneyakshaders.client.WaterEffects;

/** Underwater opened ender chests leak rising air bubbles. */
@Mixin(EnderChestBlockEntity.class)
public abstract class EnderChestBlockEntityBubbleMixin {
	@Inject(method = "clientTick", at = @At("TAIL"), require = 0)
	private static void moneyakshaders$enderChestBubbles(World world, BlockPos pos, BlockState state,
			EnderChestBlockEntity blockEntity, CallbackInfo ci) {
		WaterEffects.chestBubbles(world, pos, blockEntity.getAnimationProgress(1.0f));
	}
}
