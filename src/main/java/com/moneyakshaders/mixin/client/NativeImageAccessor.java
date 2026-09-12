
package com.moneyakshaders.mixin.client;

import net.minecraft.client.texture.NativeImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Raw pixel pointer of a NativeImage — the VK backend uploads straight from it. */
@Mixin(NativeImage.class)
public interface NativeImageAccessor {
	@Accessor("pointer")
	long moneyakshaders$pointer();
}
