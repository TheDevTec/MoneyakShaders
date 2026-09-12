
package com.moneyakshaders.mixin.client;

import net.minecraft.client.gui.screen.option.GameOptionsScreen;
import net.minecraft.client.gui.widget.OptionListWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@code GameOptionsScreen.body} (the option list widget). The field is declared on
 * GameOptionsScreen, so a {@code @Shadow} from a subclass-targeting mixin (VideoOptionsScreen)
 * can't locate it — an accessor on the declaring class is the reliable way to reach it.
 */
@Mixin(GameOptionsScreen.class)
public interface GameOptionsScreenAccessor {
	@Accessor("body")
	OptionListWidget moneyakshaders$getBody();
}
