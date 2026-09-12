package com.moneyakshaders.client;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import com.moneyakshaders.MoneyakShadersConfig;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemDisplayContext;
import net.minecraft.item.ItemStack;

/**
 * Pre-warms item-model resolution for the player's inventory + the open container, a few items per
 * tick, so the FIRST render of each item type doesn't stall the render thread when you scroll the
 * hotbar or open a chest/inventory (the "prepare everything first, then load" the user asked for).
 *
 * <p>The expensive part of {@code ItemModelManager.update} is the first resolution of a given item
 * model (the model getter then caches it); subsequent per-frame rebuilds are cheap. So we resolve
 * each distinct item type ONCE per session into a throwaway {@link ItemRenderState} and remember it,
 * spreading the work over ticks. Render-thread only (the client tick runs there), fully guarded.
 */
public final class ItemPrewarmer {
	private static final int PER_TICK = 6;

	private static final ItemRenderState SCRATCH = new ItemRenderState();
	private static final Set<Item> warmed = new HashSet<>();
	private static final Set<Item> pendingItems = new HashSet<>();
	private static final ArrayDeque<ItemStack> pending = new ArrayDeque<>();

	private ItemPrewarmer() {
	}

	public static void tick(MinecraftClient client) {
		if (client == null || client.player == null || client.world == null
				|| !MoneyakShadersConfig.get().prewarmItemModels) {
			return;
		}
		int budget = PER_TICK;
		while (budget > 0 && !pending.isEmpty()) {
			ItemStack stack = pending.removeFirst();
			pendingItems.remove(stack.getItem());
			budget = warm(client, stack, budget);
		}
		if (budget <= 0) {
			return;
		}
		// Prewarm only the small inventory that is known BEFORE a screen opens. Scanning the open
		// CreativeInventoryScreen handler walked thousands of entries on every tick (and progressively
		// more already-warmed entries before finding the next six). It could neither help the first frame
		// of that already-open screen nor stay bounded, and amplified the inventory hitch it was meant to
		// prevent. Containers/creative pages resolve their visible items through vanilla's own model cache.
		PlayerInventory inv = client.player.getInventory();
		for (int i = 0, n = inv.size(); i < n; i++) {
			budget = warm(client, inv.getStack(i), budget);
			if (budget <= 0) {
				return;
			}
		}
	}

	/** Adds immutable creative display stacks to the bounded per-tick queue without duplicate items. */
	public static void enqueue(Collection<ItemStack> stacks) {
		for (ItemStack stack : stacks) {
			enqueue(stack);
		}
	}

	public static void enqueue(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return;
		Item item = stack.getItem();
		if (warmed.contains(item) || !pendingItems.add(item)) return;
		pending.addLast(stack);
	}

	private static int warm(MinecraftClient client, ItemStack stack, int budget) {
		if (stack == null || stack.isEmpty()) {
			return budget;
		}
		Item item = stack.getItem();
		if (!warmed.add(item)) {
			return budget; // already resolved this type this session
		}
		try {
			client.getItemModelManager().clearAndUpdate(SCRATCH, stack, ItemDisplayContext.GUI, client.world, null, 0);
		} catch (Throwable ignored) {
			// a bad item model must never break the tick
		}
		return budget - 1;
	}
}
