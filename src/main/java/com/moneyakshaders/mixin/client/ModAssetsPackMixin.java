package com.moneyakshaders.mixin.client;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resource.DirectoryResourcePack;
import net.minecraft.resource.LifecycledResourceManagerImpl;
import net.minecraft.resource.ResourcePack;
import net.minecraft.resource.ResourcePackInfo;
import net.minecraft.resource.ResourcePackSource;
import net.minecraft.resource.ResourceType;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import com.moneyakshaders.MoneyakShaders;

/**
 * Without Fabric API there is nothing that exposes a mod's {@code assets/} as a resource pack (the
 * loader only handles code/mixins — pack injection is fabric-resource-loader's job). This mixin is
 * the minimal stand-in: every time a resource manager is built, append a read-only pack backed by
 * the mod's own root path, so the mod's textures (custom particles) resolve. Appended LAST = lowest
 * priority; it only serves the {@code moneyakshaders} namespace so it can't override anything.
 */
@Mixin(LifecycledResourceManagerImpl.class)
public abstract class ModAssetsPackMixin {
	@ModifyVariable(method = "<init>", at = @At("HEAD"), ordinal = 0, argsOnly = true, require = 0)
	private static List<ResourcePack> moneyakshaders$appendModAssets(List<ResourcePack> packs, ResourceType type,
			List<ResourcePack> packsArg) {
		if (type != ResourceType.CLIENT_RESOURCES) {
			return packs;
		}
		// New resource manager = resource reload: previously parsed OptiFine entity rules are stale.
		com.moneyakshaders.client.etf.EtfEngine.clearCaches();
		com.moneyakshaders.client.etf.CitEngine.clearCaches();
		try {
			Path root = FabricLoader.getInstance().getModContainer("moneyakshaders")
					.map(c -> c.getRootPaths().isEmpty() ? null : c.getRootPaths().get(0)).orElse(null);
			if (root == null) {
				return packs;
			}
			ResourcePackInfo info = new ResourcePackInfo("moneyakshaders_assets",
					Text.literal("Optimized Loading built-in assets"), ResourcePackSource.BUILTIN, Optional.empty());
			List<ResourcePack> out = new ArrayList<>(packs);
			out.add(new DirectoryResourcePack(info, root));
			return out;
		} catch (Throwable t) {
			MoneyakShaders.LOGGER.warn("[Optimized Loading] failed to attach mod asset pack", t);
			return packs;
		}
	}
}
