package com.moneyakshaders.client.etf;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Pattern;

import net.minecraft.util.Identifier;

/**
 * Parsed OptiFine random-entity rules for ONE base texture. Variants are texture Identifiers
 * (index 1 = the vanilla texture). Rules are matched in order; the first match picks among its
 * skins (weighted). No rules but multiple variants = classic uniform numbered-variant behaviour.
 * Unknown/malformed pieces are skipped, never fatal — old packs are sloppy (Phase E5.2).
 */
public final class EtfRuleSet {
	/** One {@code skins.N=…} rule with its optional matchers. */
	static final class Rule {
		int[] skins = new int[0];      // 1-based variant indices
		int[] weights = new int[0];    // parallel to skins (empty = uniform)
		List<String> biomes;           // lowercase ids ("minecraft:plains"); null = any
		List<Pattern> names;           // null = any; entries match the custom name
		boolean namesNegated;
		float healthMin = -1f, healthMax = -1f; // percent 0..100; -1 = any
		Boolean baby;                  // null = any

		boolean matches(EtfContext ctx) {
			if (baby != null && ctx.baby() != baby) {
				return false;
			}
			if (healthMin >= 0 && (ctx.healthPct() < healthMin || ctx.healthPct() > healthMax)) {
				return false;
			}
			if (biomes != null) {
				if (ctx.biome() == null || !biomes.contains(ctx.biome().toString())) {
					return false;
				}
			}
			if (names != null) {
				String n = ctx.customName();
				boolean any = false;
				if (n != null) {
					for (Pattern p : names) {
						if (p.matcher(n).matches()) {
							any = true;
							break;
						}
					}
				}
				if (any == namesNegated) {
					return false;
				}
			}
			return true;
		}
	}

	final Identifier[] variants; // [0] unused, [i] = texture for skin index i (null = missing → vanilla)
	final List<Rule> rules;

	EtfRuleSet(Identifier[] variants, List<Rule> rules) {
		this.variants = variants;
		this.rules = rules;
	}

	boolean needsBiome() {
		for (Rule r : rules) {
			if (r.biomes != null) {
				return true;
			}
		}
		return false;
	}

	/** Deterministic per-entity pick. Returns the vanilla id when nothing applies. */
	Identifier pick(Identifier vanilla, EtfContext ctx) {
		long seed = ctx.uuid().getMostSignificantBits() ^ ctx.uuid().getLeastSignificantBits();
		seed ^= (seed >>> 33);
		seed *= 0xFF51AFD7ED558CCDL;
		seed ^= (seed >>> 33);
		if (rules.isEmpty()) {
			int count = variants.length - 1;
			if (count <= 1) {
				return vanilla;
			}
			int idx = (int) Math.floorMod(seed, count) + 1;
			Identifier v = variants[idx];
			return v != null ? v : vanilla;
		}
		for (Rule r : rules) {
			if (!r.matches(ctx) || r.skins.length == 0) {
				continue;
			}
			int idx;
			if (r.weights.length == r.skins.length) {
				int total = 0;
				for (int w : r.weights) {
					total += Math.max(0, w);
				}
				if (total <= 0) {
					idx = r.skins[(int) Math.floorMod(seed, r.skins.length)];
				} else {
					int roll = (int) Math.floorMod(seed, total);
					idx = r.skins[0];
					for (int i = 0; i < r.skins.length; i++) {
						roll -= Math.max(0, r.weights[i]);
						if (roll < 0) {
							idx = r.skins[i];
							break;
						}
					}
				}
			} else {
				idx = r.skins[(int) Math.floorMod(seed, r.skins.length)];
			}
			if (idx == 1) {
				return vanilla;
			}
			if (idx > 1 && idx < variants.length && variants[idx] != null) {
				return variants[idx];
			}
			return vanilla;
		}
		return vanilla; // no rule matched
	}

	// ---- parsing -------------------------------------------------------------------------------

