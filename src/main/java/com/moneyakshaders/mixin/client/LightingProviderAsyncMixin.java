package com.moneyakshaders.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.moneyakshaders.client.ClientLightDispatcher;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.ChunkNibbleArray;
import net.minecraft.world.chunk.light.LightingProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Deflects every mutation of the client world's light engine onto the
 * dedicated light thread (see {@link ClientLightDispatcher}). The engine's
 * internal queues are only ever touched by that one thread, so no locks are
 * needed anywhere. When the replayed call arrives back on the light thread,
 * shouldDeflect() is false and the vanilla body runs unchanged.
 *
 * <p>Server-side lighting (ServerLightingProvider) is never registered with
 * the dispatcher and is completely unaffected.
 */
@Mixin(LightingProvider.class)
public abstract class LightingProviderAsyncMixin {
	@Inject(method = "checkBlock", at = @At("HEAD"), cancellable = true, require = 0)
	private void moneyakshaders$asyncCheckBlock(BlockPos pos, CallbackInfo ci) {
		LightingProvider self = (LightingProvider) (Object) this;
		if (ClientLightDispatcher.shouldDeflect(self)) {
			// checkBlock is by far the hottest deflected call (~13 400/s while a world streams in).
			// The old path allocated three objects for every one of them — an immutable BlockPos copy,
			// the capturing lambda, and the queue node. Handing over a packed long allocates nothing.
			ClientLightDispatcher.enqueueCheckBlock(pos.asLong());
			ci.cancel();
		}
	}

	@Inject(method = "setSectionStatus", at = @At("HEAD"), cancellable = true, require = 0)
	private void moneyakshaders$asyncSetSectionStatus(ChunkSectionPos pos, boolean notReady, CallbackInfo ci) {
		LightingProvider self = (LightingProvider) (Object) this;
		if (ClientLightDispatcher.shouldDeflect(self)) {
			ClientLightDispatcher.enqueue(() -> self.setSectionStatus(pos, notReady));
			ci.cancel();
		}
	}

	@Inject(method = "setColumnEnabled", at = @At("HEAD"), cancellable = true, require = 0)
	private void moneyakshaders$asyncSetColumnEnabled(ChunkPos pos, boolean enabled, CallbackInfo ci) {
		LightingProvider self = (LightingProvider) (Object) this;
		if (ClientLightDispatcher.shouldDeflect(self)) {
			// Mutates (and may resize) the enabled-columns set, but only ever on the light thread now.
			// Readers (below) tolerate a torn read instead of taking a lock — no write lock needed.
			ClientLightDispatcher.enqueue(() -> self.setColumnEnabled(pos, enabled));
			ci.cancel();
		}
	}

	/**
	 * Lock-free guard for the enabled-columns read path (isLightingEnabled → isColumnEnabled →
	 * LongOpenHashSet.contains), the single hottest light read during meshing. The light thread's
	 * setColumnEnabled can resize the backing array mid-probe → ArrayIndexOutOfBounds; previously every
	 * read took a ReentrantReadWriteLock read lock to prevent it, which is real CAS overhead on a call
	 * made millions of times per second. The resize race is vanishingly rare (columns flip on chunk
	 * load/unload), so instead we just retry the probe a few times if it throws — a non-throwing
	 * try/catch is free to the JIT, so the common path now has ZERO synchronisation cost.
	 */
	@WrapMethod(method = "isLightingEnabled(J)Z")
	private boolean moneyakshaders$guardIsLightingEnabled(long pos, Operation<Boolean> original) {
		if (!ClientLightDispatcher.asyncActive()) {
			return original.call(pos);
		}
		for (int attempt = 0; attempt < 4; attempt++) {
			try {
				return original.call(pos);
			} catch (ArrayIndexOutOfBoundsException | NullPointerException raceWithResize) {
				// light thread is resizing the set right now — spin a couple of times and re-probe
			}
		}
		return true; // gave up: assume enabled so we never wrongly drop a section's light
	}

	@Inject(method = "propagateLight", at = @At("HEAD"), cancellable = true, require = 0)
	private void moneyakshaders$asyncPropagateLight(ChunkPos pos, CallbackInfo ci) {
		LightingProvider self = (LightingProvider) (Object) this;
		if (ClientLightDispatcher.shouldDeflect(self)) {
			ClientLightDispatcher.enqueue(() -> self.propagateLight(pos));
			ci.cancel();
		}
	}

	@Inject(method = "enqueueSectionData", at = @At("HEAD"), cancellable = true, require = 0)
	private void moneyakshaders$asyncEnqueueSectionData(LightType lightType, ChunkSectionPos pos, ChunkNibbleArray data, CallbackInfo ci) {
		LightingProvider self = (LightingProvider) (Object) this;
		if (ClientLightDispatcher.shouldDeflect(self)) {
			ClientLightDispatcher.enqueue(() -> self.enqueueSectionData(lightType, pos, data));
			ci.cancel();
		}
	}

	@Inject(method = "setRetainData", at = @At("HEAD"), cancellable = true, require = 0)
	private void moneyakshaders$asyncSetRetainData(ChunkPos pos, boolean retain, CallbackInfo ci) {
		LightingProvider self = (LightingProvider) (Object) this;
		if (ClientLightDispatcher.shouldDeflect(self)) {
			ClientLightDispatcher.enqueue(() -> self.setRetainData(pos, retain));
			ci.cancel();
		}
	}

	@Inject(method = "doLightUpdates", at = @At("HEAD"), cancellable = true, require = 0)
	private void moneyakshaders$asyncDoLightUpdates(CallbackInfoReturnable<Integer> cir) {
		LightingProvider self = (LightingProvider) (Object) this;
		if (ClientLightDispatcher.shouldDeflect(self)) {
			// The light thread polls continuously; the render thread has
			// nothing to do here anymore.
			cir.setReturnValue(0);
		}
	}
}
