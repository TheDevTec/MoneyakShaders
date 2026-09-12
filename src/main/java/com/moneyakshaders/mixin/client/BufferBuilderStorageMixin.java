package com.moneyakshaders.mixin.client;

import net.minecraft.client.render.BufferBuilderStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import com.moneyakshaders.MoneyakShadersConfig;

/**
 * The block-buffer pool size caps how many chunk meshes can be built
 * concurrently (each in-flight build borrows one buffer set). Vanilla
 * allocates one per core; make sure the pool is never smaller than the
 * configured mesh thread count so the dedicated pool can actually be used.
 */
@Mixin(BufferBuilderStorage.class)
public abstract class BufferBuilderStorageMixin {
	@ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true)
	private static int moneyakshaders$ensurePoolCoversMeshThreads(int vanillaSize) {
		return Math.max(vanillaSize, MoneyakShadersConfig.get().effectiveChunkBuilderThreads());
	}
}
