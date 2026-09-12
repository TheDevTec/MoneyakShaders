
package com.moneyakshaders.mixin.client;

import net.minecraft.client.font.BakedGlyph;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.Style;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes the normal glyph-cache lookup so it can be warmed in tiny render-thread slices. */
@Mixin(TextRenderer.class)
public interface TextRendererGlyphAccessor {
	@Invoker("getGlyph")
	BakedGlyph moneyakshaders$getGlyph(int codepoint, Style style);
}
