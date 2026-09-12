package com.moneyakshaders.mixin.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.moneyakshaders.MoneyakShaders;
import com.moneyakshaders.MoneyakShadersConfig;

import net.minecraft.client.render.item.model.BasicItemModel;
import net.minecraft.client.render.item.model.CompositeItemModel;
import net.minecraft.client.render.item.model.ItemModel;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.BakedSimpleModel;
import net.minecraft.client.render.model.Baker;
import net.minecraft.client.render.model.ModelRotation;
import net.minecraft.client.render.model.ModelSettings;
import net.minecraft.client.render.model.ModelTextures;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Old-pack item rescue (the user's "unknown texture" items): pre-1.21.4 packs declare custom item
 * textures only for the BLOCKS atlas, so since the items-atlas split their item models resolve
 * quads from BOTH atlases and {@code BasicItemModel.findRenderLayerGetter} refuses the mix with
 * "Multiple atlases used in model" → the whole item bakes to missingno. Sprite RESOLUTION is fine
 * (models bake against the merged block_or_item view) — only the single-atlas assert kills it.
 * Fix: catch exactly that failure and re-bake the model with its quads PARTITIONED per atlas, one
 * BasicItemModel per atlas, wrapped in a vanilla CompositeItemModel — each part then renders from
 * its own atlas.
 */
@Mixin(BasicItemModel.Unbaked.class)
public abstract class BasicItemModelMixedAtlasMixin {
	@WrapMethod(method = "bake", require = 0)
	private ItemModel moneyakshaders$splitMixedAtlases(ItemModel.BakeContext context, Operation<ItemModel> original) {
		try {
			return original.call(context);
		} catch (IllegalStateException e) {
			if (!MoneyakShadersConfig.get().oldPackItemAtlasFix
					|| e.getMessage() == null || !e.getMessage().startsWith("Multiple atlases")) {
				throw e;
			}
			BasicItemModel.Unbaked self = (BasicItemModel.Unbaked) (Object) this;
			Baker baker = context.blockModelBaker();
			BakedSimpleModel model = baker.getModel(self.model());
			ModelTextures textures = model.getTextures();
			List<BakedQuad> all = model.bakeGeometry(textures, baker, ModelRotation.IDENTITY).getAllQuads();
			ModelSettings settings = ModelSettings.resolveSettings(baker, model, textures);
			Map<Identifier, List<BakedQuad>> byAtlas = new LinkedHashMap<>();
			for (BakedQuad q : all) {
				byAtlas.computeIfAbsent(q.sprite().getAtlasId(), k -> new ArrayList<>()).add(q);
			}
			List<ItemModel> parts = new ArrayList<>(byAtlas.size());
			for (List<BakedQuad> quads : byAtlas.values()) {
				parts.add(BasicItemModelInvoker.moneyakshaders$create(self.tints(), quads, settings,
						BasicItemModelInvoker.moneyakshaders$findRenderLayerGetter(quads)));
			}
			if (MoneyakShadersConfig.get().debugCit) {
				MoneyakShaders.LOGGER.info(
						"[Optimized Loading/CIT] item model {} mixes {} atlases — split into a composite instead of failing",
						self.model(), byAtlas.size());
			}
			return new CompositeItemModel(parts);
		}
	}
}
