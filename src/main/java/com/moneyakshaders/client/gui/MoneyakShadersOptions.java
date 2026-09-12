package com.moneyakshaders.client.gui;

import java.util.function.Consumer;

import com.moneyakshaders.MoneyakShadersConfig;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.option.VideoOptionsScreen;
import net.minecraft.client.gui.widget.OptionListWidget;
import net.minecraft.client.option.SimpleOption;
import net.minecraft.text.Text;

import org.lwjgl.opengl.EXTTextureFilterAnisotropic;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;

public final class MoneyakShadersOptions {
	private MoneyakShadersOptions() {}

	public static void addTo(OptionListWidget body) {
		MoneyakShadersConfig c = MoneyakShadersConfig.get();
		int cpus = Math.max(1, Runtime.getRuntime().availableProcessors());
		int bgMax = Math.min(255, Math.max(1, cpus - 1));
		int chunkMax = Math.min(255, Math.max(1, cpus - 2));
		int workerMax = Math.min(255, Math.max(1, cpus - 1));
		int lightMax = Math.min(32, Math.max(1, cpus));
		int anisoMax = maxAnisotropy();

		body.addHeader(Text.literal("Global renderer profile"));
		body.addAll(profile(c));

		body.addHeader(Text.literal("§l§bOptimized Loading — Renderer"));
		body.addAll(
				bool("Terrain mipmaps", "Smooth distant terrain textures. Requires vanilla Mipmap Levels > 0. Default: ON", c.terrainMipmaps, v -> c.terrainMipmaps = v),
				slider("Anisotropic", "Terrain anisotropic filtering. 1 = off; maximum is read from the current GPU. Default: 4", 1, anisoMax, Math.min(c.terrainAnisotropy, anisoMax), v -> c.terrainAnisotropy = v),
				slider("Pack texture LOD", "Positive mip bias for distant terrain. 0 = exact, 4 = strongest bandwidth saving. Default: 0", 0, 4, c.terrainTextureLodBias, v -> c.terrainTextureLodBias = v),
				bool("Entity texture LOD", "Create lower-resolution variants only for distant high-resolution entity/armor textures. Default: ON", c.entityTextureDistanceLod, v -> c.entityTextureDistanceLod = v),
				slider("Leaf opacity dist", "Distance where leaves become fully opaque; 0 = always fancy. Default: 64", 0, 256, c.leavesOpaqueDistance, v -> c.leavesOpaqueDistance = v));

		body.addHeader(Text.literal("§l§bShader-like effects"));
		body.addAll(
				bool("Wind sway", "Gentle sway for plants/leaves and compatible hanging models. Default: ON", c.windSway, v -> c.windSway = v),
				bool("Hand sway", "Subtle first-person hand sway. Default: OFF", c.handSway, v -> c.handSway = v),
				bool("Waterfall splash", "Splash particles where falling water hits a surface. Default: ON", c.waterfallParticles, v -> c.waterfallParticles = v),
				bool("Water impact FX", "Splash/bubbles for water impacts and underwater interactions. Default: ON", c.waterSplash, v -> c.waterSplash = v),
				bool("Rain ripples", "Expanding ripple rings where rain hits water. Default: ON", c.rainRipples, v -> c.rainRipples = v),
				bool("Rain mist", "Soft ground mist during rain. Default: ON", c.rainMist, v -> c.rainMist = v),
				bool("Fantasy clouds", "Volumetric procedural clouds replacing vanilla clouds. Default: ON", c.fantasyClouds, v -> c.fantasyClouds = v),
				bool("Frosted text displays", "Keep water/glass/entities visible through custom text_display backgrounds. Default: ON", c.depthSafeTextDisplays, v -> c.depthSafeTextDisplays = v),
				bool("Day/night tint", "Warm direct daylight, cool night ambient and smooth dawn/dusk transitions. Default: ON", c.dayNightTint, v -> c.dayNightTint = v),
				bool("Held light", "Held emissive blocks light nearby terrain. Default: ON", c.dynamicHeldLight, v -> c.dynamicHeldLight = v),
				bool("Glowing ores", "Non-coal ores glow faintly in darkness. Default: ON", c.glowingOres, v -> c.glowingOres = v),
				slider("Light smoothing %", "0 = vanilla-like flat vertex light, 100 = maximum smoothing. Default: 100", 0, 100, c.lightSmoothing, v -> c.lightSmoothing = v),
				bool("Mob dynamic light", "Torches/fire/glow sources on entities illuminate nearby entities. Default: ON", c.dynamicLighting, v -> c.dynamicLighting = v),
				bool("Block lights", "Placed/dropped emissive blocks cast coloured terrain light. Default: ON", c.terrainPointLights, v -> c.terrainPointLights = v));

		body.addHeader(Text.literal("§l§bWater material"));
		body.addAll(
				slider("Water bump %", "Multi-scale procedural wave-normal strength. 100 = normal, Complementary-like default: 125", 0, 250, c.waterBumpiness, v -> c.waterBumpiness = v),
				slider("Water reflection %", "Fresnel/SSR reflection contribution. 0 disables terrain reflection, 100 = default.", 0, 150, c.waterReflection, v -> c.waterReflection = v),
				slider("Water refraction %", "Screen-space refraction strength. 0 = off, 100 = default, 200 = strong.", 0, 200, c.waterRefraction, v -> c.waterRefraction = v),
				slider("Water foam %", "Shore/depth foam strength. 0 = off, 100 = default.", 0, 150, c.waterFoam, v -> c.waterFoam = v),
				slider("Water SSR steps", "Terrain reflection ray-march steps. 0 = sky reflection only; 20 = default; 28 = maximum shader loop.", 0, 28, c.waterSsrSteps, v -> c.waterSsrSteps = v));

		body.addHeader(Text.literal("§l§bPost-processing"));
		body.addAll(
				bool("Post-processing", "ACES tonemap + bloom + vignette + atmospheric shafts. Default: ON", c.postProcessing, v -> c.postProcessing = v),
				slider("Exposure %", "Tonemap exposure. Practical renderer range: 50-180; 100 = neutral. Default: 100", 50, 180, c.postExposure, v -> c.postExposure = v),
				slider("Bloom %", "Bloom strength. 0 = off, 100 = maximum supported mix. Default: 18", 0, 100, c.postBloom, v -> c.postBloom = v),
				slider("Vignette %", "Edge darkening. Values above 60 become visually destructive, so the UI stops there. Default: 10", 0, 60, c.postVignette, v -> c.postVignette = v),
				slider("Post saturation %", "Final post saturation. 100 = neutral. Default: 104", 0, 160, c.postSaturation, v -> c.postSaturation = v),
				slider("Celestial rays %", "World-space sun/moon volumetric scattering. Also controls underwater shafts. 0 = off. Default: 55", 0, 100, c.postGodRays, v -> c.postGodRays = v),
				stepSlider("PostFX debug", "Diagnostic target: Final, shafts buffer, linear depth, shadow visibility or water transmission. Keep Final for normal play.", new int[]{0,1,2,3,4}, new String[]{"Final","Shafts","Depth","Shadow vis","Water mask"}, c.postDebugView, v -> c.postDebugView = v),
				slider("World brightness %", "Terrain pre-tonemap brightness. 100 = neutral. Default: 100", 50, 150, c.worldBrightness, v -> c.worldBrightness = v),
				slider("World contrast %", "Terrain contrast before fog/post. 100 = neutral. Default: 106", 50, 150, c.worldContrast, v -> c.worldContrast = v),
				slider("World saturation %", "Terrain saturation before post. 100 = neutral. Default: 106", 0, 160, c.worldSaturation, v -> c.worldSaturation = v),
				bool("Cel outlines", "Depth-based comic outlines. Default: OFF", c.celOutlines, v -> c.celOutlines = v),
				slider("Outline %", "Outline strength. Default: 35", 0, 100, c.outlineStrength, v -> c.outlineStrength = v),
				bool("FXAA", "Optional post AA. Can soften the image during motion. Default: ON", c.fxaa, v -> c.fxaa = v));

		body.addHeader(Text.literal("§l§bSun / moon shadows"));
		body.addAll(
				bool("Sun/moon shadows", "Directional cast shadows from the sun by day and moon by night. Default: ON", c.sunShadows, v -> c.sunShadows = v),
				stepSlider("Near shadow res", "Close cascade hardware/render cap is 8192. Default: 4K", new int[]{512, 1024, 2048, 4096, 8192}, new String[]{"512", "1K", "2K", "4K", "8K"}, c.shadowNearResolution, v -> c.shadowNearResolution = v),
				stepSlider("Far shadow res", "Far cascade renderer cap is 4096. Default: 2K", new int[]{512, 1024, 2048, 4096}, new String[]{"512", "1K", "2K", "4K"}, c.shadowFarResolution, v -> c.shadowFarResolution = v),
				bool("Adaptive shadows", "Preserves close-shadow texel density by raising the near map up to 8K as Sharp dist grows; FAR/point quality may still scale under GPU load. Default: ON", c.adaptiveShadowQuality, v -> c.adaptiveShadowQuality = v),
				slider("Shadow budget ms", "GPU-time target used by adaptive shadow quality. Renderer minimum is 1 ms. Default: 8", 1, 33, c.shadowGpuBudgetMs, v -> c.shadowGpuBudgetMs = v),
				stepSlider("Min shadow res", "Adaptive FAR floor. FAR itself is capped at 4K, so larger values are meaningless. Default: 1K", new int[]{512, 1024, 2048, 4096}, new String[]{"512", "1K", "2K", "4K"}, c.shadowMinResolution, v -> c.shadowMinResolution = v),
				slider("Shadow dist", "Far directional-shadow range. Renderer clamps this to 3-32 chunks. Default: 12", 3, 32, c.shadowDistanceChunks, v -> c.shadowDistanceChunks = v),
				slider("Near shadow dist", "Requested close-cascade coverage. Renderer keeps it at least 2 chunks and below the far cascade. Default: 6", 2, 16, c.shadowNearChunks, v -> c.shadowNearChunks = v),
				slider("Shadow strength %", "Maximum directional shadow darkening. 0 = off, 100 = full configured darkening. Default: 68", 0, 100, c.shadowStrength, v -> c.shadowStrength = v),
				slider("Sharp dist", "Close-detail radius. Adaptive shadows compensates with a larger near map; after the 8K density ceiling, extra distance falls back to the far cascade instead of pixelating contact shadows. Default: 28", 4, 128, c.shadowSharpDistance, v -> c.shadowSharpDistance = v),
				slider("Far blur", "PCF radius for the far cascade; shader clamps to 1-4. Default: 3", 1, 4, c.shadowFarBlur, v -> c.shadowFarBlur = v),
				slider("Cascade blend %", "Near/far cross-fade. Shader accepts 2-30%. Default: 8", 2, 30, c.shadowCascadeBlend, v -> c.shadowCascadeBlend = v),
				slider("Shadow bias", "Bias units map to cfg/6000 blocks and are clamped to 0.00025-0.012 blocks; useful integer range is 2-72. Default: 12", 2, 72, Math.max(2, c.shadowBias), v -> c.shadowBias = v),
				bool("Cull hidden casters", "Submit only directional casters able to shadow visible receivers. Default: OFF", c.shadowCasterCulling, v -> c.shadowCasterCulling = v),
				bool("Entity shadows", "Model/item directional shadows. Default: ON", c.entityShadows, v -> c.entityShadows = v),
				slider("Entity shadow dist", "Maximum entity shadow distance; 0 = unlimited. Default: 32", 0, 128, c.entityShadowDistance, v -> c.entityShadowDistance = v),
				stepSlider("Point shadow res", "Eight lights share a 32M-pixel 3x2 atlas. 1024+ is always reduced by the renderer, so only real base values are exposed.", new int[]{256, 512}, new String[]{"256", "512"}, c.pointShadowResolution, v -> c.pointShadowResolution = v));

		body.addHeader(Text.literal("§l§bEntity textures (OptiFine format)"));
		body.addAll(
				bool("Random entity textures", "OptiFine random/custom entity textures. Default: ON", c.etfRandomTextures, v -> c.etfRandomTextures = v),
				bool("CIT items", "OptiFine CIT item/armor/elytra textures. Default: ON", c.citTextures, v -> c.citTextures = v),
				bool("Old pack compat", "Allow older/newer resource-pack formats without stripping. Default: ON", c.forceOldPackCompat, v -> c.forceOldPackCompat = v),
				bool("Emissive textures", "OptiFine _e emissive entity textures. Default: ON", c.etfEmissive, v -> c.etfEmissive = v));

		body.addHeader(Text.literal("§l§bEntity rendering"));
		body.addAll(
				slider("Mob distance", "Mob render distance; 0 = vanilla/unlimited policy. Default: 64", 0, 256, c.mobRenderDistance, v -> c.mobRenderDistance = v),
				bool("Animation LOD", "Reuse distant living-model poses between bounded refreshes. Default: ON", c.entityAnimationLod, v -> c.entityAnimationLod = v),
				slider("Full animation dist", "Inside this distance pose animation updates every frame. Default: 24", 8, 64, c.entityAnimationNearDistance, v -> c.entityAnimationNearDistance = v),
				slider("Mid animation dist", "Beyond this distance the far animation-rate tier is used. Default: 48", 16, 128, c.entityAnimationMidDistance, v -> c.entityAnimationMidDistance = v),
				slider("Mid animation FPS", "Maximum middle-tier pose refresh rate. Default: 30", 5, 60, c.entityAnimationMidFps, v -> c.entityAnimationMidFps = v),
				slider("Far animation FPS", "Maximum far-tier pose refresh rate. Default: 15", 2, 30, c.entityAnimationFarFps, v -> c.entityAnimationFarFps = v),
				slider("Static entity sleep", "Distance cap for armor stands/displays/frames/paintings; 0 = off. Default: 96", 0, 256, c.staticEntityRenderDistance, v -> c.staticEntityRenderDistance = v),
				slider("Item distance", "Dropped-item render distance. Default: 24", 0, 128, c.itemRenderDistance, v -> c.itemRenderDistance = v),
				slider("XP orb distance", "XP-orb render distance. Default: 16", 0, 128, c.xpOrbRenderDistance, v -> c.xpOrbRenderDistance = v),
				slider("Projectile dist", "Projectile render distance. Default: 32", 0, 128, c.projectileRenderDistance, v -> c.projectileRenderDistance = v),
				slider("Block-entity dist", "Block-entity render distance. Default: 32", 0, 128, c.blockEntityRenderDistance, v -> c.blockEntityRenderDistance = v),
				bool("Occlusion culling", "Section visibility graph for terrain + supported block entities. Default: ON", c.occlusionCulling, v -> c.occlusionCulling = v),
				bool("Skip vanilla builds", "Cancel vanilla terrain meshing while this renderer owns terrain. Default: ON", c.skipVanillaChunkBuilds, v -> setSkipVanillaBuilds(c, v)),
				bool("Sort front-to-back", "Opaque terrain near-to-far for early-Z. Default: ON", c.sortOpaqueFrontToBack, v -> c.sortOpaqueFrontToBack = v),
				bool("Occlusion meshing", "Do not build proven-hidden terrain sections until needed. Default: ON", c.occlusionMeshing, v -> c.occlusionMeshing = v),
				bool("Depth pre-pass", "Depth-only pass before expensive opaque shading. Default: ON", c.depthPrePass, v -> c.depthPrePass = v),
				bool("Fix big-model cull", "Expand culling bounds for large resource-pack models. Default: ON", c.fixLargeModelCulling, v -> c.fixLargeModelCulling = v),
				slider("Cull margin", "Extra blocks added to large-model bounds. Default: 4", 0, 16, c.largeModelCullMargin, v -> c.largeModelCullMargin = v),
				bool("No vanilla shadows", "Disable vanilla blob shadows under entities. Default: ON", c.disableEntityShadows, v -> c.disableEntityShadows = v));

		body.addHeader(Text.literal("§l§bThreads & chunks"));
		body.addAll(
				slider("BG threads", "0 = auto. Manual maximum follows available CPU count; effective code still reserves render/mesh capacity. Default: 0", 0, bgMax, Math.min(c.backgroundThreads, bgMax), v -> c.backgroundThreads = v),
				slider("Chunk threads", "0 = auto. Renderer hard ceiling is CPU cores - 2. Default: 0", 0, chunkMax, Math.min(c.chunkBuilderThreads, chunkMax), v -> c.chunkBuilderThreads = v),
				slider("Worker threads", "0 = auto. Manual UI maximum follows available CPU count. Default: 0", 0, workerMax, Math.min(c.backgroundWorkerThreads, workerMax), v -> c.backgroundWorkerThreads = v),
				bool("Auto-balance threads", "Reserve CPU capacity for render/game/light work. Default: ON", c.autoBalanceBackgroundThreads, v -> c.autoBalanceBackgroundThreads = v),
				bool("Force async rebuild", "Keep section rebuilds off the render thread. Default: ON", c.forceAsyncChunkRebuilds, v -> c.forceAsyncChunkRebuilds = v),
				bool("Pre-warm item models", "Resolve inventory/hotbar models gradually before first render. Default: ON", c.prewarmItemModels, v -> c.prewarmItemModels = v),
				slider("Max uploads/frame", "Emergency count cap; 0 = unlimited count while byte/time budgets still apply. Default: 16", 0, 128, c.maxChunkUploadsPerFrame, v -> c.maxChunkUploadsPerFrame = v));

		body.addHeader(Text.literal("§l§bLight engine"));
		body.addAll(
				bool("Async custom light refresh", "Coalesce light changes and rebuild custom terrain through immutable snapshots/workers. Default: ON", c.asyncLightUpdates, v -> c.asyncLightUpdates = v),
				slider("Light threads", "0 = auto. UI maximum follows available CPU count, capped at 32. Default: 0", 0, lightMax, Math.min(c.lightingEngineThreads, lightMax), v -> c.lightingEngineThreads = v),
				stepSlider("Brightness cache", "Per-thread AO/brightness cache entries. Default: 32K", new int[]{100, 512, 1024, 2048, 4096, 8192, 16384, 32768, 65536}, new String[]{"100", "512", "1K", "2K", "4K", "8K", "16K", "32K", "64K"}, c.brightnessCacheSize, v -> c.brightnessCacheSize = v),
				slider("Entity light ms", "Entity-light cache lifetime. 0 = disabled. Default: 100", 0, 1000, c.entityLightCacheMs, v -> c.entityLightCacheMs = v));

		body.addHeader(Text.literal("§l§bChat & toasts"));
		body.addAll(
				stepSlider("Chat history", "Persisted chat-history limit. Default: 16K", new int[]{500, 1000, 2000, 4000, 8000, 16000, 32000, 64000}, new String[]{"0.5K", "1K", "2K", "4K", "8K", "16K", "32K", "64K"}, c.chatHistorySize, v -> c.chatHistorySize = v),
				slider("Sent history", "Up-arrow command/message history. Default: 64", 16, 256, c.sentHistorySize, v -> c.sentHistorySize = v),
				bool("Toast: tutorial", "Show tutorial toasts. Default: OFF", c.toastTutorial, v -> c.toastTutorial = v),
				bool("Toast: advancement", "Show advancement toasts. Default: ON", c.toastAdvancement, v -> c.toastAdvancement = v),
				bool("Toast: chat insecure", "Show unsigned/insecure chat warning. Default: OFF", c.toastChatInsecure, v -> c.toastChatInsecure = v),
				bool("Toast: resource pack", "Show resource-pack load/copy failure toasts. Default: ON", c.toastResourcePack, v -> c.toastResourcePack = v));

		body.addHeader(Text.literal("§l§bParticles & HUD"));
		body.addAll(
				stepSlider("Max particles", "Hard active-particle cap. Default: 6K", new int[]{500, 1000, 2000, 4000, 6000, 10000, 15000, 20000}, new String[]{"0.5K", "1K", "2K", "4K", "6K", "10K", "15K", "20K"}, c.maxActiveParticles, v -> c.maxActiveParticles = v),
				slider("Particle spawn dist", "Maximum distance new particles spawn; 0 = unlimited. Default: 48", 0, 128, c.particleSpawnDistanceLimit, v -> c.particleSpawnDistanceLimit = v),
				slider("Particle render dist", "Maximum particle render distance; 0 = unlimited. Default: 32", 0, 128, c.particleRenderDistance, v -> c.particleRenderDistance = v),
				bool("FPS overlay", "Show FPS/light renderer overlay. Default: ON", c.fpsHudEnabled, v -> c.fpsHudEnabled = v),
				bool("Debug stats", "Print detailed renderer stats to log. Default: OFF", c.debugStats, v -> c.debugStats = v));
	}

