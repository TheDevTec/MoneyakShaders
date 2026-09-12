package com.moneyakshaders.mixin.client;

import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.moneyakshaders.client.FabricCreativePageGuard;

/**
 * Repairs Fabric's creative-page metadata before its own screen-init hook reads it.
 *
 * <p>Fabric assigns these pages during bootstrap, but the captured 1.21.11 client occasionally had
 * a later/runtime item-group instance whose injected page remained {@code -1}. Fabric's screen hook
 * then throws "Item group has no page" on an otherwise normal E press. The high mixin priority makes
 * this HEAD injection run before Fabric's default-priority HEAD injection. The fast path only reads
 * the tiny item-group registry; when every page is valid it changes nothing. If one is missing, the
 * complete deterministic pagination is rebuilt using the same ordering and 10-tabs-per-page layout
 * as Fabric API, so modded groups do not overlap or all collapse onto page zero.
 */
@Mixin(value = CreativeInventoryScreen.class, priority = 2000)
public abstract class CreativeInventoryPageGuardMixin {
	@Inject(method = "init", at = @At("HEAD"))
	private void moneyakshaders$ensureFabricPages(CallbackInfo ci) {
		FabricCreativePageGuard.ensurePages();
	}
}
