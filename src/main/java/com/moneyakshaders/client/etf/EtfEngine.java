package com.moneyakshaders.client.etf;

import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import com.moneyakshaders.MoneyakShaders;
import com.moneyakshaders.MoneyakShadersConfig;

import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;

/**
 * Built-in OptiFine entity-texture support (Entity Texture Features replacement), Part E of plan
 * (etf-plan.md). Maps a vanilla entity texture id to a per-entity variant using OptiFine
 * random-entity rules, plus emissive ({@code _e}) overlay lookup. All lookups are cached; caches
 * clear on resource reload. Legacy pack support: {@code optifine/mob/} and {@code mcpatcher/mob/}
 * are checked alongside {@code optifine/random/entity/}, and numbered variants without properties
 * work both next to the vanilla texture and in the random dirs.
 */
public final class EtfEngine {
	private static final Identifier NONE = Identifier.of("moneyakshaders", "none");
	private static final EtfRuleSet EMPTY = new EtfRuleSet(new Identifier[0], java.util.List.of());
	private static final ConcurrentHashMap<Identifier, EtfRuleSet> RULES = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<Identifier, Identifier> EMISSIVE = new ConcurrentHashMap<>();
	private static volatile String emissiveSuffix; // null until loaded; "" = no emissive support in packs

	private EtfEngine() {
	}

	/** Wipe all caches (resource reload / world change). */
	public static void clearCaches() {
		RULES.clear();
		EMISSIVE.clear();
		emissiveSuffix = null;
	}

	/** Variant texture for this entity, or the vanilla id unchanged. Cheap: cached rule set + arithmetic. */
	public static Identifier pickVariant(Identifier vanilla, EtfContext ctx) {
		if (!MoneyakShadersConfig.get().etfRandomTextures) {
			return vanilla;
		}
		EtfRuleSet set = RULES.computeIfAbsent(vanilla, EtfEngine::loadRuleSet);
		if (set == EMPTY) {
			return vanilla;
		}
		return set.pick(vanilla, ctx);
	}

	/** True when the rule set for this texture matches on biome (lets the state fill skip the lookup). */
	public static boolean needsBiome(Identifier vanilla) {
		EtfRuleSet set = RULES.get(vanilla);
		return set != null && set != EMPTY && set.needsBiome();
	}

	/** Emissive overlay texture ({@code <tex>_e.png}) for a texture, or null. Cached. */
	public static Identifier emissiveFor(Identifier texture) {
		if (!MoneyakShadersConfig.get().etfEmissive) {
			return null;
		}
		Identifier cached = EMISSIVE.get(texture);
		if (cached != null) {
			return cached == NONE ? null : cached;
		}
		String suffix = emissiveSuffix();
		Identifier result = NONE;
		if (!suffix.isEmpty() && texture.getPath().endsWith(".png")) {
			String p = texture.getPath();
			Identifier candidate = Identifier.of(texture.getNamespace(),
					p.substring(0, p.length() - 4) + suffix + ".png");
			if (exists(candidate)) {
				result = candidate;
			}
		}
		EMISSIVE.put(texture, result);
		return result == NONE ? null : result;
	}

	/** {@code suffix.emissive} from optifine/emissive.properties (default {@code _e}). */
	private static String emissiveSuffix() {
		String s = emissiveSuffix;
		if (s == null) {
			s = "_e";
			Properties p = readProperties(Identifier.of("minecraft", "optifine/emissive.properties"));
			if (p == null) {
				p = readProperties(Identifier.of("minecraft", "mcpatcher/emissive.properties"));
			}
			if (p != null) {
				s = p.getProperty("suffix.emissive", "_e").trim();
			}
			emissiveSuffix = s;
		}
		return s;
	}

	// ---- rule-set loading ------------------------------------------------------------------------

	/**
	 * Build the rule set for one vanilla texture id ({@code ns:textures/entity/<sub>.png}).
	 * Checks (in order): optifine/random/entity, optifine/mob, mcpatcher/mob — properties first,
	 * then bare numbered variants (in the random dir AND next to the vanilla texture).
	 */
	private static EtfRuleSet loadRuleSet(Identifier vanilla) {
		String path = vanilla.getPath();
		if (!path.startsWith("textures/entity/") || !path.endsWith(".png")) {
			return EMPTY;
		}
		String sub = path.substring("textures/entity/".length(), path.length() - 4); // e.g. "zombie/zombie"
		String[] roots = { "optifine/random/entity/", "optifine/mob/", "mcpatcher/mob/" };
		try {
			for (String root : roots) {
				Identifier propsId = Identifier.of(vanilla.getNamespace(), root + sub + ".properties");
				Properties props = readProperties(propsId);
				if (props != null) {
					Identifier[] variants = resolveVariants(vanilla, root + sub, 256);
					EtfRuleSet set = EtfRuleSet.parse(props, variants);
					MoneyakShaders.LOGGER.info("[Optimized Loading/ETF] {}: {} rules, {} variants ({})",
							vanilla, set.rules.size(), countNonNull(variants), propsId);
					return set;
				}
			}
			// no properties anywhere → numbered fallback (uniform random)
			for (String root : roots) {
				Identifier[] variants = resolveVariants(vanilla, root + sub, 64);
				if (countNonNull(variants) > 1) {
					MoneyakShaders.LOGGER.info("[Optimized Loading/ETF] {}: {} numbered variants (no properties)",
							vanilla, countNonNull(variants));
					return new EtfRuleSet(variants, java.util.List.of());
				}
			}
			// classic in-place numbering next to the vanilla texture (textures/entity/.../zombie2.png)
			Identifier[] inPlace = resolveVariants(vanilla, "textures/entity/" + sub, 64);
			if (countNonNull(inPlace) > 1) {
				return new EtfRuleSet(inPlace, java.util.List.of());
			}
		} catch (Throwable t) {
			MoneyakShaders.LOGGER.warn("[Optimized Loading/ETF] failed to load rules for {}", vanilla, t);
		}
		return EMPTY;
	}

	/** variants[i] = {@code <base><i>.png} if present (variants[1] = the vanilla texture itself). */
	private static Identifier[] resolveVariants(Identifier vanilla, String base, int max) {
		Identifier[] out = new Identifier[max + 1];
		out[1] = vanilla;
		int misses = 0;
		for (int i = 2; i <= max; i++) {
			Identifier cand = Identifier.of(vanilla.getNamespace(), base + i + ".png");
			if (exists(cand)) {
				out[i] = cand;
				misses = 0;
			} else if (++misses >= 8) {
				break; // long gap → stop probing (packs number densely)
			}
		}
		return out;
	}

	private static int countNonNull(Identifier[] arr) {
		int n = 0;
		for (Identifier id : arr) {
			if (id != null) {
				n++;
			}
		}
		return n;
	}

	private static boolean exists(Identifier id) {
		ResourceManager rm = MinecraftClient.getInstance().getResourceManager();
		return rm != null && rm.getResource(id).isPresent();
	}

	private static Properties readProperties(Identifier id) {
		ResourceManager rm = MinecraftClient.getInstance().getResourceManager();
		if (rm == null) {
			return null;
		}
		var res = rm.getResource(id);
		if (res.isEmpty()) {
			return null;
		}
		try (InputStream in = res.get().getInputStream()) {
			Properties p = new Properties();
			p.load(in);
			return p;
		} catch (Exception e) {
			MoneyakShaders.LOGGER.warn("[Optimized Loading/ETF] bad properties {}", id);
			return null;
		}
	}
}