	private static SimpleOption<Integer> profile(MoneyakShadersConfig c) {
		GlobalQualityProfile[] profiles = GlobalQualityProfile.values();
		int current = GlobalQualityProfile.fromConfig(c.globalQualityProfile).ordinal();
		return new SimpleOption<>("Profile", SimpleOption.constantTooltip(Text.literal("Applies a coherent renderer profile. Individual controls remain editable afterwards.")),
				(prefix, index) -> Text.literal("Profile: " + profiles[Math.max(0, Math.min(profiles.length - 1, index))].name()),
				new SimpleOption.ValidatingIntSliderCallbacks(0, profiles.length - 1), current, index -> {
					GlobalQualityProfile selected = profiles[Math.max(0, Math.min(profiles.length - 1, index))];
					GlobalQualityProfile.apply(c, selected);
					MoneyakShadersConfig.save();
					MinecraftClient client = MinecraftClient.getInstance();
					if (client != null) client.execute(() -> {
						if (client.worldRenderer != null) client.worldRenderer.reload();
						// SimpleOption widgets retain their creation-time value. Recreate this one
						// screen so every slider immediately displays the complete profile replacement.
						client.setScreen(new VideoOptionsScreen(null, client, client.options));
					});
				});
	}

	private static SimpleOption<Boolean> bool(String label, String tip, boolean current, Consumer<Boolean> setter) {
		return SimpleOption.ofBoolean(label, SimpleOption.constantTooltip(Text.literal(tip)), current, setter::accept);
	}

