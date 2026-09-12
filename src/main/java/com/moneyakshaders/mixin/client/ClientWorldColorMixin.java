package com.moneyakshaders.mixin.client;

import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.biome.ColorResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.moneyakshaders.MoneyakShadersConfig;
import com.moneyakshaders.client.FastBiomeBlend;

/**
 * Replaces vanilla's biome-tint blend with {@link FastBiomeBlend}: same result (uses the vanilla
 * biome-blend-radius option), but the (2r+1)² per-position box average — the confirmed chunk-load
 * spike — never runs. Vanilla's per-position BiomeColorCache stays in front of us, so this only
 * fires on its misses; our per-layer cache then amortises the whole 16×16 layer.
 */
@Mixin(ClientWorld.class)
public abstract class ClientWorldColorMixin {
	@Inject(method = "calculateColor", at = @At("HEAD"), cancellable = true, require = 0)
	private void moneyakshaders$fastCalculateColor(BlockPos pos, ColorResolver resolver,
			CallbackInfoReturnable<Integer> cir) {
		if (MoneyakShadersConfig.get().fastBiomeBlend) {
			cir.setReturnValue(FastBiomeBlend.getColor((ClientWorld) (Object) this, pos, resolver));
		}
	}

	@Inject(method = "resetChunkColor", at = @At("HEAD"), require = 0)
	private void moneyakshaders$invalidateChunkColor(ChunkPos chunkPos, CallbackInfo ci) {
		FastBiomeBlend.invalidateChunk(chunkPos.x, chunkPos.z);
		// Sections near this chunk may have been MESHED before its biome data arrived — their baked
		// tints (water/grass colour) blended against default biomes and would stay stale forever,
		// showing hard chunk-shaped colour seams (user's "biome blend broken" screenshot). Re-mesh the
		// column ± 1 chunk asynchronously so the tint converges once the real biomes are known.
		com.moneyakshaders.render.ExperimentalSectionRender.remeshColumnAround(chunkPos.x, chunkPos.z);
	}

	@Inject(method = "reloadColor", at = @At("HEAD"), require = 0)
	private void moneyakshaders$reloadColor(CallbackInfo ci) {
		FastBiomeBlend.clearAll();
	}
}
