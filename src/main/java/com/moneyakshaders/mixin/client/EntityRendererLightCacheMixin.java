package com.moneyakshaders.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.moneyakshaders.client.DynamicLightSources;
import com.moneyakshaders.client.EntityLightCache;

import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Caches the per-entity packed light from {@code EntityRenderer.getLight} for
 * a short window (config: entityLightCacheMs). Item-frame walls, head/banner
 * props and mob farms stop hammering the light engine every frame.
 *
 * <p>Uses {@code @WrapMethod} - the whole method is wrapped at a single,
 * reliable point (read cache, else call original + store). This is robust
 * against shader mods (Iris) that transform {@code getLight} and could make a
 * separate {@code @At("RETURN")} / {@code @ModifyReturnValue} store silently
 * fail to apply, leaving the cache permanently empty (0 hits).
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererLightCacheMixin {
	@WrapMethod(method = "getLight")
	private int moneyakshaders$cacheLight(Entity entity, float tickDelta, Operation<Integer> original) {
		int cached = EntityLightCache.get(entity);
		int base;
		if (cached >= 0) {
			base = cached;
		} else {
			base = original.call(entity, tickDelta);
			EntityLightCache.store(entity, base);
		}
		// Dynamic lighting: lift the (cached) base light by nearby dynamic sources, fresh every
		// frame so a held torch / burning mob lights other entities as they move (no black models
		// in the dark). Cheap no-op when the feature is off or no source reaches the entity.
		return DynamicLightSources.boost(entity.getX(), entity.getY() + entity.getHeight() * 0.5, entity.getZ(), base);
	}
}
