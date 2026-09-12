
package com.moneyakshaders.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ChatHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Route ALL sent messages (chat and commands alike) to {@code CommandHistoryManager} so they end up
 * persisted in {@code command_history.txt}. Vanilla only persists commands ("/foo"); the user asked
 * for sent chat messages to be remembered across restarts too.
 *
 * <p>The cap on that file is enlarged by {@link CommandHistoryManagerMixin}.
 */
@Mixin(ChatHud.class)
public abstract class ChatHudPersistAllSentMixin {
	@Inject(method = "addToMessageHistory", at = @At("TAIL"), require = 0)
	private void moneyakshaders$persistAllSent(String message, CallbackInfo ci) {
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null || message == null || message.isEmpty()) {
			return;
		}
		// Vanilla already calls CommandHistoryManager.add(...) for messages starting with "/" — skip
		// those here so we don't double-add (CommandHistoryManager guards equal-to-peekLast, so a
		// duplicate is a no-op, but skipping keeps the file write count down).
		if (message.startsWith("/")) {
			return;
		}
		mc.getCommandHistoryManager().add(message);
	}
}
