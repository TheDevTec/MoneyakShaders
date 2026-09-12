package com.moneyakshaders.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.mojang.blaze3d.systems.VertexSorter;
import com.moneyakshaders.client.StableFloatRadixSort;

import net.minecraft.client.util.math.Vec3fArray;

/**
 * Keeps vanilla's stable, descending translucent-quad order while replacing its comparator-based
 * merge sort with an adaptive temporal repair and three-pass integer radix fallback. Large
 * resource-pack entity buffers can contain tens of thousands of quads, making the vanilla
 * O(n log n) Float.compare path a render-thread hotspot.
 */
@Mixin(VertexSorter.class)
public interface VertexSorterRadixMixin {
	@Inject(method = "method_49908", at = @At("HEAD"), cancellable = true)
	private static void moneyakshaders$stableRadixSort(VertexSorter.SortKeyMapper mapper,
			Vec3fArray positions, CallbackInfoReturnable<int[]> cir) {
		cir.setReturnValue(StableFloatRadixSort.sortDescending(mapper, positions));
	}
}
