package com.moneyakshaders.mixin.client;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.moneyakshaders.MoneyakShadersConfig;
import com.moneyakshaders.client.DebugStats;
import com.moneyakshaders.client.EntityRenderTint;

import net.minecraft.client.model.Model;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.command.BatchingRenderCommandQueue;
import net.minecraft.client.render.command.ModelCommandRenderer;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.BlockStateModel;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemDisplayContext;

/** Applies the current entity light hue to every colour-capable deferred command path. */
@Mixin(BatchingRenderCommandQueue.class)
public abstract class EntityCommandTintMixin {
	@WrapMethod(method = "submitModel")
	private <S> void moneyakshaders$tintModel(Model<? super S> model, S state, MatrixStack matrices,
			RenderLayer layer, int light, int overlay, int color, @Nullable Sprite sprite, int outline,
			ModelCommandRenderer.@Nullable CrumblingOverlayCommand crumbling, Operation<Void> original) {
		// Armor enchantment glint is an additive animated overlay. Multiplying its colour by the
		// section's asynchronously refreshed custom light hue makes only that overlay flash while the
		// underlying armor remains stable. Keep the authored/vanilla glint colour; the base armor command
		// still receives the coloured entity light normally.
		int finalColor = EntityRenderTint.isGlintLayer(layer)
				? color : EntityRenderTint.multiplyCurrent(color);
		original.call(model, state, matrices, layer, light, overlay, finalColor,
				sprite, outline, crumbling);
	}

	@WrapMethod(method = "submitModelPart")
	private void moneyakshaders$tintModelPart(ModelPart part, MatrixStack matrices, RenderLayer layer,
			int light, int overlay, @Nullable Sprite sprite, boolean sheeted, boolean glint, int color,
			ModelCommandRenderer.@Nullable CrumblingOverlayCommand crumbling, int outline,
			Operation<Void> original) {
		original.call(part, matrices, layer, light, overlay, sprite, sheeted, glint,
				glint || EntityRenderTint.isGlintLayer(layer)
						? color : EntityRenderTint.multiplyCurrent(color), crumbling, outline);
	}

	@WrapMethod(method = "submitBlockStateModel")
	private void moneyakshaders$tintBlockStateModel(MatrixStack matrices, RenderLayer layer,
			BlockStateModel model, float red, float green, float blue, int light, int overlay, int outline,
			Operation<Void> original) {
		original.call(matrices, layer, model, red * EntityRenderTint.redScale(),
				green * EntityRenderTint.greenScale(), blue * EntityRenderTint.blueScale(),
				light, overlay, outline);
	}

	@WrapMethod(method = "submitItem")
	private void moneyakshaders$tintItem(MatrixStack matrices, ItemDisplayContext context, int light,
			int overlay, int outline, int[] tints, List<BakedQuad> quads, RenderLayer layer,
			ItemRenderState.Glint glint, Operation<Void> original) {
		original.call(matrices, context, light, overlay, outline, EntityRenderTint.prepareItemTints(tints),
				quads, layer, glint);
	}

	@WrapMethod(method = "submitCustom(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/RenderLayer;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue$Custom;)V")
	private void moneyakshaders$tintCustom(MatrixStack matrices, RenderLayer layer,
			OrderedRenderCommandQueue.Custom custom, Operation<Void> original) {
		original.call(matrices, layer, custom instanceof EntityRenderTint.PreTintedCustom
				? custom : EntityRenderTint.wrapCustom(custom));
	}

	@WrapMethod(method = "submitCustom(Lnet/minecraft/client/render/command/OrderedRenderCommandQueue$LayeredCustom;)V")
	private void moneyakshaders$trackUntintableLayeredCustom(OrderedRenderCommandQueue.LayeredCustom custom,
			Operation<Void> original) {
		original.call(EntityRenderTint.wrapLayeredCustom(custom));
	}
}
