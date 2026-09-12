package com.moneyakshaders.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moneyakshaders.MoneyakShaders;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.ResourceManager;
import net.minecraft.util.Identifier;

/**
 * Optional resource-pack material policy. A file at
 * {@code assets/<namespace>/render_materials/<block-path>.json} overrides only the fields it names;
 * the vanilla-derived {@link MaterialRegistry.Material} remains authoritative for every other field.
 *
 * <p>The immutable map is published only after the whole scan succeeds. Invalid individual files are
 * ignored, which lets a multiplayer server pack retain the previous functional renderer behavior
 * rather than making a cosmetic metadata typo fatal.
 */
final class RenderMaterialOverrides {
	private static final String ROOT = "render_materials";
	private static volatile Map<Identifier, Override> current = Map.of();

	private RenderMaterialOverrides() {
	}

	static void reload() {
		Map<Identifier, Override> next = new HashMap<>();
		try {
			MinecraftClient client = MinecraftClient.getInstance();
			ResourceManager resources = client == null ? null : client.getResourceManager();
			if (resources == null) {
				current = Map.of();
				return;
			}
			resources.findResources(ROOT, id -> id.getPath().endsWith(".json")).forEach((file, resource) -> {
				Identifier target = targetId(file);
				if (target == null) return;
				try (var reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
					JsonElement root = JsonParser.parseReader(reader);
					if (!root.isJsonObject()) throw new IllegalArgumentException("root is not an object");
					next.put(target, Override.parse(root.getAsJsonObject()));
				} catch (Exception badFile) {
					MoneyakShaders.LOGGER.warn("[Optimized Loading] ignored invalid render material metadata {}", file);
				}
			});
		} catch (Throwable failedScan) {
			MoneyakShaders.LOGGER.warn("[Optimized Loading] render material metadata scan failed; using vanilla policy", failedScan);
			next.clear();
		}
		current = Map.copyOf(next);
		if (!next.isEmpty()) {
			MoneyakShaders.LOGGER.info("[Optimized Loading] loaded {} render material override(s)", next.size());
		}
	}

	static MaterialRegistry.Material apply(Identifier blockId, MaterialRegistry.Material base) {
		Override override = current.get(blockId);
		return override == null ? base : override.apply(base);
	}

	private static Identifier targetId(Identifier file) {
		String path = file.getPath();
		String prefix = ROOT + "/";
		if (!path.startsWith(prefix) || !path.endsWith(".json")) return null;
		return Identifier.tryParse(file.getNamespace() + ":" + path.substring(prefix.length(), path.length() - 5));
	}

	private record Override(Boolean castsSun, Boolean castsLocal, Boolean receives, MaterialRegistry.AlphaMode alpha,
			float[] emission, Integer intensity, TransmissionRegistry.Transmission transmission) {
		static Override parse(JsonObject json) {
			Boolean sun = bool(json, "castsSunShadow");
			Boolean local = bool(json, "castsLocalShadow");
			Boolean receives = bool(json, "receivesSunShadow");
			MaterialRegistry.AlphaMode alpha = alpha(json);
			float[] emission = color(json.get("emission"));
			Integer intensity = number(json, "emissionStrength", 0, 15);
			TransmissionRegistry.Transmission transmission = transmission(json);
			return new Override(sun, local, receives, alpha, emission, intensity, transmission);
		}

		MaterialRegistry.Material apply(MaterialRegistry.Material base) {
			MaterialRegistry.AlphaMode nextAlpha = alpha == null ? base.alpha : alpha;
			float[] nextEmission = emission == null ? base.emission : emission;
			int nextIntensity = intensity == null ? base.intensity : intensity;
			TransmissionRegistry.Transmission nextTransmission = transmission == null ? base.transmission : transmission;
			return new MaterialRegistry.Material(nextAlpha,
					castsSun == null ? base.castsSunShadow : castsSun,
					castsLocal == null ? base.castsLocalShadow : castsLocal,
					receives == null ? base.receivesShadow : receives,
					nextEmission, nextIntensity, nextTransmission);
		}

		private static Boolean bool(JsonObject json, String name) {
			JsonElement value = json.get(name);
			return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
					? value.getAsBoolean() : null;
		}

		private static Integer number(JsonObject json, String name, int min, int max) {
			JsonElement value = json.get(name);
			if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return null;
			return Math.max(min, Math.min(max, Math.round(value.getAsFloat())));
		}

		private static float[] color(JsonElement value) {
			if (value == null || !value.isJsonArray()) return null;
			JsonArray a = value.getAsJsonArray();
			if (a.size() != 3) return null;
			try {
				return new float[] { clamp(a.get(0).getAsFloat()), clamp(a.get(1).getAsFloat()), clamp(a.get(2).getAsFloat()) };
			} catch (RuntimeException ignored) {
				return null;
			}
		}

		private static TransmissionRegistry.Transmission transmission(JsonObject json) {
			float[] tint = color(json.get("transmission"));
			Float strength = decimal(json, "transmissionStrength", 0f, 1f);
			if (tint == null && strength == null) return null;
			float s = strength == null ? 1f : strength;
			if (tint == null) tint = new float[] { 1f, 1f, 1f };
			return new TransmissionRegistry.Transmission(tint[0], tint[1], tint[2], s, s <= 0f);
		}

		private static MaterialRegistry.AlphaMode alpha(JsonObject json) {
			JsonElement mode = json.get("shadowMode");
			if (mode == null || !mode.isJsonPrimitive()) return null;
			String normalized = mode.getAsString().trim().toLowerCase(java.util.Locale.ROOT);
			switch (normalized) {
				case "alpha_test", "cutout" -> { return MaterialRegistry.AlphaMode.CUTOUT; }
				case "opaque" -> { return MaterialRegistry.AlphaMode.OPAQUE; }
				case "translucent", "blend" -> { return MaterialRegistry.AlphaMode.TRANSLUCENT; }
				case "emissive" -> { return MaterialRegistry.AlphaMode.EMISSIVE; }
				case "none", "disabled" -> { return MaterialRegistry.AlphaMode.EMPTY; }
			}
			try {
				return MaterialRegistry.AlphaMode.valueOf(normalized.toUpperCase(java.util.Locale.ROOT));
			} catch (IllegalArgumentException ignored) {
				return null;
			}
		}

		private static Float decimal(JsonObject json, String name, float min, float max) {
			JsonElement value = json.get(name);
			if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return null;
			return Math.max(min, Math.min(max, value.getAsFloat()));
		}

		private static float clamp(float value) {
			return Math.max(0f, Math.min(1f, value));
		}
	}
}
