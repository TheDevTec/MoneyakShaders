
package com.moneyakshaders.mixin.client;

import net.minecraft.client.render.item.ItemRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read access to the filled item layers for the CIT quad remap. */
@Mixin(ItemRenderState.class)
public interface ItemRenderStateAccessor {
	@Accessor("layers")
	ItemRenderState.LayerRenderState[] moneyakshaders$getLayers();

	@Accessor("layerCount")
	int moneyakshaders$getLayerCount();
}
