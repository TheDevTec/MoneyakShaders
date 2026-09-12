package com.moneyakshaders.mixin.client;

import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.entity.EntityRenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.moneyakshaders.MoneyakShadersConfig;

/**
 * Deterministic per-type render-distance caps for cheap, numerous entities.
 * In farms and dense bases the bulk of the entity count is dropped items, XP
 * orbs and stuck/flying projectiles - each is a draw call, a render-state
 * extraction and (with shaders) an extra shadow-pass render every frame.
 * Capping how far they render cuts that load directly.
 *
 * <p>Runs at HEAD: a far item is culled before vanilla (and before the
 * large-model keep check) even looks at it. Deterministic by distance - no
 * raycasts, no random pop-in. config: itemRenderDistance / xpOrbRenderDistance
 * / projectileRenderDistance (0 = vanilla, no cap).
 */
@Mixin(EntityRenderManager.class)
public abstract class EntityRenderDistanceMixin {
	@Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true, require = 0)
	private void moneyakshaders$capRenderDistance(Entity entity, Frustum frustum, double cameraX, double cameraY, double cameraZ,
			CallbackInfoReturnable<Boolean> cir) {
		MoneyakShadersConfig config = MoneyakShadersConfig.get();
		if (isStaticDecoration(entity)) {
			int staticCap = config.staticEntityRenderDistance;
			if (staticCap > 0 && !isStaticExempt(entity)
					&& squaredDistanceToRenderableBounds(entity, cameraX, cameraY, cameraZ, config) > (double) staticCap * staticCap) {
				if (config.debugStats) com.moneyakshaders.client.DebugStats.entCulled.incrementAndGet();
				cir.setReturnValue(false);
			}
			return;
		}
		int cap;
		if (entity instanceof ItemEntity) {
			cap = config.itemRenderDistance;
		} else if (entity instanceof ExperienceOrbEntity) {
			cap = config.xpOrbRenderDistance;
		} else if (entity instanceof ProjectileEntity) {
			cap = config.projectileRenderDistance;
		} else if (isCullExempt(entity)) {
			return; // nametags / armor stands / displays / frames / paintings / players — always render
		} else {
			cap = config.mobRenderDistance; // general cap for the rest (mobs etc.) — the farm/spawner lever
		}
		if (cap <= 0) {
			return;
		}
		if (entity.squaredDistanceTo(cameraX, cameraY, cameraZ) > (double) cap * cap) {
			if (config.debugStats) com.moneyakshaders.client.DebugStats.entCulled.incrementAndGet();
			cir.setReturnValue(false);
		}
	}

	/**
	 * Entities that must keep rendering regardless of distance: players, anything with a
	 * custom name (server nametags/holograms), and the decorative types servers use to show
	 * custom textures — armor stands, display entities, item frames, paintings.
	 */
	private static boolean isCullExempt(Entity entity) {
		if (entity instanceof net.minecraft.entity.player.PlayerEntity) {
			return true;
		}
		if (entity.hasCustomName()) {
			return true;
		}
		return entity instanceof net.minecraft.entity.decoration.ArmorStandEntity
				|| entity instanceof net.minecraft.entity.decoration.DisplayEntity
				|| entity instanceof net.minecraft.entity.decoration.ItemFrameEntity
				|| entity instanceof net.minecraft.entity.decoration.painting.PaintingEntity;
	}

	private static boolean isStaticDecoration(Entity entity) {
		return entity instanceof net.minecraft.entity.decoration.ArmorStandEntity
				|| entity instanceof net.minecraft.entity.decoration.DisplayEntity
				|| entity instanceof net.minecraft.entity.decoration.ItemFrameEntity
				|| entity instanceof net.minecraft.entity.decoration.painting.PaintingEntity;
	}

	private static boolean isStaticExempt(Entity entity) {
		if (entity.hasCustomName() || entity.isGlowing()) {
			return true;
		}
		net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
		return entity == client.targetedEntity || entity == client.getCameraEntity();
	}

	/**
	 * Static decoration origins are often much smaller than their resource-pack model. Measure the
	 * nearest point of the expanded bounds, not the origin, so the distance sleeper cannot cut a
	 * visually on-screen model at the threshold. This is allocation-free and runs before state build.
	 */
	private static double squaredDistanceToRenderableBounds(Entity entity, double x, double y, double z,
			MoneyakShadersConfig config) {
		double margin = config.fixLargeModelCulling ? Math.max(0, config.largeModelCullMargin) : 0.0;
		net.minecraft.util.math.Box box = entity.getBoundingBox();
		double dx = axisDistance(x, box.minX - margin, box.maxX + margin);
		double dy = axisDistance(y, box.minY - margin, box.maxY + margin);
		double dz = axisDistance(z, box.minZ - margin, box.maxZ + margin);
		return dx * dx + dy * dy + dz * dz;
	}

	private static double axisDistance(double point, double min, double max) {
		return point < min ? min - point : (point > max ? point - max : 0.0);
	}
}
