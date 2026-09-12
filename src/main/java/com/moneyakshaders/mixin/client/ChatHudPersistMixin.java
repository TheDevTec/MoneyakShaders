package com.moneyakshaders.mixin.client;

import java.util.List;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.network.message.MessageSignatureData;
import net.minecraft.text.Text;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.moneyakshaders.MoneyakShadersConfig;
import com.moneyakshaders.client.PersistedChatHistory;

/**
 * Chat history persistence: raise the trim cap and load/save through {@link PersistedChatHistory}.
 *
 * <p>Vanilla caps {@code ChatHud.messages} at 100 and wipes them on disconnect. This mixin raises
 * the trim cap to the configured chat history size, loads the persisted scrollback when the ChatHud
 * is constructed at game start (so a relog still shows the prior conversation), keeps it on
 * disconnect/server-change (the ChatHud instance is reused — we just refuse the {@code clear}), and
 * appends every newly received message to the on-disk store.
 */
@Mixin(ChatHud.class)
public abstract class ChatHudPersistMixin {
	@Shadow
	@org.spongepowered.asm.mixin.Final
	private List<ChatHudLine> messages;

	/** Rebuild the visible scrollback from {@code messages} (private in vanilla). */
	@Invoker("refresh")
	abstract void moneyakshaders$refresh();

	/** Bump the {@code messages.size() > 100} trim limit in {@code addMessage(ChatHudLine)}. */
	@ModifyConstant(method = "addMessage(Lnet/minecraft/client/gui/hud/ChatHudLine;)V", constant = @Constant(intValue = 100), require = 0)
	private int moneyakshaders$bumpMessagesCap(int original) {
		return Math.max(100, MoneyakShadersConfig.get().chatHistorySize);
	}

	/** Load persisted history into the freshly-built ChatHud (once, at game start). */
	@Inject(method = "<init>", at = @At("TAIL"), require = 0)
	private void moneyakshaders$loadPersisted(MinecraftClient client, CallbackInfo ci) {
		try {
			List<Text> persisted = PersistedChatHistory.snapshot();
			if (persisted.isEmpty()) {
				return;
			}
			// messages is NEWEST-FIRST (live code uses addFirst). Disk order is oldest→newest, so add
			// from newest down → index 0 ends up newest, matching the deque convention.
			for (int i = persisted.size() - 1; i >= 0; i--) {
				this.messages.add(new ChatHudLine(0, persisted.get(i), null, MessageIndicator.system()));
			}
			moneyakshaders$refresh(); // populate visibleMessages so the loaded lines actually show
		} catch (Throwable t) {
			com.moneyakshaders.MoneyakShaders.LOGGER.warn("[Optimized Loading] chat history restore failed", t);
		}
	}

	/** Mirror every newly added (live) message to the on-disk persistent store. */
	@Inject(method = "addMessage(Lnet/minecraft/text/Text;Lnet/minecraft/network/message/MessageSignatureData;Lnet/minecraft/client/gui/hud/MessageIndicator;)V",
			at = @At("HEAD"), require = 0)
	private void moneyakshaders$persistAdd(Text message, @Nullable MessageSignatureData signature,
			@Nullable MessageIndicator indicator, CallbackInfo ci) {
		PersistedChatHistory.append(message); // full component → colours survive the relog
	}

	/** Don't drop persisted history when the chat is cleared on disconnect — keep "forever". */
	@Inject(method = "clear", at = @At("HEAD"), cancellable = true, require = 0)
	private void moneyakshaders$preserveOnClear(boolean clearHistory, CallbackInfo ci) {
		// keep the visible scrollback even on disconnect; the live messages.clear() in vanilla would
		// wipe the relog history right after we reloaded it. The user explicitly asked for "navěky".
		ci.cancel();
	}
}
