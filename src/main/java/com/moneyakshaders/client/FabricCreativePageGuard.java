package com.moneyakshaders.client;

import java.util.Comparator;
import java.util.List;

import com.moneyakshaders.MoneyakShaders;
import com.moneyakshaders.mixin.client.ItemGroupPositionAccessor;

import net.fabricmc.fabric.impl.itemgroup.FabricItemGroupImpl;
import net.minecraft.item.ItemGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;

/** Restores the pagination metadata normally published by Fabric after a monolithic tab rebuild. */
public final class FabricCreativePageGuard {
	private static boolean reportedRepair;

	private FabricCreativePageGuard() {
	}

	/**
	 * Returns immediately when every registered group already has a page. If our incremental creative
	 * prewarm bypassed Fabric's tail injection, rebuilds the same deterministic 10-tabs-per-page layout.
	 */
	public static boolean ensurePages() {
		boolean missing = false;
		for (ItemGroup group : Registries.ITEM_GROUP) {
			try {
				((FabricItemGroupImpl) group).fabric_getPage();
			} catch (IllegalStateException ignored) {
				missing = true;
				break;
			}
		}
		if (!missing) {
			return false;
		}

		Comparator<RegistryEntry.Reference<ItemGroup>> order = (left, right) -> {
			int display = Boolean.compare(left.value().shouldDisplay(), right.value().shouldDisplay());
			if (display != 0) return -display;
			int namespace = left.registryKey().getValue().getNamespace()
					.compareTo(right.registryKey().getValue().getNamespace());
			return namespace != 0 ? namespace
					: left.registryKey().getValue().getPath().compareTo(right.registryKey().getValue().getPath());
		};
		List<RegistryEntry.Reference<ItemGroup>> sorted = Registries.ITEM_GROUP.streamEntries().sorted(order).toList();
		int custom = 0;
		for (RegistryEntry.Reference<ItemGroup> entry : sorted) {
			ItemGroup group = entry.value();
			FabricItemGroupImpl fabric = (FabricItemGroupImpl) group;
			if ("minecraft".equals(entry.registryKey().getValue().getNamespace())) {
				fabric.fabric_setPage(0);
				continue;
			}
			int pageIndex = custom % FabricItemGroupImpl.TABS_PER_PAGE;
			ItemGroup.Row row = pageIndex < FabricItemGroupImpl.TABS_PER_PAGE / 2
					? ItemGroup.Row.TOP : ItemGroup.Row.BOTTOM;
			ItemGroupPositionAccessor position = (ItemGroupPositionAccessor) group;
			position.moneyakshaders$setRow(row);
			position.moneyakshaders$setColumn(pageIndex % (FabricItemGroupImpl.TABS_PER_PAGE / 2));
			fabric.fabric_setPage(custom / FabricItemGroupImpl.TABS_PER_PAGE + 1);
			custom++;
		}
		if (!reportedRepair) {
			reportedRepair = true;
			MoneyakShaders.LOGGER.warn("[Optimized Loading] Restored Fabric creative-tab pages after incremental prewarm");
		}
		return true;
	}
}
