package com.moneyakshaders.client.etf;

import java.util.UUID;

import net.minecraft.util.Identifier;

/** Per-entity inputs the OptiFine random-entity rules can match on (extracted at render-state fill). */
public record EtfContext(UUID uuid, String customName, Identifier biome, float healthPct, boolean baby) {
}
