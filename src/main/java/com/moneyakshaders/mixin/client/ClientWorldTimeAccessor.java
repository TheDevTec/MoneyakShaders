package com.moneyakshaders.mixin.client;

import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The server's daylight-cycle flag, not the independently advancing world tick counter. */
@Mixin(ClientWorld.class)
public interface ClientWorldTimeAccessor {
	@Accessor("shouldTickTimeOfDay")
	boolean moneyakshaders$shouldTickTimeOfDay();
}
