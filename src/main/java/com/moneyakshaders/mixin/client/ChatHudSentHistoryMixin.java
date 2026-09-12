package com.moneyakshaders.mixin.client;

import net.minecraft.client.gui.hud.ChatHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import com.moneyakshaders.MoneyakShadersConfig;

/**
 * Bumps the sent-message ring-buffer cap in {@code ChatHud.addToMessageHistory} from vanilla's 100
 * down/up to {@link MoneyakShadersConfig#sentHistorySize} (default 64).
 *
 * <p>{@code CommandHistoryManager.add} is also bumped (see CommandHistoryManagerMixin); together
 * these control the up-arrow recall buffer that survives game restarts.
 */
@Mixin(ChatHud.class)
public abstract class ChatHudSentHistoryMixin {
	@ModifyConstant(method = "addToMessageHistory", constant = @Constant(intValue = 100), require = 0)
	private int moneyakshaders$sentCap(int original) {
		return Math.max(1, MoneyakShadersConfig.get().sentHistorySize);
	}
}
