package com.moneyakshaders.client;

import java.util.ArrayList;
import java.util.List;

import com.moneyakshaders.MoneyakShaders;
import com.moneyakshaders.MoneyakShadersConfig;
import com.moneyakshaders.mixin.client.ItemGroupsAccessor;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;

/**
 * Builds creative-tab entries incrementally after the play registry is available.
 *
 * <p>Vanilla normally rebuilds every item group synchronously in the creative screen constructor.
 * On the captured client that was the large first-open hitch.  Calling the whole constructor (or
 * querying Fabric's pages) early is unsafe: Fabric has not necessarily assigned every group a page
 * yet, which caused the previous random "Item group has no page" crash.  This path never creates a
 * screen and never asks for a page.  It updates one already-registered group per client tick, then
 * atomically publishes the completed display context and search inputs.  If the player opens the
 * screen before completion, vanilla wins and this prewarmer abandons its private partial context.
 */
public final class CreativeInventoryPrewarmer {
	private static ClientWorld world;
	private static ItemGroup.DisplayContext context;
	private static List<ItemGroup> groups = List.of();
	private static int nextGroup;
	private static int joinTicks;
	private static boolean finished;

	private CreativeInventoryPrewarmer() {
	}

	public static void tick(MinecraftClient client) {
		if (client == null || client.world == null || client.player == null || client.player.networkHandler == null) {
			reset(null);
			return;
		}
		if (client.world != world) {
			reset(client.world);
		}
		if (finished || !MoneyakShadersConfig.get().prewarmItemModels || !client.player.isInCreativeMode()
				|| client.currentScreen != null) {
			return;
		}

		boolean showOperator = client.player.isCreativeLevelTwoOp()
				&& client.options.getOperatorItemsTab().getValue();
		ItemGroup.DisplayContext published = ItemGroupsAccessor.moneyakshaders$getDisplayContext();
		if (published != null && !published.doesNotMatch(
				client.player.networkHandler.getEnabledFeatures(), showOperator,
				client.player.getEntityWorld().getRegistryManager())) {
			finished = true; // the screen (or another safe path) completed first
			return;
		}

		// By this point a creative screen could already be opened safely. Two play ticks additionally
		// keep the work out of the join packet's hottest frame without turning it into a visible delay.
		if (++joinTicks < 2) {
			return;
		}

		try {
			if (context == null || context.doesNotMatch(
					client.player.networkHandler.getEnabledFeatures(), showOperator,
					client.player.getEntityWorld().getRegistryManager())) {
				prepare(client, showOperator);
			}
			if (nextGroup < groups.size()) {
				groups.get(nextGroup++).updateEntries(context); // exactly one tab this tick
				return;
			}

			// updateEntries normally runs through ItemGroups' monolithic method, whose Fabric tail hook
			// assigns creative pages. We deliberately split that method, so publish the missing metadata
			// ourselves before exposing the matching context. The screen mixin remains a last-resort guard
			// for item groups registered unusually late by another mod.
			FabricCreativePageGuard.ensurePages();
			for (ItemGroup group : ItemGroups.getGroupsToDisplay()) {
				ItemPrewarmer.enqueue(group.getIcon());
			}
			ItemPrewarmer.enqueue(ItemGroups.getDefaultTab().getDisplayStacks());

			// Mirror CreativeInventoryScreen.populateDisplay before publishing the matching context.  A
			// later screen constructor now observes the same registry object and skips the monolithic rebuild.
			List<ItemStack> searchStacks = List.copyOf(ItemGroups.getSearchGroup().getDisplayStacks());
			client.player.networkHandler.getSearchManager().addItemTooltipReloader(context.lookup(), searchStacks);
			client.player.networkHandler.getSearchManager().addItemTagReloader(searchStacks);
			ItemGroupsAccessor.moneyakshaders$setDisplayContext(context);
			finished = true;
		} catch (Throwable t) {
			// Do not publish a partial context. Vanilla remains the correctness fallback when the user
			// opens the screen, and one incompatible modded group must never crash the play tick.
			finished = true;
			MoneyakShaders.LOGGER.warn("[Optimized Loading] Creative inventory prewarm skipped; vanilla will initialize it", t);
		}
	}

	private static void prepare(MinecraftClient client, boolean showOperator) {
		context = new ItemGroup.DisplayContext(
				client.player.networkHandler.getEnabledFeatures(), showOperator,
				client.player.getEntityWorld().getRegistryManager());
		ArrayList<ItemGroup> ordered = new ArrayList<>();
		for (ItemGroup group : ItemGroups.getGroups()) {
			if (group.getType() == ItemGroup.Type.CATEGORY) ordered.add(group);
		}
		for (ItemGroup group : ItemGroups.getGroups()) {
			if (group.getType() != ItemGroup.Type.CATEGORY) ordered.add(group);
		}
		groups = ordered;
		nextGroup = 0;
	}

	private static void reset(ClientWorld nextWorld) {
		world = nextWorld;
		context = null;
		groups = List.of();
		nextGroup = 0;
		joinTicks = 0;
		finished = false;
	}
}
