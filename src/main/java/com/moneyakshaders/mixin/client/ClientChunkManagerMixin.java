package com.moneyakshaders.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientChunkManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.light.LightingProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.moneyakshaders.MoneyakShadersConfig;
import com.moneyakshaders.client.ChunkLoadTimeline;
import com.moneyakshaders.client.ClientLightDispatcher;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Two glue points for the async light engine:
 *
 * <ul>
 * <li>Registers each new client world's lighting provider with the
 * dispatcher, so only that instance gets deflected to the light thread.</li>
 * <li>{@code onLightUpdate} is invoked from inside {@code doLightUpdates}
 * (now on the light thread) and calls {@code WorldRenderer.scheduleChunkRender},
 * which is not thread-safe - so it is trampolined back to the render
 * thread. The mod's RenderTaskDeduplicator already coalesces the resulting
 * duplicate schedules.</li>
 * </ul>
 */
@Mixin(ClientChunkManager.class)
public abstract class ClientChunkManagerMixin {
	@Shadow @org.spongepowered.asm.mixin.Final private ClientWorld world;
	@Shadow
	public abstract LightingProvider getLightingProvider();

	@Inject(method = "<init>", at = @At("RETURN"))
	private void moneyakshaders$registerLightDispatcher(ClientWorld world, int loadDistance, CallbackInfo ci) {
		ClientLightDispatcher.register(this.getLightingProvider());
		com.moneyakshaders.client.EntityLightCache.clear();
		com.moneyakshaders.client.FastBiomeBlend.clearAll(); // new world → old blended layers are stale
		com.moneyakshaders.render.ClientSectionStore.clear(); // drop old-world section refs
	}

	/**
	 * Mark PHASE_RECEIVED for every section in the freshly-loaded chunk column so
	 * {@link ChunkLoadTimeline} can compute end-to-end latency from network arrival to first draw.
	 * Vertical range covers Overworld + a margin (−4..20 sections = y −64..335). Cheap: ~25 atomic
	 * writes per chunk. No-op when the timeline is disabled.
	 */
	@Inject(method = "loadChunkFromPacket", at = @At("RETURN"), require = 0)
	private void moneyakshaders$markChunkReceived(int x, int z, PacketByteBuf buf,
			Map<?, ?> data, Consumer<?> consumer, CallbackInfoReturnable<WorldChunk> cir) {
		WorldChunk chunk = cir.getReturnValue();
		if (chunk == null) return;
		if (MoneyakShadersConfig.get().experimentalRenderer) {
			// Chunk palettes are available now. Vanilla otherwise starts entity ticking from the
			// later queued lighting/colour completion, although custom terrain is already visible.
			// Preserve vanilla's entity list, tick rate, passengers and server freeze controls.
			((ClientWorldEntityAccess) world).moneyakshaders$entityManager().startTicking(chunk.getPos());
			// A neighbour may already have been meshed while this column was still absent. Its
			// snapshot then correctly saw "unknown/air" at the boundary, but that provisional
			// fluid face must be rebuilt now or it becomes a permanent water wall in an ocean.
			// The renderer coalesces repeats, refreshes only inputs that actually missed this column,
			// and keeps old geometry visible until a necessary replacement is ready.
			com.moneyakshaders.render.ExperimentalSectionRender.remeshReceivedColumnAround(x, z);
		}
		if (!MoneyakShadersConfig.get().chunkLoadTimelineEnabled) return;
		for (int sy = -4; sy <= 20; sy++) {
			ChunkLoadTimeline.mark(ChunkSectionPos.asLong(x, sy, z), ChunkLoadTimeline.PHASE_RECEIVED);
		}
	}

	@Inject(method = "onLightUpdate", at = @At("HEAD"), cancellable = true, require = 0)
	private void moneyakshaders$trampolineOnLightUpdate(
			LightType type,
			ChunkSectionPos pos,
			CallbackInfo ci) {

		MinecraftClient client = MinecraftClient.getInstance();
		MoneyakShadersConfig config = MoneyakShadersConfig.get();

		/*
		* The experimental renderer owns terrain lighting regardless of whether
		* vanilla light propagation itself runs asynchronously.
		*
		* Every vanilla light notification advances the renderer's local revision
		* and schedules a baked-light replacement. Never let vanilla rebuild its
		* discarded terrain mesh.
		*/
		if (config.experimentalRenderer) {
			ClientLightDispatcher.queueLightRender(pos.asLong());
			ci.cancel();
			return;
		}

		/*
		* Legacy renderer + async vanilla light engine:
		* onLightUpdate may arrive from MoneyakShaders-Light. Vanilla's callback
		* touches WorldRenderer, so replay the callback itself on the client thread.
		*
		* Do NOT route this through queueLightRender(): that queue belongs to the
		* experimental terrain renderer and is drained from ExperimentalSectionRender.
		*/
		if (client != null
				&& !client.isOnThread()
				&& ClientLightDispatcher.asyncActive()) {

			if (config.debugStats) {
				com.moneyakshaders.client.DebugStats
						.lightTrampolines
						.incrementAndGet();
			}

			ClientChunkManager self =
					(ClientChunkManager) (Object) this;

			client.execute(() ->
					self.onLightUpdate(type, pos));

			ci.cancel();
		}
	}
}
