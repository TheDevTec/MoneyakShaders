package com.moneyakshaders.mixin.client;

import net.minecraft.util.HeldItemContext;
import net.minecraft.client.item.ItemModelManager;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.item.ItemDisplayContext;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.moneyakshaders.client.etf.CitEngine;

/**
 * CIT items hook (plan phase E4.2): every item render state fill (GUI, hand, dropped, item frames)
 * passes through here — after vanilla fills the layers, a matching CIT rule remaps the quads onto
 * the rule's sprite (stitched into the items atlas by AtlasLoaderCitMixin).
 */
@Mixin(ItemModelManager.class)
public abstract class ItemModelManagerCitMixin {
	@Inject(method = "update(Lnet/minecraft/client/render/item/ItemRenderState;Lnet/minecraft/item/ItemStack;Lnet/minecraft/item/ItemDisplayContext;Lnet/minecraft/world/World;Lnet/minecraft/util/HeldItemContext;I)V",
			at = @At("TAIL"), require = 0)
	private void moneyakshaders$applyCit(ItemRenderState state, ItemStack stack, ItemDisplayContext ctx,
			World world, HeldItemContext held, int seed, CallbackInfo ci) {
		try {
			if (state instanceof ItemRenderStateAccessor acc) {
				CitEngine.apply(state, stack, acc.moneyakshaders$getLayers(), acc.moneyakshaders$getLayerCount());
			}
		} catch (Throwable ignored) {
			// CIT must never break item rendering
		}
	}
}
