package com.moneyakshaders.mixin.client;

import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemGroups;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Publishes an incrementally prepared creative-tab context without constructing a GUI screen. */
@Mixin(ItemGroups.class)
public interface ItemGroupsAccessor {
	@Accessor("displayContext")
	static ItemGroup.DisplayContext moneyakshaders$getDisplayContext() {
		throw new AssertionError();
	}

	@Accessor("displayContext")
	static void moneyakshaders$setDisplayContext(ItemGroup.DisplayContext context) {
		throw new AssertionError();
	}
}
