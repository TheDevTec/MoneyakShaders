package com.moneyakshaders.client.etf;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import com.moneyakshaders.MoneyakShaders;
import com.moneyakshaders.MoneyakShadersConfig;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.item.ItemRenderState;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;

/**
 * OptiFine CIT (Custom Item Textures) support — plan phase E4.2, full version. Reads every
 * {@code optifine/cit/**.properties} (+ {@code mcpatcher/cit/**}) from the enabled packs:
 * <ul>
 *   <li>{@code type=item} — the item's baked quads are UV-remapped onto the CIT sprite (the CIT
 *       textures are stitched into the ITEMS atlas via an injected directory source), so the swap
 *       works everywhere an item renders: GUI, hand, dropped, item frames.</li>
 *   <li>{@code type=armor} / {@code type=elytra} — worn equipment layer textures are replaced with
 *       the pack's texture directly (equipment textures aren't atlas-bound).</li>
 *   <li>{@code type=enchantment} (custom glint) is parsed but NOT applied — logged once.</li>
 * </ul>
 * Conditions: items, nbt.display.Name / components.custom_name (plain, regex:, iregex:, pattern:,
 * ipattern:), enchantments, enchantmentLevels, damage (abs or %), stackSize, weight ordering.
 * Malformed pieces are skipped, never fatal (legacy packs are sloppy).
 */
public final class CitEngine {
	static final class CitRule {
		String type = "item";
		List<Item> items = List.of();
		Identifier textureSprite;   // items-atlas sprite id (no .png) for type=item
		Identifier textureDirect;   // direct texture path (with .png) for armor/elytra
		java.util.regex.Pattern name;
		boolean nameNegated;
		List<Identifier> enchantments;
		float[] enchantLevels;      // min,max or null
		float[] damage;             // min,max (percent if damagePercent) or null
		boolean damagePercent;
		int[] stackSize;            // min,max or null
		int weight;
		Identifier sourceFile;   // the .properties this rule came from (debug logging)

		boolean matches(ItemStack stack) {
			if (name != null) {
				var custom = stack.get(DataComponentTypes.CUSTOM_NAME);
				String n = custom != null ? custom.getString() : null;
				boolean m = n != null && name.matcher(n).matches();
				if (m == nameNegated) {
					return false;
				}
			}
			if (stackSize != null && (stack.getCount() < stackSize[0] || stack.getCount() > stackSize[1])) {
				return false;
			}
			if (damage != null) {
				float d = stack.getDamage();
				if (damagePercent && stack.getMaxDamage() > 0) {
					d = d * 100f / stack.getMaxDamage();
				}
				if (d < damage[0] || d > damage[1]) {
					return false;
				}
			}
			if (enchantments != null || enchantLevels != null) {
				if (!matchesEnchant(stack.get(DataComponentTypes.ENCHANTMENTS))
						&& !matchesEnchant(stack.get(DataComponentTypes.STORED_ENCHANTMENTS))) {
					return false;
				}
			}
			return true;
		}

		private boolean matchesEnchant(ItemEnchantmentsComponent comp) {
			if (comp == null || comp.isEmpty()) {
				return false;
			}
			for (var entry : comp.getEnchantmentEntries()) {
				RegistryEntry<?> ench = entry.getKey();
				Identifier id = ench.getKey().map(k -> k.getValue()).orElse(null);
				boolean idOk = enchantments == null || (id != null && enchantments.contains(id));
				boolean lvlOk = enchantLevels == null
						|| (entry.getIntValue() >= enchantLevels[0] && entry.getIntValue() <= enchantLevels[1]);
				if (idOk && lvlOk) {
					return true;
				}
			}
			return false;
		}
	}

	private static volatile Map<Item, List<CitRule>> itemRules;   // type=item
	private static volatile Map<Item, List<CitRule>> equipRules;  // type=armor/elytra
	private static boolean loggedGlint;

	private CitEngine() {
	}

	public static void clearCaches() {
		itemRules = null;
		equipRules = null;
	}

