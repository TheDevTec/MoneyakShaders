package com.moneyakshaders.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import com.moneyakshaders.MoneyakShadersConfig;

/**
 * Vanilla's per-thread brightness/AO cache (enabled during chunk mesh
 * building in SectionBuilder) is hardcoded to 100 entries. A single 16^3
 * section with smooth lighting samples thousands of distinct positions, so
 * the cache evicts constantly and most lookups fall through to the light
 * engine - which is exactly the hot path that makes lighting expensive.
 *
 * <p>Enlarging the cap (default 32768) keeps a whole section build in cache.
 * Pure RAM-for-FPS trade: ~0.5-1 MB per mesh thread at the default size.
 *
 * <p>Targets: the size checks in getFloat / method_68891 (int path) and the
 * initial map capacities in the two map-building lambdas.
 */
@Mixin(targets = "net.minecraft.client.render.block.BlockModelRenderer$BrightnessCache")
public abstract class BrightnessCacheMixin {
	@ModifyConstant(
			method = {"getFloat", "method_68891", "method_20552", "method_20553"},
			constant = @Constant(intValue = 100),
			require = 0
	)
	private int moneyakshaders$enlargeBrightnessCache(int vanillaSize) {
		return Math.max(100, MoneyakShadersConfig.get().brightnessCacheSize);
	}
}
