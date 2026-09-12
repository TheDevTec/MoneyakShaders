package com.moneyakshaders.mixin.client;

import net.minecraft.client.util.CommandHistoryManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import com.moneyakshaders.MoneyakShadersConfig;

/**
 * Bump the on-disk command-history cap from vanilla's 50 to {@link MoneyakShadersConfig#sentHistorySize}
 * (default 64). {@code command_history.txt} already persists across restarts; this just enlarges it.
 *
 * <p>The companion {@link ChatHudSentHistoryMixin} bumps the in-memory ring buffer in
 * {@code ChatHud}, and {@link ChatHudPersistAllSentMixin} routes non-command sent messages here too.
 */
@Mixin(CommandHistoryManager.class)
public abstract class CommandHistoryManagerMixin {
	@ModifyConstant(method = "add", constant = @Constant(intValue = 50), require = 0)
	private int moneyakshaders$cap(int original) {
		return Math.max(1, MoneyakShadersConfig.get().sentHistorySize);
	}
}
