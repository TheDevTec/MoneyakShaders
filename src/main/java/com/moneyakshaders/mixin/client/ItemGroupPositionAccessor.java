
package com.moneyakshaders.mixin.client;

import net.minecraft.item.ItemGroup;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Same two mutable pagination fields Fabric itself updates for modded creative tabs. */
@Mixin(ItemGroup.class)
public interface ItemGroupPositionAccessor {
	@Accessor("row")
	@Mutable
	@Final
	void moneyakshaders$setRow(ItemGroup.Row row);

	@Accessor("column")
	@Mutable
	@Final
	void moneyakshaders$setColumn(int column);
}