	private static void setSkipVanillaBuilds(MoneyakShadersConfig config, boolean enabled) {
		if (config.skipVanillaChunkBuilds == enabled) return;
		config.skipVanillaChunkBuilds = enabled;
		MinecraftClient client = MinecraftClient.getInstance();
		if (client != null && client.worldRenderer != null) client.execute(client.worldRenderer::reload);
	}

	private static SimpleOption<Integer> slider(String label, String tip, int min, int max, int current, Consumer<Integer> setter) {
		int value = Math.max(min, Math.min(max, current));
		return new SimpleOption<>(label, SimpleOption.constantTooltip(Text.literal(tip)), (prefix, v) -> Text.literal(label + ": " + v),
				new SimpleOption.ValidatingIntSliderCallbacks(min, max), value, setter::accept);
	}

	private static SimpleOption<Integer> stepSlider(String label, String tip, int[] values, String[] labels, int current, Consumer<Integer> setter) {
		int initIdx = 0, bestDiff = Math.abs(values[0] - current);
		for (int i = 1; i < values.length; i++) { int d = Math.abs(values[i] - current); if (d < bestDiff) { bestDiff = d; initIdx = i; } }
		return new SimpleOption<>(label, SimpleOption.constantTooltip(Text.literal(tip)),
				(prefix, index) -> Text.literal(label + ": " + labels[Math.max(0, Math.min(labels.length - 1, index))]),
				new SimpleOption.ValidatingIntSliderCallbacks(0, values.length - 1), initIdx,
				index -> setter.accept(values[Math.max(0, Math.min(values.length - 1, index))]));
	}
public static void addCinematicOptions(OptionListWidget body) {
	MoneyakShadersConfig c = MoneyakShadersConfig.get();

	body.addHeader(Text.literal("§l§6Cinematic atmosphere"));
	body.addAll(
			slider("Atmosphere density", "Množství aerial perspective/mlhy v krajině. 0 = čistý vzduch, 100 = velmi hustá atmosféra. Default: 44", 0, 100, c.atmosphereDensity, v -> c.atmosphereDensity = v),
			slider("Horizon haze", "Zesílení atmosféry směrem k horizontu. Default: 66", 0, 100, c.atmosphereHorizon, v -> c.atmosphereHorizon = v),
			slider("Ambient strength", "Síla barevného sky ambientu ve stínech. Default: 72", 0, 120, c.ambientStrength, v -> c.ambientStrength = v),
			slider("Sun warmth", "Teplota slunce hlavně při východu/západu. Default: 74", 0, 100, c.sunWarmth, v -> c.sunWarmth = v),
			slider("Moon brightness", "Síla měsíčního directional světla a nočního liftu. Default: 58", 0, 100, c.moonBrightness, v -> c.moonBrightness = v));

	body.addHeader(Text.literal("§l§fCinematic clouds"));
	body.addAll(
			slider("Cloud coverage", "Kolik oblohy je typicky zakryto mraky. Default: 48", 0, 100, c.cloudCoverage, v -> c.cloudCoverage = v),
			slider("Cloud density", "Optická hustota a plnost mraků. Default: 62", 20, 100, c.cloudDensity, v -> c.cloudDensity = v),
			slider("Silver lining", "Záře hran mraků při slunci/měsíci za nimi. Default: 72", 0, 100, c.cloudSilverLining, v -> c.cloudSilverLining = v),
			slider("Cloud shadow", "Jak výrazně mraky zastiňují krajinu a vlastní objem. Default: 74", 0, 100, c.cloudShadowStrength, v -> c.cloudShadowStrength = v),
			slider("Cloud speed", "Rychlost world-space driftu mraků. Default: 38", 0, 100, c.cloudSpeed, v -> c.cloudSpeed = v));

	body.addHeader(Text.literal("§l§3Cinematic water"));
	body.addAll(
			slider("Water transparency", "Průhlednost hladiny. Vyšší = čistší voda. Default: 72", 0, 100, c.waterTransparency, v -> c.waterTransparency = v),
			slider("Water absorption", "Jak rychle voda se vzdáleností pohlcuje barvu. Default: 46", 0, 100, c.waterAbsorption, v -> c.waterAbsorption = v),
			slider("Water specular", "Síla měkkého odlesku slunce/měsíce. Default: 58", 0, 100, c.waterSpecular, v -> c.waterSpecular = v));

	body.addHeader(Text.literal("§l§aBiome colour"));
	body.addAll(
			slider("Biome blend radius", "Vlastní radius biome gradientu. 0 = tvrdé hranice, 7 = velmi jemný přechod. Default: 6", 0, 7, c.biomeBlendRadius, v -> c.biomeBlendRadius = v),
			slider("Biome vibrance", "Saturace pouze biome tintů grass/leaves/water. 100 = původní barva. Default: 110", 70, 135, c.biomeTintVibrance, v -> c.biomeTintVibrance = v));

	body.addHeader(Text.literal("§l§8Cinematic shadows"));
	body.addAll(
			slider("Shadow softness", "Plynulost penumbry se vzdáleností. Near contact stín zůstává ostrý. Default: 58", 0, 100, c.shadowSoftness, v -> c.shadowSoftness = v));
}
	private static int maxAnisotropy() {
		try {
			if (!GL.getCapabilities().GL_EXT_texture_filter_anisotropic) return 1;
			return Math.max(1, Math.min(32, Math.round(GL11.glGetFloat(EXTTextureFilterAnisotropic.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT))));
		} catch (Throwable ignored) { return 1; }
	}
}
