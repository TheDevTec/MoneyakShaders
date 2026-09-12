package com.moneyakshaders.mixin.client;

import net.minecraft.block.BlockState;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.moneyakshaders.MoneyakShadersConfig;
import com.moneyakshaders.render.ExperimentalSectionRender;

/**
 * Plan C / Phase 3.7 — capture blockstate changes for the custom renderer.
 *
 * <p>When vanilla terrain builds are skipped, {@code updateBlock} is the canonical geometry-edit
	 * signal. Bulk section-delta packets may only use the scheduling callbacks, so resident custom
	 * meshes retain a compatibility invalidation while unknown first-load sections are ignored.
 */
@Mixin(WorldRenderer.class)
public abstract class ExperimentalDirtyMixin {
	@Inject(method = "updateBlock", at = @At("HEAD"), require = 0)
	private void moneyakshaders$dirtyBlockGeometry(BlockView world, BlockPos pos, BlockState oldState,
			BlockState newState, int flags, CallbackInfo ci) {
		if (MoneyakShadersConfig.get().experimentalRenderer) {
			ExperimentalSectionRender.markBlockDirty(pos, oldState, newState);
		}
	}

	@Inject(method = "scheduleChunkRender(III)V", at = @At("HEAD"), require = 0)
	private void moneyakshaders$dirtyOne(int x, int y, int z, CallbackInfo ci) {
		MoneyakShadersConfig config = MoneyakShadersConfig.get();
		if (config.experimentalRenderer) {
			if (config.skipVanillaChunkBuilds) ExperimentalSectionRender.markScheduledDirty(x, y, z);
			else ExperimentalSectionRender.markDirty(x, y, z);
		}
	}

	@Inject(method = "scheduleChunkRender(IIIZ)V", at = @At("HEAD"), require = 0)
	private void moneyakshaders$dirtyOneImportant(int x, int y, int z, boolean important, CallbackInfo ci) {
		MoneyakShadersConfig config = MoneyakShadersConfig.get();
		if (config.experimentalRenderer) {
			if (config.skipVanillaChunkBuilds) ExperimentalSectionRender.markScheduledDirty(x, y, z);
			else ExperimentalSectionRender.markDirty(x, y, z);
		}
	}

	@Inject(method = "scheduleChunkRenders3x3x3(III)V", at = @At("HEAD"), require = 0)
	private void moneyakshaders$dirtyNeighbourhood(int x, int y, int z, CallbackInfo ci) {
		MoneyakShadersConfig config = MoneyakShadersConfig.get();
		if (config.experimentalRenderer) {
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						if (config.skipVanillaChunkBuilds) ExperimentalSectionRender.markScheduledDirty(x + dx, y + dy, z + dz);
						else ExperimentalSectionRender.markDirty(x + dx, y + dy, z + dz);
					}
				}
			}
		}
	}

	@Inject(method = "scheduleChunkRenders(IIIIII)V", at = @At("HEAD"), require = 0)
	private void moneyakshaders$dirtyRegion(int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
			CallbackInfo ci) {
		MoneyakShadersConfig config = MoneyakShadersConfig.get();
		if (!config.experimentalRenderer) return;
		int sxMin = minX >> 4, syMin = minY >> 4, szMin = minZ >> 4;
		int sxMax = maxX >> 4, syMax = maxY >> 4, szMax = maxZ >> 4;
		for (int sx = sxMin; sx <= sxMax; sx++) {
			for (int sy = syMin; sy <= syMax; sy++) {
				for (int sz = szMin; sz <= szMax; sz++) {
					if (config.skipVanillaChunkBuilds) ExperimentalSectionRender.markScheduledDirty(sx, sy, sz);
					else ExperimentalSectionRender.markDirty(sx, sy, sz);
				}
			}
		}
	}

	/**
	 * Block-coord single call. Mark only the containing section; do NOT pre-mesh neighbours here.
	 * Vanilla fires this again per adjacent section as light propagates, and the async light
	 * dispatcher marks neighbours dirty only once their light has actually updated. Adding a halo
	 * here would remesh neighbours before their lightmap is refreshed, producing hard seams at
	 * section boundaries (the "cut" bug).
	 */
	@Inject(method = "scheduleSectionRender(Lnet/minecraft/util/math/BlockPos;Z)V", at = @At("HEAD"), require = 0)
	private void moneyakshaders$dirtyBlockPos(BlockPos pos, boolean important, CallbackInfo ci) {
		MoneyakShadersConfig config = MoneyakShadersConfig.get();
		if (!config.experimentalRenderer) return;
		int sx = pos.getX() >> 4, sy = pos.getY() >> 4, sz = pos.getZ() >> 4;
		if (config.skipVanillaChunkBuilds) ExperimentalSectionRender.markScheduledDirty(sx, sy, sz);
		else ExperimentalSectionRender.markDirty(sx, sy, sz);
	}
}