	/** type=item: swap the filled render state's quads onto the matched CIT sprite. */
	public static void apply(ItemRenderState state, ItemStack stack,
			ItemRenderState.LayerRenderState[] layers, int layerCount) {
		if (!MoneyakShadersConfig.get().citTextures || stack.isEmpty()) {
			return;
		}
		CitRule rule = match(rulesFor(true), stack);
		if (rule == null || rule.textureSprite == null) {
			if (MoneyakShadersConfig.get().debugCit) {
				logMissingModelTextures(stack, layers, layerCount);
			}
			return;
		}
		Sprite sprite = itemsSprite(rule.textureSprite);
		if (sprite == null || sprite.getContents().getId().getPath().startsWith("missingno")) {
			if (MoneyakShadersConfig.get().debugCit && DEBUG_LOGGED.add("citmiss:" + rule.textureSprite)) {
				MoneyakShaders.LOGGER.info(
						"[Optimized Loading/CIT] rule matched {} but sprite {} is NOT in the items atlas (from {})",
						Registries.ITEM.getId(stack.getItem()), rule.textureSprite, rule.sourceFile);
			}
			return;
		}
		for (int i = 0; i < layerCount && i < layers.length; i++) {
			List<BakedQuad> quads = layers[i].getQuads();
			for (int q = 0; q < quads.size(); q++) {
				quads.set(q, remap(quads.get(q), sprite));
			}
		}
	}

	private static final java.util.Set<String> DEBUG_LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/**
	 * debugCit: items rendering with the purple-black "missingno" sprite are usually a PACK problem
	 * (custom item models referencing textures outside the paths the 1.21 items atlas stitches),
	 * not a CIT one — but the log tells us WHICH texture the pack wanted so a source can be added.
	 */
	private static void logMissingModelTextures(ItemStack stack, ItemRenderState.LayerRenderState[] layers, int layerCount) {
		for (int i = 0; i < layerCount && i < layers.length; i++) {
			for (BakedQuad q : layers[i].getQuads()) {
				Sprite s = q.sprite();
				if (s != null && s.getContents().getId().getPath().startsWith("missingno")) {
					String key = "modelmiss:" + Registries.ITEM.getId(stack.getItem());
					if (DEBUG_LOGGED.add(key)) {
						MoneyakShaders.LOGGER.info(
								"[Optimized Loading/CIT-debug] item {} renders with MISSING sprite (layer {}) — its model references a texture that is not stitched into the items atlas (pack issue, not CIT rule)",
								Registries.ITEM.getId(stack.getItem()), i);
					}
					return;
				}
			}
		}
	}

	/** type=armor/elytra: replacement texture for a worn equipment layer, or null. */
	public static Identifier equipmentOverride(ItemStack stack) {
		if (!MoneyakShadersConfig.get().citTextures || stack == null || stack.isEmpty()) {
			return null;
		}
		CitRule rule = match(rulesFor(false), stack);
		return rule != null ? rule.textureDirect : null;
	}

	private static CitRule match(Map<Item, List<CitRule>> rules, ItemStack stack) {
		if (rules == null) {
			return null;
		}
		List<CitRule> list = rules.get(stack.getItem());
		if (list == null) {
			return null;
		}
		for (CitRule r : list) {
			if (r.matches(stack)) {
				return r;
			}
		}
		return null;
	}

	// ---- UV remap ------------------------------------------------------------------------------

	private static BakedQuad remap(BakedQuad q, Sprite target) {
		Sprite old = q.sprite();
		if (old == null || old == target) {
			return q;
		}
		float ou0 = old.getMinU(), ou1 = old.getMaxU(), ov0 = old.getMinV(), ov1 = old.getMaxV();
		float nu0 = target.getMinU(), nu1 = target.getMaxU(), nv0 = target.getMinV(), nv1 = target.getMaxV();
		float du = ou1 - ou0, dv = ov1 - ov0;
		if (du <= 0f || dv <= 0f) {
			return q;
		}
		long[] uv = new long[4];
		for (int i = 0; i < 4; i++) {
			long packed = q.getTexcoords(i);
			float u = Float.intBitsToFloat((int) (packed >>> 32));
			float v = Float.intBitsToFloat((int) (packed & 0xFFFFFFFFL));
			u = nu0 + (u - ou0) / du * (nu1 - nu0);
			v = nv0 + (v - ov0) / dv * (nv1 - nv0);
			uv[i] = ((long) Float.floatToRawIntBits(u) << 32) | (Float.floatToRawIntBits(v) & 0xFFFFFFFFL);
		}
		return new BakedQuad(q.position0(), q.position1(), q.position2(), q.position3(),
				uv[0], uv[1], uv[2], uv[3], q.tintIndex(), q.face(), target, q.shade(), q.lightEmission());
	}

