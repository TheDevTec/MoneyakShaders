package com.moneyakshaders.mixin.client;

import java.util.Map;
import java.util.SortedSet;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.BlockBreakingInfo;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRenderManager;
import net.minecraft.client.render.block.entity.state.BlockEntityRenderState;
import net.minecraft.client.render.command.ModelCommandRenderer;
import net.minecraft.client.render.state.WorldRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.moneyakshaders.MoneyakShadersConfig;
import com.moneyakshaders.client.DebugStats;

/**
 * Feeds block entities to the renderer STRAIGHT from the loaded chunks' block-entity maps instead of
 * from vanilla's built-section render data. This is what makes "Skip vanilla chunk builds" possible:
 * vanilla section building only mattered to us for BE collection (its terrain draw is already
 * replaced), so once BEs come from here, the whole vanilla mesh pipeline can be cancelled — a large
 * CPU cut during chunk loading (vanilla meshed every section on its worker pool for nothing).
 */
@Mixin(WorldRenderer.class)
public abstract class WorldRendererBlockEntityFeedMixin {
	/**
	 * ClientWorld's global block-entity collection may overlap the per-chunk maps on a given
	 * version/modded world. The custom feed reads both so chunkless BEs are preserved; use identity
	 * semantics to guarantee a BE's render state is extracted at most once per frame.
	 */
	private static final ReferenceOpenHashSet<BlockEntity> moneyakshaders$fedThisFrame = new ReferenceOpenHashSet<>();
	/** Render-thread scratch; allocating a MatrixStack every frame is visible in dense block-entity bases. */
	private static final MatrixStack moneyakshaders$crumblingMatrices = new MatrixStack();
	private static int moneyakshaders$lastBreakingStateCount = -1;

	/**
	 * Transition-only probe for the vanilla crack overlay. It distinguishes missing game state from a
	 * draw/state problem without adding per-frame log traffic while the debug overlay is enabled.
	 */
	@Inject(method = "renderBlockDamage", at = @At("HEAD"), require = 0)
	private void moneyakshaders$traceBlockDamage(MatrixStack matrices,
			VertexConsumerProvider.Immediate consumers, WorldRenderState renderStates, CallbackInfo ci) {
		if (!MoneyakShadersConfig.get().debugStats) return;
		int count = renderStates.breakingBlockRenderStates.size();
		if (count == moneyakshaders$lastBreakingStateCount) return;
		moneyakshaders$lastBreakingStateCount = count;
		com.moneyakshaders.MoneyakShaders.LOGGER.info(
				"[Plan C/cracks] vanilla breaking render states={}", count);
	}

	@Shadow
	private ClientWorld world;
	@Shadow
	@Final
	private BlockEntityRenderManager blockEntityRenderManager;
	@Shadow
	@Final
	private Long2ObjectMap<SortedSet<BlockBreakingInfo>> blockBreakingProgressions;

	@Inject(method = "fillBlockEntityRenderStates", at = @At("HEAD"), cancellable = true, require = 0)
	private void moneyakshaders$feedBlockEntitiesFromChunks(Camera camera, float tickProgress,
			WorldRenderState renderStates, CallbackInfo ci) {
		MoneyakShadersConfig cfg = MoneyakShadersConfig.get();
		if (!cfg.experimentalRenderer || !cfg.skipVanillaChunkBuilds || this.world == null) {
			return; // vanilla path (built-section lists)
		}
		ci.cancel();
		moneyakshaders$fedThisFrame.clear();
		Vec3d cam = camera.getCameraPos();
		MatrixStack matrices = moneyakshaders$crumblingMatrices;
		// Chunk radius that can still contain a renderable BE (distance culling happens per-BE in
		// BlockEntityCuller via the getRenderState mixin; 0 = vanilla's 64-block default).
		int dist = cfg.blockEntityRenderDistance > 0 ? cfg.blockEntityRenderDistance : 64;
		int rc = (dist >> 4) + 1;
		double distSq = (double) dist * dist;
		int ccx = (int) Math.floor(cam.x) >> 4;
		int ccz = (int) Math.floor(cam.z) >> 4;
		for (int cx = ccx - rc; cx <= ccx + rc; cx++) {
			for (int cz = ccz - rc; cz <= ccz + rc; cz++) {
				// The square chunk scan is convenient, but its corners are outside the circular
				// per-BE distance cap. If the nearest point of this entire column is already too
				// far in X/Z, no contained block entity can be rendered; avoid even opening its
				// map or walking its entries. Ignoring Y is conservative.
				double minX = cx << 4, maxX = minX + 16.0;
				double minZ = cz << 4, maxZ = minZ + 16.0;
				double dx = cam.x < minX ? minX - cam.x : (cam.x > maxX ? cam.x - maxX : 0.0);
				double dz = cam.z < minZ ? minZ - cam.z : (cam.z > maxZ ? cam.z - maxZ : 0.0);
				if (dx * dx + dz * dz > distSq) {
					continue;
				}
				if (!(this.world.getChunk(cx, cz, ChunkStatus.FULL, false) instanceof WorldChunk chunk)) {
					continue;
				}
				Map<BlockPos, BlockEntity> bes = chunk.getBlockEntities();
				if (bes.isEmpty()) {
					continue;
				}
				for (BlockEntity be : bes.values()) {
					if (be == null || be.isRemoved()) {
						continue;
					}
					if (!moneyakshaders$fedThisFrame.add(be)) {
						continue;
					}
					// getRenderState is intercepted by BlockEntityRenderManagerMixin, which performs the
					// renderer exemption and culling before extraction. Doing it here as well used to run the
					// whole distance/visibility/frustum test twice for every visible BE.
					if (cfg.debugStats) DebugStats.beFed.incrementAndGet();
					BlockPos pos = be.getPos();
					// Block-breaking crumbling overlay (chest crack animation) — same as vanilla.
					SortedSet<BlockBreakingInfo> breaking = this.blockBreakingProgressions.get(pos.asLong());
					ModelCommandRenderer.CrumblingOverlayCommand crumbling = null;
					if (breaking != null && !breaking.isEmpty()) {
						matrices.push();
						matrices.translate(pos.getX() - cam.x, pos.getY() - cam.y, pos.getZ() - cam.z);
						crumbling = new ModelCommandRenderer.CrumblingOverlayCommand(breaking.last().getStage(), matrices.peek());
						matrices.pop();
					}
					BlockEntityRenderState state = this.blockEntityRenderManager.getRenderState(be, tickProgress, crumbling);
					if (state != null) {
						if (cfg.debugStats) DebugStats.beStates.incrementAndGet();
						renderStates.blockEntityRenderStates.add(state);
					}
				}
			}
		}
		// Global (chunkless) BEs — mirrors the vanilla tail loop, including the remove-on-dead sweep.
		java.util.Iterator<BlockEntity> it = this.world.getBlockEntities().iterator();
		while (it.hasNext()) {
			BlockEntity be = it.next();
			if (be.isRemoved()) {
				it.remove();
			} else {
				if (!moneyakshaders$fedThisFrame.add(be)) {
					continue;
				}
				if (cfg.debugStats) DebugStats.beFed.incrementAndGet();
				BlockEntityRenderState state = this.blockEntityRenderManager.getRenderState(be, tickProgress, null);
				if (state != null) {
					if (cfg.debugStats) DebugStats.beStates.incrementAndGet();
					renderStates.blockEntityRenderStates.add(state);
				}
			}
		}
		moneyakshaders$fedThisFrame.clear();
	}
}
