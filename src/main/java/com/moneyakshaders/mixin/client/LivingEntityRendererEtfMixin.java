package com.moneyakshaders.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.moneyakshaders.MoneyakShadersConfig;
import com.moneyakshaders.client.etf.EtfContext;
import com.moneyakshaders.client.etf.EtfEngine;
import com.moneyakshaders.client.etf.OplEtfRenderState;

import net.minecraft.client.model.Model;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The ETF-replacement render hooks (plan Part E, phases E2 + E3):
 * <ul>
 *   <li>{@code updateRenderState} TAIL — the only (entity, state) moment: run the OptiFine
 *       random-entity rules and store the chosen variant + emissive overlay on the state.</li>
 *   <li>{@code getRenderLayer}'s internal {@code getTexture(...)} call — swapped to the variant,
 *       so every body-texture branch (translucent/cutout/outline) uses it automatically.</li>
 *   <li>{@code render} after the base {@code submitModel} — a second submit with the emissive
 *       texture on the eyes layer at full brightness (glowing texels, transparent elsewhere).</li>
 * </ul>
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererEtfMixin {
	@Shadow
	@Final
	protected net.minecraft.client.render.entity.model.EntityModel<?> model;

	@Shadow
	public abstract Identifier getTexture(LivingEntityRenderState state);

	@Inject(method = "updateRenderState(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;F)V",
			at = @At("TAIL"), require = 0)
	private void moneyakshaders$decideEtfTextures(LivingEntity entity, LivingEntityRenderState state,
			float tickProgress, CallbackInfo ci) {
		if (!(state instanceof OplEtfRenderState duck)) {
			return;
		}
		duck.opl$setSwapTexture(null);
		duck.opl$setEmissiveTexture(null);
		MoneyakShadersConfig cfg = MoneyakShadersConfig.get();
		if (!cfg.etfRandomTextures && !cfg.etfEmissive) {
			return;
		}
		try {
			Identifier vanilla = this.getTexture(state);
			if (vanilla == null) {
				return;
			}
			Identifier chosen = vanilla;
			if (cfg.etfRandomTextures) {
				Identifier biome = null;
				if (EtfEngine.needsBiome(vanilla)) {
					biome = entity.getEntityWorld().getBiome(entity.getBlockPos())
							.getKey().map(k -> k.getValue()).orElse(null);
				}
				String name = entity.hasCustomName() ? entity.getCustomName().getString() : null;
				float healthPct = entity.getMaxHealth() > 0f
						? entity.getHealth() / entity.getMaxHealth() * 100f : 100f;
				EtfContext ctx = new EtfContext(entity.getUuid(), name, biome, healthPct, entity.isBaby());
				chosen = EtfEngine.pickVariant(vanilla, ctx);
				// First call for a biome-matching texture won't have the rule set yet (needsBiome was
				// false before load) — re-run once with the biome now that the rules are cached.
				if (biome == null && EtfEngine.needsBiome(vanilla)) {
					biome = entity.getEntityWorld().getBiome(entity.getBlockPos())
							.getKey().map(k -> k.getValue()).orElse(null);
					chosen = EtfEngine.pickVariant(vanilla, new EtfContext(
							entity.getUuid(), name, biome, healthPct, entity.isBaby()));
				}
				if (!chosen.equals(vanilla)) {
					duck.opl$setSwapTexture(chosen);
				}
			}
			duck.opl$setEmissiveTexture(EtfEngine.emissiveFor(chosen));
		} catch (Throwable ignored) {
			// texture decisions must never break entity rendering
		}
	}

	/** All three body-layer branches read the texture through this one call — swap it here. */
	@ModifyExpressionValue(method = "getRenderLayer(Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;ZZZ)Lnet/minecraft/client/render/RenderLayer;",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/render/entity/LivingEntityRenderer;getTexture(Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;)Lnet/minecraft/util/Identifier;"),
			require = 0)
	private Identifier moneyakshaders$swapBodyTexture(Identifier original, LivingEntityRenderState state,
			boolean showBody, boolean translucent, boolean showOutline) {
		if (state instanceof OplEtfRenderState duck && duck.opl$getSwapTexture() != null) {
			original = duck.opl$getSwapTexture();
		}
		return com.moneyakshaders.client.EntityTextureLodCache.resolve(original, state.squaredDistanceToCamera);
	}

	@Inject(method = "render(Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/render/state/CameraRenderState;)V",
			at = @At("HEAD"), require = 0)
	private void moneyakshaders$beginTextureLodScope(LivingEntityRenderState state, MatrixStack matrices,
			OrderedRenderCommandQueue queue, CameraRenderState camera, CallbackInfo ci) {
		com.moneyakshaders.client.EntityTextureLodCache.beginEntityScope(state.squaredDistanceToCamera);
	}

	@Inject(method = "render(Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/render/state/CameraRenderState;)V",
			at = @At("TAIL"), require = 0)
	private void moneyakshaders$endTextureLodScope(LivingEntityRenderState state, MatrixStack matrices,
			OrderedRenderCommandQueue queue, CameraRenderState camera, CallbackInfo ci) {
		com.moneyakshaders.client.EntityTextureLodCache.endEntityScope();
	}

	/** Emissive overlay: re-submit the same model with the {@code _e} texture, fullbright. */
	@Inject(method = "render(Lnet/minecraft/client/render/entity/state/LivingEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;Lnet/minecraft/client/render/state/CameraRenderState;)V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/RenderLayer;IIILnet/minecraft/client/texture/Sprite;ILnet/minecraft/client/render/command/ModelCommandRenderer$CrumblingOverlayCommand;)V",
					shift = At.Shift.AFTER),
			require = 0)
	@SuppressWarnings({"unchecked", "rawtypes"})
	private void moneyakshaders$submitEmissive(LivingEntityRenderState state, MatrixStack matrices,
			OrderedRenderCommandQueue queue, CameraRenderState camera, CallbackInfo ci) {
		if (state instanceof OplEtfRenderState duck && duck.opl$getEmissiveTexture() != null) {
			queue.submitModel((Model) this.model, state, matrices,
					RenderLayers.eyes(duck.opl$getEmissiveTexture()),
					LightmapTextureManager.MAX_LIGHT_COORDINATE, OverlayTexture.DEFAULT_UV,
					-1, null, state.outlineColor, null);
		}
	}
}