	private static Sprite itemsSprite(Identifier id) {
		if (MinecraftClient.getInstance().getTextureManager()
				.getTexture(SpriteAtlasTexture.ITEMS_ATLAS_TEXTURE) instanceof SpriteAtlasTexture atlas) {
			return atlas.getSprite(id);
		}
		return null;
	}

	// ---- loading -------------------------------------------------------------------------------

	private static Map<Item, List<CitRule>> rulesFor(boolean item) {
		Map<Item, List<CitRule>> r = item ? itemRules : equipRules;
		if (r == null) {
			loadAll();
			r = item ? itemRules : equipRules;
		}
		return r;
	}

	private static synchronized void loadAll() {
		if (itemRules != null) {
			return;
		}
		Map<Item, List<CitRule>> items = new HashMap<>();
		Map<Item, List<CitRule>> equip = new HashMap<>();
		try {
			ResourceManager rm = MinecraftClient.getInstance().getResourceManager();
			if (rm != null) {
				for (String root : new String[] { "optifine/cit", "mcpatcher/cit" }) {
					rm.findResources(root, p -> p.getPath().endsWith(".properties")).forEach((id, res) -> {
						try (InputStream in = res.getInputStream()) {
							Properties props = new Properties();
							props.load(in);
							parseRule(id, props, items, equip);
						} catch (Exception e) {
							MoneyakShaders.LOGGER.warn("[Optimized Loading/CIT] bad properties {}", id);
						}
					});
				}
			}
		} catch (Throwable t) {
			MoneyakShaders.LOGGER.warn("[Optimized Loading/CIT] scan failed", t);
		}
		// higher weight first; ties keep scan order
		java.util.Comparator<CitRule> byWeight = (a, b) -> Integer.compare(b.weight, a.weight);
		items.values().forEach(l -> l.sort(byWeight));
		equip.values().forEach(l -> l.sort(byWeight));
		int n = items.values().stream().mapToInt(List::size).sum()
				+ equip.values().stream().mapToInt(List::size).sum();
		if (n > 0) {
			MoneyakShaders.LOGGER.info("[Optimized Loading/CIT] loaded {} rules ({} items, {} equipment)",
					n, items.size(), equip.size());
		}
		equipRules = equip;
		itemRules = items;
	}

	private static void parseRule(Identifier propsId, Properties props,
			Map<Item, List<CitRule>> items, Map<Item, List<CitRule>> equip) {
		CitRule rule = new CitRule();
		rule.sourceFile = propsId;
		rule.type = props.getProperty("type", "item").trim().toLowerCase(Locale.ROOT);
		if (rule.type.equals("enchantment")) {
			if (!loggedGlint) {
				loggedGlint = true;
				MoneyakShaders.LOGGER.info("[Optimized Loading/CIT] type=enchantment (custom glint) is not supported yet — skipping those rules");
			}
			return;
		}
		// items to match (fallback: matchItems=, or the file name)
		String itemsSpec = firstOf(props, "items", "matchItems");
		List<Item> matched = new ArrayList<>();
		String fileName = fileName(propsId);
		if (itemsSpec == null || itemsSpec.isBlank()) {
			itemsSpec = fileName;
		}
		for (String tok : itemsSpec.trim().split("\\s+")) {
			String idStr = tok.contains(":") ? tok : "minecraft:" + tok;
			Identifier iid = Identifier.tryParse(idStr);
			if (iid != null) {
				Item it = Registries.ITEM.get(iid);
				if (it != net.minecraft.item.Items.AIR) {
					matched.add(it);
				}
			}
		}
		if (matched.isEmpty()) {
			return;
		}
		rule.items = matched;
		// texture: explicit or same-name png next to the properties file
		String tex = firstOf(props, "texture", "tile", "source");
		Identifier resolved = resolveTexture(propsId, tex, fileName);
		if (resolved == null) {
			if (MoneyakShadersConfig.get().debugCit) {
				MoneyakShaders.LOGGER.info(
						"[Optimized Loading/CIT] rule {} DROPPED — texture '{}' not found in any pack", propsId, tex);
			}
			return;
		}
		if (rule.type.equals("item")) {
			String spritePath = resolved.getPath().endsWith(".png")
					? resolved.getPath().substring(0, resolved.getPath().length() - 4)
					: resolved.getPath();
			// atlas sprite ids never carry the textures/ root (textures/item/x.png stitches as item/x)
			if (spritePath.startsWith("textures/")) {
				spritePath = spritePath.substring("textures/".length());
			}
			rule.textureSprite = Identifier.of(resolved.getNamespace(), spritePath);
		} else {
			rule.textureDirect = resolved.getPath().endsWith(".png") ? resolved
					: Identifier.of(resolved.getNamespace(), resolved.getPath() + ".png");
		}
		// conditions
		String name = firstOf(props, "nbt.display.Name", "components.custom_name", "components.minecraft:custom_name", "name");
		if (name != null && !name.isBlank()) {
			String spec = name.trim();
			if (spec.startsWith("!")) {
				rule.nameNegated = true;
				spec = spec.substring(1);
			}
			rule.name = compileNameSpec(spec);
		}
		String ench = props.getProperty("enchantments");
		if (ench != null && !ench.isBlank()) {
			List<Identifier> list = new ArrayList<>();
			for (String tok : ench.trim().split("\\s+")) {
				Identifier eid = Identifier.tryParse(tok.contains(":") ? tok : "minecraft:" + tok);
				if (eid != null) {
					list.add(eid);
				}
			}
			rule.enchantments = list.isEmpty() ? null : list;
		}
		rule.enchantLevels = parseRange(props.getProperty("enchantmentLevels"));
		String dmg = props.getProperty("damage");
		if (dmg != null && !dmg.isBlank()) {
			rule.damagePercent = dmg.contains("%");
			rule.damage = parseRange(dmg);
		}
		float[] ss = parseRange(props.getProperty("stackSize"));
		if (ss != null) {
			rule.stackSize = new int[] { (int) ss[0], (int) ss[1] };
		}
		try {
			rule.weight = Integer.parseInt(props.getProperty("weight", "0").trim());
		} catch (NumberFormatException ignored) {
		}
		Map<Item, List<CitRule>> target = rule.type.equals("item") ? items : equip;
		for (Item it : matched) {
			target.computeIfAbsent(it, k -> new ArrayList<>()).add(rule);
		}
	}

