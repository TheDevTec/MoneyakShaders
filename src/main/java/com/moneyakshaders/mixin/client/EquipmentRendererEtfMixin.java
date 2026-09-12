package com.moneyakshaders.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.moneyakshaders.client.etf.EtfEngine;

import net.minecraft.client.model.Model;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.command.ModelCommandRenderer;
import net.minecraft.client.render.command.RenderCommandQueue;
import net.minecraft.client.render.entity.equipment.EquipmentRenderer;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * ETF armor support (plan phase E4.1): emissive armor textures. The armor layer's texture id is
 * captured as it's turned into a render layer; right after the base armor submit, an {@code _e}
 * companion (if the pack ships one) is submitted again fullbright on the eyes layer — glowing
 * armor accents, exactly like entity emissives. Armor trims keep rendering untouched.
 */
@Mixin(EquipmentRenderer.class)
public abstract class EquipmentRendererEtfMixin {
	private static final ThreadLocal<Identifier> moneyakshaders$lastArmorTexture = new ThreadLocal<>();

	@WrapOperation(method = "render(Lnet/minecraft/client/render/entity/equipment/EquipmentModel$LayerType;Lnet/minecraft/registry/RegistryKey;Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;ILnet/minecraft/util/Identifier;II)V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/render/RenderLayers;armorCutoutNoCull(Lnet/minecraft/util/Identifier;)Lnet/minecraft/client/render/RenderLayer;"),
			require = 0)
	private RenderLayer moneyakshaders$captureArmorTexture(Identifier texture, Operation<RenderLayer> original,
			@com.llamalad7.mixinextras.sugar.Local(argsOnly = true) net.minecraft.item.ItemStack stack) {
		// CIT armor/elytra (type=armor / type=elytra): replace the worn layer texture per stack rules.
		Identifier cit = com.moneyakshaders.client.etf.CitEngine.equipmentOverride(stack);
		Identifier fin = cit != null ? cit : texture;
		// Keep every layer of worn equipment on one stable texture identity. Vanilla submits the base,
		// optional glint and armor trim as separate ordered commands; only downsampling the base made it
		// switch identity at the distance thresholds while the trim stayed unchanged. It also made the
		// emissive lookup below query an opaque generated LOD id, so the _e pass could blink on/off. GPU
		// mipmapping still handles ordinary distance filtering without changing the resource identity.
		moneyakshaders$lastArmorTexture.set(fin);
		return original.call(fin);
	}

	@WrapOperation(method = "render(Lnet/minecraft/client/render/entity/equipment/EquipmentModel$LayerType;Lnet/minecraft/registry/RegistryKey;Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;ILnet/minecraft/util/Identifier;II)V",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/render/command/RenderCommandQueue;submitModel(Lnet/minecraft/client/model/Model;Ljava/lang/Object;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/RenderLayer;IIILnet/minecraft/client/texture/Sprite;ILnet/minecraft/client/render/command/ModelCommandRenderer$CrumblingOverlayCommand;)V",
					ordinal = 0),
			require = 0)
	@SuppressWarnings({"unchecked", "rawtypes"})
	private void moneyakshaders$submitArmorEmissive(RenderCommandQueue queue, Model model, Object state,
			MatrixStack matrices, RenderLayer layer, int light, int overlay, int color, Sprite sprite,
			int outlineColor, ModelCommandRenderer.CrumblingOverlayCommand crumbling, Operation<Void> original) {
		original.call(queue, model, state, matrices, layer, light, overlay, color, sprite, outlineColor, crumbling);
		Identifier tex = moneyakshaders$lastArmorTexture.get();
		if (tex != null) {
			moneyakshaders$lastArmorTexture.remove();
			Identifier emissive = EtfEngine.emissiveFor(tex);
			if (emissive != null) {
				queue.submitModel(model, state, matrices, RenderLayers.eyes(emissive),
						LightmapTextureManager.MAX_LIGHT_COORDINATE, OverlayTexture.DEFAULT_UV,
						-1, null, outlineColor, null);
			}
		}
	}
}
