package com.moneyakshaders.client;

import com.moneyakshaders.MoneyakShadersConfig;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Distance culling for block entities (chests, ender chests, heads, banners,
 * signs, shulker boxes, ...). Block entities farther than
 * blockEntityRenderDistance from the camera are not rendered.
 *
 * <p>Deterministic - no per-frame raycasts, no cache, so no random pop-in.
 * Occlusion raycasting was removed: it cost more render-thread time than it
 * saved (it raycast far more entities than it culled) and produced visible
 * pop-in, including culling things the player could actually see.
 *
 * <p>Render-thread only.
 */
public final class BlockEntityCuller {
	private BlockEntityCuller() {
	}

	/**
	 * Memo of the last section-visibility answer. Block entities arrive grouped by chunk, so
	 * consecutive calls nearly always ask about the same section; one long compare then replaces the
	 * key packing plus hash lookup. Invalidated every frame, because the visibility graph is rebuilt
	 * as the player moves. Render-thread only, like the rest of this class.
	 */
	private static long memoSectionKey = Long.MIN_VALUE;
	private static boolean memoSectionVisible;
	/**
	 * Frame identity. The camera's position vector is a fresh object each frame, so a reference
	 * compare detects a new frame without needing a render hook to reset the memo.
	 */
	private static Vec3d memoCameraPos;

	public static boolean shouldRender(BlockEntity blockEntity) {
		// One config read for the whole call. This runs once per block entity per frame — in a
		// warehouse that is hundreds of thousands of calls per frame, and the old code fetched the
		// config three separate times inside it.
		MoneyakShadersConfig cfg = MoneyakShadersConfig.get();
		BlockPos pos = blockEntity.getPos();
		if (cfg.deferUndergroundSections
				&& !com.moneyakshaders.render.ExperimentalSectionRender.isSectionActiveForEntity(
						pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4)) {
			if (cfg.debugStats) DebugStats.beCulled.incrementAndGet();
			return false;
		}
		int maxDistance = cfg.blockEntityRenderDistance;
		if (maxDistance <= 0) {
			return true;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.gameRenderer == null) {
			return true;
		}
		Camera camera = client.gameRenderer.getCamera();
		if (camera == null || !camera.isReady()) {
			return true;
		}

		Vec3d cameraPos = camera.getCameraPos();
		if (cameraPos != memoCameraPos) {
			memoCameraPos = cameraPos;
			memoSectionKey = Long.MIN_VALUE; // new frame → the visibility graph may have changed
		}
		// The counters are AtomicLong. One CAS per block entity is nothing by itself but is real work
		// at warehouse scale, and it buys nothing unless the debug overlay is actually on.
		boolean stats = cfg.debugStats;
		if (stats) {
			DebugStats.beChecks.incrementAndGet();
		}
		double distSq = pos.getSquaredDistance(cameraPos.x, cameraPos.y, cameraPos.z);
		if (distSq > (double) maxDistance * maxDistance) {
			if (stats) {
				DebugStats.beCulled.incrementAndGet();
			}
			return false;
		}
		// Occlusion: drop block entities (chests, signs, …) in sections the terrain visibility graph
		// found no sight line to — the warehouse-of-chests-behind-walls case. Conservative: the graph
		// returns "visible" for any section it hasn't computed yet, so nothing pops while chunks load.
		if (cfg.occlusionCulling) {
			int scx = pos.getX() >> 4, ssy = pos.getY() >> 4, scz = pos.getZ() >> 4;
			long secKey = net.minecraft.util.math.ChunkSectionPos.asLong(scx, ssy, scz);
			if (secKey != memoSectionKey) {
				memoSectionKey = secKey;
				memoSectionVisible =
						com.moneyakshaders.render.ExperimentalSectionRender.isSectionVisible(scx, ssy, scz);
			}
			if (!memoSectionVisible) {
				if (stats) {
					DebugStats.beCulled.incrementAndGet();
				}
				return false;
			}
		}
		// Frustum cull: BEs behind the camera / outside the FOV never need a render state built. Generous
		// radius (4) so a tall custom model whose centre is just off-screen isn't clipped. Beacon-beam-type
		// BEs (rendersOutsideBoundingBox) are already exempted upstream in BlockEntityRenderManagerMixin.
		if (cfg.blockEntityFrustumCull
				&& !com.moneyakshaders.render.ExperimentalSectionRender.isSphereInFrustum(
						(float) (pos.getX() + 0.5 - cameraPos.x),
						(float) (pos.getY() + 0.5 - cameraPos.y),
						(float) (pos.getZ() + 0.5 - cameraPos.z), 4f)) {
			if (stats) {
				DebugStats.beCulled.incrementAndGet();
			}
			return false;
		}
		return true;
	}
}
