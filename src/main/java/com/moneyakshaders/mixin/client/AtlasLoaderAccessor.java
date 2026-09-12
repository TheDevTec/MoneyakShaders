
package com.moneyakshaders.mixin.client;

import java.util.List;

import net.minecraft.client.texture.atlas.AtlasLoader;
import net.minecraft.client.texture.atlas.AtlasSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Access to the (mutable) merged atlas source list so CIT directory sources can be appended. */
@Mixin(AtlasLoader.class)
public interface AtlasLoaderAccessor {
	@Accessor("sources")
	List<AtlasSource> moneyakshaders$sources();
}