	/** Parse OptiFine properties. {@code variants} must already be resolved by the engine. */
	static EtfRuleSet parse(Properties props, Identifier[] variants) {
		List<Rule> rules = new ArrayList<>();
		for (int n = 1; n <= 256; n++) {
			String skins = firstOf(props, "skins." + n, "textures." + n);
			if (skins == null) {
				if (n > 32 && rules.isEmpty()) {
					break; // sparse guard
				}
				continue;
			}
			Rule r = new Rule();
			r.skins = parseIntList(skins);
			String w = props.getProperty("weights." + n);
			if (w != null) {
				r.weights = parseIntList(w);
			}
			String biomes = props.getProperty("biomes." + n);
			if (biomes != null && !biomes.isBlank()) {
				List<String> list = new ArrayList<>();
				for (String b : biomes.trim().split("\\s+")) {
					String id = b.toLowerCase(Locale.ROOT);
					if (!id.contains(":")) {
						id = "minecraft:" + id;
					}
					list.add(id);
				}
				r.biomes = list;
			}
			String names = firstOf(props, "names." + n, "name." + n);
			if (names != null && !names.isBlank()) {
				String spec = names.trim();
				if (spec.startsWith("!")) {
					r.namesNegated = true;
					spec = spec.substring(1);
				}
				Pattern p = compileNameSpec(spec);
				if (p != null) {
					r.names = List.of(p);
				}
			}
			String health = props.getProperty("health." + n);
			if (health != null && !health.isBlank()) {
				float[] range = parseRangePct(health.trim());
				if (range != null) {
					r.healthMin = range[0];
					r.healthMax = range[1];
				}
			}
			String baby = props.getProperty("baby." + n);
			if (baby != null) {
				r.baby = Boolean.parseBoolean(baby.trim());
			}
			rules.add(r);
		}
		return new EtfRuleSet(variants, rules);
	}

	private static String firstOf(Properties p, String a, String b) {
		String v = p.getProperty(a);
		return v != null ? v : p.getProperty(b);
	}

	/** ints + ranges: "1 2 4-6" → [1,2,4,5,6]. Bad tokens skipped. */
	private static int[] parseIntList(String s) {
		List<Integer> out = new ArrayList<>();
		for (String tok : s.trim().split("\\s+")) {
			try {
				int dash = tok.indexOf('-', 1);
				if (dash > 0) {
					int lo = Integer.parseInt(tok.substring(0, dash));
					int hi = Integer.parseInt(tok.substring(dash + 1));
					for (int i = lo; i <= hi && out.size() < 1024; i++) {
						out.add(i);
					}
				} else {
					out.add(Integer.parseInt(tok));
				}
			} catch (NumberFormatException ignored) {
			}
		}
		int[] arr = new int[out.size()];
		for (int i = 0; i < arr.length; i++) {
			arr[i] = out.get(i);
		}
		return arr;
	}

	/** "0-50", "0-50%", "25" → percent range (plain numbers are treated as percent of max health). */
	private static float[] parseRangePct(String s) {
		try {
			s = s.replace("%", "");
			int dash = s.indexOf('-', 1);
			if (dash > 0) {
				return new float[] { Float.parseFloat(s.substring(0, dash)), Float.parseFloat(s.substring(dash + 1)) };
			}
			float v = Float.parseFloat(s);
			return new float[] { v, v };
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** OptiFine name spec: plain, {@code regex:}, {@code iregex:}, {@code pattern:} (glob), {@code ipattern:}. */
	private static Pattern compileNameSpec(String spec) {
		try {
			if (spec.startsWith("regex:")) {
				return Pattern.compile(spec.substring(6));
			}
			if (spec.startsWith("iregex:")) {
				return Pattern.compile(spec.substring(7), Pattern.CASE_INSENSITIVE);
			}
			if (spec.startsWith("pattern:")) {
				return Pattern.compile(globToRegex(spec.substring(8)));
			}
			if (spec.startsWith("ipattern:")) {
				return Pattern.compile(globToRegex(spec.substring(9)), Pattern.CASE_INSENSITIVE);
			}
			return Pattern.compile(Pattern.quote(spec));
		} catch (Exception e) {
			return null; // malformed → rule matches nothing by name (E5.2: never fatal)
		}
	}

	private static String globToRegex(String glob) {
		StringBuilder sb = new StringBuilder();
		for (char c : glob.toCharArray()) {
			switch (c) {
				case '*' -> sb.append(".*");
				case '?' -> sb.append('.');
				default -> {
					if ("\\.[]{}()+-^$|".indexOf(c) >= 0) {
						sb.append('\\');
					}
					sb.append(c);
				}
			}
		}
		return sb.toString();
	}
}
