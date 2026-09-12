package com.moneyakshaders.client.etf;

import net.minecraft.util.Identifier;

/** Duck interface added onto EntityRenderState: carries this frame's ETF decision (states are
 *  POOLED per renderer — these fields are overwritten by every fill, never used as a cache). */
public interface OplEtfRenderState {
	Identifier opl$getSwapTexture();

	void opl$setSwapTexture(Identifier id);

	Identifier opl$getEmissiveTexture();

	void opl$setEmissiveTexture(Identifier id);
}