	/** Resolve a texture reference relative to the properties file; null when the png doesn't exist. */
	private static Identifier resolveTexture(Identifier propsId, String tex, String fileName) {
		String dir = propsId.getPath().substring(0, propsId.getPath().lastIndexOf('/') + 1);
		List<String> candidates = new ArrayList<>();
		if (tex == null || tex.isBlank()) {
			candidates.add(dir + fileName + ".png");
		} else {
			String t = tex.trim().replace('\\', '/');
			if (t.startsWith("./")) {
				t = t.substring(2);
			}
			if (!t.endsWith(".png")) {
				t = t + ".png";
			}
			if (t.startsWith("optifine/") || t.startsWith("mcpatcher/") || t.startsWith("textures/")) {
				candidates.add(t);
			} else if (t.startsWith("~/")) {
				candidates.add("optifine/" + t.substring(2));
			} else {
				candidates.add(dir + t);
				candidates.add("optifine/cit/" + t);
			}
		}
		ResourceManager rm = MinecraftClient.getInstance().getResourceManager();
		for (String c : candidates) {
			Identifier id = Identifier.tryParse(propsId.getNamespace() + ":" + c);
			if (id != null && rm != null && rm.getResource(id).isPresent()) {
				return id;
			}
		}
		return null;
	}

	private static String fileName(Identifier propsId) {
		String p = propsId.getPath();
		int slash = p.lastIndexOf('/');
		return p.substring(slash + 1, p.length() - ".properties".length());
	}

	private static String firstOf(Properties p, String... keys) {
		for (String k : keys) {
			String v = p.getProperty(k);
			if (v != null) {
				return v;
			}
		}
		return null;
	}

	private static float[] parseRange(String s) {
		if (s == null || s.isBlank()) {
			return null;
		}
		try {
			s = s.trim().replace("%", "");
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

	private static java.util.regex.Pattern compileNameSpec(String spec) {
		try {
			if (spec.startsWith("regex:")) {
				return java.util.regex.Pattern.compile(spec.substring(6));
			}
			if (spec.startsWith("iregex:")) {
				return java.util.regex.Pattern.compile(spec.substring(7), java.util.regex.Pattern.CASE_INSENSITIVE);
			}
			if (spec.startsWith("pattern:")) {
				return java.util.regex.Pattern.compile(globToRegex(spec.substring(8)));
			}
			if (spec.startsWith("ipattern:")) {
				return java.util.regex.Pattern.compile(globToRegex(spec.substring(9)), java.util.regex.Pattern.CASE_INSENSITIVE);
			}
			return java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(spec));
		} catch (Exception e) {
			return null;
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
