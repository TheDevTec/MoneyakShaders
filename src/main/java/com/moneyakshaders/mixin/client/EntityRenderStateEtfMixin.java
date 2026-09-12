package com.moneyakshaders.mixin.client;

import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import com.moneyakshaders.client.etf.OplEtfRenderState;

/** Carries the per-frame ETF texture decision on the (pooled) render state. */
@Mixin(EntityRenderState.class)
public abstract class EntityRenderStateEtfMixin implements OplEtfRenderState {
	@Unique
	private Identifier moneyakshaders$swapTexture;
	@Unique
	private Identifier moneyakshaders$emissiveTexture;

	@Override
	public Identifier opl$getSwapTexture() {
		return moneyakshaders$swapTexture;
	}

	@Override
	public void opl$setSwapTexture(Identifier id) {
		moneyakshaders$swapTexture = id;
	}

	@Override
	public Identifier opl$getEmissiveTexture() {
		return moneyakshaders$emissiveTexture;
	}

	@Override
	public void opl$setEmissiveTexture(Identifier id) {
		moneyakshaders$emissiveTexture = id;
	}
}
