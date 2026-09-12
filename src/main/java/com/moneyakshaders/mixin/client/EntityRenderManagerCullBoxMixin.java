package com.moneyakshaders.mixin.client;

import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.entity.EntityRenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.moneyakshaders.MoneyakShadersConfig;

/**
 * Fixes resource-pack custom visuals (item displays, dropped items, item
 * frames, armor stands with big textures - e.g. a spinning "wheel of fortune")
 * disappearing from certain camera angles.
 *
 * <p>Cause: vanilla frustum-culls entities by {@code getBoundingBox().expand(0.5)},
 * but a large custom model is far bigger than that box. When the small box
 * leaves the frustum (a certain angle) the entity is culled even though the
 * visible model is still on screen.
 *
 * <p>Fix: when vanilla would cull one of these custom-visual entities, re-test
 * the frustum against an inflated box (config: largeModelCullMargin). Still a
 * frustum test, so an entity that is genuinely off-screen (behind the camera)
 * is still culled - no behind-camera render cost. Only these decorative types
 * are affected; mobs keep normal culling.
 */
@Mixin(EntityRenderManager.class)
public abstract class EntityRenderManagerCullBoxMixin {
	/**
	 * Occlusion-cull entities whose section the terrain visibility BFS found no sight line to — behind
	 * walls. This is the cheap section-graph version (an O(1) set lookup, no per-entity raycast like the
	 * removed one), aimed at server "mob" decorations built from DisplayEntities that sit behind walls
	 * and are otherwise exempt from distance culling. Opt-in (entityOcclusionCulling); conservative graph
	 * → an entity is kept whenever its section has ANY sight line, so it rarely pops. Never culls the
	 * player, the camera entity or glowing entities. Cancels so the keep-large-models hook can't re-add it.
	 */
	@Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true, require = 0)
	private void moneyakshaders$occlusionCullEntity(Entity entity, Frustum frustum, double cameraX, double cameraY, double cameraZ,
			CallbackInfoReturnable<Boolean> cir) {
		MoneyakShadersConfig config = MoneyakShadersConfig.get();
		if (!config.entityOcclusionCulling) {
			return;
		}
		net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
		if (entity == mc.player || entity == mc.getCameraEntity() || entity.isGlowing()) {
			return;
		}
		// Underwater views can see through a long fluid column and into open ravines that the opaque
		// section-connectivity graph intentionally does not model. Vanilla still performs its own
		// frustum/distance test after this hook; skip only our extra occlusion decision so swimming never
		// makes mobs disappear as the camera crosses a water/section boundary.
		if (mc.gameRenderer != null && mc.gameRenderer.getCamera().getSubmersionType()
				== net.minecraft.block.enums.CameraSubmersionType.WATER) {
			return;
		}
		net.minecraft.util.math.Box box = entity.getBoundingBox().expand(0.5);
		int minSx = net.minecraft.util.math.MathHelper.floor(box.minX) >> 4;
		int maxSx = net.minecraft.util.math.MathHelper.floor(box.maxX) >> 4;
		int minSy = net.minecraft.util.math.MathHelper.floor(box.minY) >> 4;
		int maxSy = net.minecraft.util.math.MathHelper.floor(box.maxY) >> 4;
		int minSz = net.minecraft.util.math.MathHelper.floor(box.minZ) >> 4;
		int maxSz = net.minecraft.util.math.MathHelper.floor(box.maxZ) >> 4;
		// A pathological/custom entity spanning a huge number of sections is safer left to vanilla than
		// expanded into an expensive nested loop on every frame.
		if ((long) (maxSx - minSx + 1) * (maxSy - minSy + 1) * (maxSz - minSz + 1) > 64L) return;
		boolean anyActive = !config.deferUndergroundSections;
		boolean anyVisible = !config.occlusionCulling;
		outer:
		for (int sx = minSx; sx <= maxSx; sx++) {
			for (int sy = minSy; sy <= maxSy; sy++) {
				for (int sz = minSz; sz <= maxSz; sz++) {
					if (!anyActive && com.moneyakshaders.render.ExperimentalSectionRender.isSectionActiveForEntity(sx, sy, sz)) {
						anyActive = true;
					}
					if (!anyVisible && com.moneyakshaders.render.ExperimentalSectionRender.isSectionVisible(sx, sy, sz)) {
						anyVisible = true;
					}
					if (anyActive && anyVisible) break outer;
				}
			}
		}
		if (!anyActive || !anyVisible) cir.setReturnValue(false);
	}

	@Inject(method = "shouldRender", at = @At("RETURN"), cancellable = true, require = 0)
	private void moneyakshaders$keepLargeModelsVisible(Entity entity, Frustum frustum, double cameraX, double cameraY, double cameraZ,
			CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ()) {
			return; // already going to render
		}
		MoneyakShadersConfig config = MoneyakShadersConfig.get();
		if (!config.fixLargeModelCulling || config.largeModelCullMargin <= 0) {
			return;
		}
		if (entity instanceof DisplayEntity
				|| entity instanceof ItemEntity
				|| entity instanceof ItemFrameEntity
				|| entity instanceof ArmorStandEntity) {
			if (frustum.isVisible(entity.getBoundingBox().expand(config.largeModelCullMargin))) {
				cir.setReturnValue(true);
			}
		}
	}
}
