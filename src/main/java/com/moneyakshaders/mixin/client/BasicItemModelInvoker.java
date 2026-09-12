

package com.moneyakshaders.mixin.client;

import java.util.List;
import java.util.function.Function;

import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.item.model.BasicItemModel;
import net.minecraft.client.render.item.tint.TintSource;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.ModelSettings;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Package-private ctor + static helper of BasicItemModel, needed by the mixed-atlas split fix. */
@Mixin(BasicItemModel.class)
public interface BasicItemModelInvoker {
	@Invoker("<init>")
	static BasicItemModel moneyakshaders$create(List<TintSource> tints, List<BakedQuad> quads,
			ModelSettings settings, Function<ItemStack, RenderLayer> renderLayerGetter) {
		throw new AssertionError();
	}

	@Invoker("findRenderLayerGetter")
	static Function<ItemStack, RenderLayer> moneyakshaders$findRenderLayerGetter(List<BakedQuad> quads) {
		throw new AssertionError();
	}
}
