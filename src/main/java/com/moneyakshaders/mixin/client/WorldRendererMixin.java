package com.moneyakshaders.mixin.client;

import net.minecraft.client.render.WorldRenderer;
import net.minecraft.util.Util;
import net.minecraft.util.thread.NameableExecutor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.moneyakshaders.MoneyakShadersConfig;
import com.moneyakshaders.client.ChunkMeshExecutor;
import com.moneyakshaders.render.ResourceGeneration;

/**
 * Vanilla constructs the ChunkBuilder with the shared "Worker-Main" pool,
 * so chunk meshing competes with world generation and lighting for the same
 * threads. Hand it a dedicated pool instead.
 *
 * <p>{@code require = 0}: if another renderer mod (e.g. Sodium) replaces this
 * code path, the redirect silently does nothing instead of crashing.
 */
@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {
	@Redirect(
			method = "reload()V",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/util/Util;getMainWorkerExecutor()Lnet/minecraft/util/thread/NameableExecutor;"
			),
			require = 0
	)
	private NameableExecutor moneyakshaders$useDedicatedMeshPool() {
		MoneyakShadersConfig config = MoneyakShadersConfig.get();
		if (config.experimentalRenderer && config.skipVanillaChunkBuilds) {
			// The custom renderer owns all terrain meshes in this mode, so allocating a second
			// dedicated vanilla mesh pool only creates competing CPU workers during world load.
			return Util.getMainWorkerExecutor();
		}
		return ChunkMeshExecutor.getOrCreate();
	}

	/**
	 * Spec §13.4: every resource-pack reload bumps {@link ResourceGeneration} so any subsystem
	 * caching resource-derived artefacts (baked models, sprites, shadow atlases) invalidates
	 * atomically at the frame boundary. Cheap: one AtomicLong increment per reload.
	 */
	@Inject(method = "reload()V", at = @At("HEAD"), require = 0)
	private void moneyakshaders$bumpResourceGeneration(CallbackInfo ci) {
		ResourceGeneration.bump();
	}
}
