package com.moneyakshaders.mixin.client;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.moneyakshaders.client.WaterEffects;

/** Underwater opened chests (incl. trapped + double chests) leak rising air bubbles. */
@Mixin(ChestBlockEntity.class)
public abstract class ChestBlockEntityBubbleMixin {
	@Inject(method = "clientTick", at = @At("TAIL"), require = 0)
	private static void moneyakshaders$chestBubbles(World world, BlockPos pos, BlockState state,
			ChestBlockEntity blockEntity, CallbackInfo ci) {
		WaterEffects.chestBubbles(world, pos, blockEntity.getAnimationProgress(1.0f));
	}
}
