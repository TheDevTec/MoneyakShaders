package com.moneyakshaders.client.gui;

import com.moneyakshaders.MoneyakShadersConfig;

/** Coherent multiplayer renderer profiles; thread-pool sizing and non-render preferences stay untouched. */
public enum GlobalQualityProfile {
	POTATO, PERFORMANCE, CLASSIC, QUALITY, ULTRA;

	public static GlobalQualityProfile fromConfig(String value) {
		if (value != null) {
			try {
				return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
			} catch (IllegalArgumentException ignored) {
				// malformed legacy config falls back to the familiar visual baseline
			}
		}
		return CLASSIC;
	}

	public static void apply(MoneyakShadersConfig c, GlobalQualityProfile profile) {
		if (c == null || profile == null) return;
		resetAllProfileSettings(c);
		switch (profile) {
			case POTATO -> potato(c);
			case PERFORMANCE -> performance(c);
			case CLASSIC -> classic(c);
			case QUALITY -> quality(c);
			case ULTRA -> ultra(c);
		}
		c.globalQualityProfile = profile.name();
	}

	/** A profile is a replacement, never a partial overlay over values left by an older profile. */
	private static void resetAllProfileSettings(MoneyakShadersConfig target) {
		MoneyakShadersConfig defaults = new MoneyakShadersConfig();
		for (java.lang.reflect.Field field : MoneyakShadersConfig.class.getDeclaredFields()) {
			int modifiers = field.getModifiers();
			if (!java.lang.reflect.Modifier.isPublic(modifiers) || java.lang.reflect.Modifier.isStatic(modifiers)
					|| field.getName().equals("debugAutoScreenshot")) continue;
			try {
				field.set(target, field.get(defaults));
			} catch (IllegalAccessException ignored) {
				// A future inaccessible setting simply keeps its old value; existing public settings reset.
			}
		}
	}

	private static void common(MoneyakShadersConfig c, int uploads, int readyMeshes, int lightBudget,
			int lightIntervalMs, int particleCap, int particleDistance) {
		c.experimentalRenderer = true; c.skipVanillaChunkBuilds = true; c.forceAsyncChunkRebuilds = true;
		c.frameBudgetEnabled = true; c.asyncLightUpdates = true; c.occlusionCulling = true;
		c.entityOcclusionCulling = true; c.blockEntityFrustumCull = true; c.occlusionMeshing = true;
		c.deferUndergroundSections = true; c.shadowCasterCulling = true; c.sortOpaqueFrontToBack = true;
		c.undergroundSleepMode = "MAXIMUM_FPS"; c.undergroundActiveRadiusSections = 1;
		c.entityTextureDistanceLod = true;
		c.enableUnusedSceneCulling = true; c.enableBlockFaceCulling = true; c.enableTextureAggregation = true;
		c.prewarmItemModels = true; c.maxChunkUploadsPerFrame = uploads; c.maxReadySectionMeshes = readyMeshes;
		// Light correctness is profile-independent. Whole invisible domains are deferred instead of
		// publishing partial generations according to a time interval or per-frame section quota.
		c.lightRenderBudget = 0; c.lightUpdateIntervalMs = 0;
		c.maxActiveParticles = particleCap; c.particleSpawnDistanceLimit = particleDistance; c.particleRenderDistance = particleDistance;
	}

	/** Streaming values are part of a profile too; leaving prior custom values here made a profile
	 * switch inherit an arbitrarily slow snapshot pipeline from an older config file. */
	private static void streaming(MoneyakShadersConfig c, int snapshotCells, int pendingSnapshots, int pooledSnapshots) {
		c.sectionSnapshotCellsPerFrame = snapshotCells;
		c.maxPendingSectionSnapshots = pendingSnapshots;
		c.maxPooledSectionSnapshots = pooledSnapshots;
	}

	private static void potato(MoneyakShadersConfig c) {
		common(c, 8, 16, 64, 100, 500, 16);
		streaming(c, 16_384, 16, 16);
		c.terrainMipmaps = true; c.terrainAnisotropy = 1; c.leavesOpaqueDistance = 20;
		c.terrainTextureLodBias = 2;
		c.windSway = false; c.fantasyClouds = false; c.waterfallParticles = false;
		c.waterSplash = false; c.rainRipples = false; c.rainMist = false; c.dayNightTint = false;
		c.dynamicHeldLight = false; c.dynamicLighting = false; c.terrainPointLights = false; c.lightSmoothing = 0;
		c.postProcessing = false; c.fxaa = false; c.depthPrePass = true;
		c.sunShadows = false; c.pointLightShadows = true; c.entityShadows = false;
		c.shadowNearResolution = 512; c.shadowFarResolution = 512; c.pointShadowResolution = 256;
		c.mobRenderDistance = 32; c.staticEntityRenderDistance = 48; c.itemRenderDistance = 12; c.xpOrbRenderDistance = 12; c.projectileRenderDistance = 24; c.blockEntityRenderDistance = 20;
		c.brightnessCacheSize = 8192; c.entityLightCacheMs = 250;
		c.frameBudgetMeshUploadMs = 1; c.frameBudgetSnapshotMs = 1; c.frameBudgetLightApplyMs = 1; c.frameBudgetShadowMs = 1;
		c.undergroundEvictionDelayMs = 250; c.undergroundSleepGpuBudgetMb = 0;
	}

	private static void performance(MoneyakShadersConfig c) {
		common(c, 24, 48, 160, 50, 2000, 32);
		streaming(c, 32_768, 32, 24);
		c.terrainMipmaps = true; c.terrainAnisotropy = 4; c.leavesOpaqueDistance = 40;
		c.terrainTextureLodBias = 1;
		c.windSway = false; c.fantasyClouds = false; c.waterfallParticles = false;
		c.waterSplash = true; c.rainRipples = false; c.rainMist = false; c.dayNightTint = false;
		c.dynamicHeldLight = true; c.dynamicLighting = true; c.terrainPointLights = false; c.lightSmoothing = 50;
		c.postProcessing = false; c.fxaa = false; c.depthPrePass = true;
		c.sunShadows = false; c.pointLightShadows = true; c.entityShadows = false;
		c.shadowNearResolution = 1024; c.shadowFarResolution = 512; c.pointShadowResolution = 256;
		c.mobRenderDistance = 48; c.staticEntityRenderDistance = 64; c.itemRenderDistance = 20; c.xpOrbRenderDistance = 16; c.projectileRenderDistance = 32; c.blockEntityRenderDistance = 28;
		c.brightnessCacheSize = 16384; c.entityLightCacheMs = 150;
		c.frameBudgetMeshUploadMs = 1; c.frameBudgetSnapshotMs = 3; c.frameBudgetLightApplyMs = 1; c.frameBudgetShadowMs = 1;
		c.undergroundEvictionDelayMs = 500; c.undergroundSleepGpuBudgetMb = 0;
	}

	private static void classic(MoneyakShadersConfig c) {
		common(c, 48, 64, 256, 50, 4000, 40);
		streaming(c, 65_536, 48, 40);
		c.terrainMipmaps = true; c.terrainAnisotropy = 4; c.leavesOpaqueDistance = 0;
		c.terrainTextureLodBias = 1;
		c.windSway = false; c.fantasyClouds = false; c.waterfallParticles = true;
		c.waterSplash = true; c.rainRipples = false; c.rainMist = false; c.dayNightTint = false;
		c.dynamicHeldLight = false; c.dynamicLighting = false; c.terrainPointLights = false; c.lightSmoothing = 0;
		c.postProcessing = false; c.fxaa = false; c.depthPrePass = true;
		c.sunShadows = false; c.pointLightShadows = true; c.entityShadows = false;
		c.shadowNearResolution = 1024; c.shadowFarResolution = 512; c.pointShadowResolution = 256;
		c.mobRenderDistance = 64; c.staticEntityRenderDistance = 96; c.itemRenderDistance = 24; c.xpOrbRenderDistance = 16; c.projectileRenderDistance = 32; c.blockEntityRenderDistance = 32;
		c.brightnessCacheSize = 32768; c.entityLightCacheMs = 100;
		c.frameBudgetMeshUploadMs = 2; c.frameBudgetSnapshotMs = 4; c.frameBudgetLightApplyMs = 1; c.frameBudgetShadowMs = 1;
		c.undergroundEvictionDelayMs = 900; c.undergroundSleepGpuBudgetMb = 16;
	}

	private static void quality(MoneyakShadersConfig c) {
		common(c, 96, 96, 384, 40, 6000, 48);
		streaming(c, 98_304, 64, 64);

		c.terrainMipmaps = true;
		c.terrainAnisotropy = 8;
		c.leavesOpaqueDistance = 128;
		c.terrainTextureLodBias = 0;

		c.windSway = true;
		c.fantasyClouds = true;
		c.waterfallParticles = true;
		c.waterSplash = true;
		c.rainRipples = true;
		c.rainMist = true;
		c.dayNightTint = true;

		c.dynamicHeldLight = true;
		c.dynamicLighting = true;
		c.terrainPointLights = true;
		c.lightSmoothing = 100;

		c.postProcessing = true;
		c.postExposure = 98;
		c.postBloom = 20;
		c.postVignette = 8;
		c.postSaturation = 106;
		c.postGodRays = 62;
		c.fxaa = true;

		c.worldBrightness = 98;
		c.worldContrast = 107;
		c.worldSaturation = 106;

		c.atmosphereDensity = 40;
		c.atmosphereHorizon = 62;
		c.ambientStrength = 70;

		c.sunWarmth = 72;
		c.moonBrightness = 54;

		c.cloudCoverage = 46;
		c.cloudDensity = 58;
		c.cloudSilverLining = 66;
		c.cloudShadowStrength = 68;
		c.cloudSpeed = 36;

		c.waterBumpiness = 88;
		c.waterReflection = 68;
		c.waterRefraction = 54;
		c.waterFoam = 28;
		c.waterSsrSteps = 0;
		c.waterTransparency = 74;
		c.waterAbsorption = 44;
		c.waterSpecular = 54;

		c.biomeBlendRadius = 5;
		c.biomeTintVibrance = 107;

		c.sunShadows = true;
		c.pointLightShadows = true;
		c.entityShadows = true;

		c.shadowNearResolution = 4096;
		c.shadowFarResolution = 2048;
		c.pointShadowResolution = 256;
		c.shadowDistanceChunks = 10;
		c.shadowNearChunks = 6;
		c.shadowSharpDistance = 36;
		c.shadowStrength = 72;
		c.shadowFarBlur = 2;
		c.shadowCascadeBlend = 10;
		c.shadowSoftness = 54;
		c.entityShadowDistance = 28;

		c.shadowGpuBudgetMs = 7;
		c.shadowMinResolution = 1024;

		c.depthPrePass = true;

		c.mobRenderDistance = 80;
		c.staticEntityRenderDistance = 128;
		c.itemRenderDistance = 32;
		c.xpOrbRenderDistance = 24;
		c.projectileRenderDistance = 48;
		c.blockEntityRenderDistance = 40;

		c.brightnessCacheSize = 32768;
		c.entityLightCacheMs = 100;

		c.frameBudgetMeshUploadMs = 2;
		c.frameBudgetSnapshotMs = 5;
		c.frameBudgetLightApplyMs = 1;
		c.frameBudgetShadowMs = 1;

		c.undergroundEvictionDelayMs = 1500;
		c.undergroundSleepGpuBudgetMb = 48;
	}

	private static void ultra(MoneyakShadersConfig c) {
		common(c, 128, 128, 512, 33, 10000, 64);
		streaming(c, 196_608, 128, 96);

		c.terrainMipmaps = true;
		c.terrainAnisotropy = 16;
		c.leavesOpaqueDistance = 256;
		c.terrainTextureLodBias = 0;

		c.windSway = true;
		c.fantasyClouds = true;
		c.waterfallParticles = true;
		c.waterSplash = true;
		c.rainRipples = true;
		c.rainMist = true;
		c.dayNightTint = true;

		c.dynamicHeldLight = true;
		c.dynamicLighting = true;
		c.terrainPointLights = true;
		c.lightSmoothing = 100;

		c.postProcessing = true;
		c.postExposure = 98;
		c.postBloom = 24;
		c.postVignette = 9;
		c.postSaturation = 109;
		c.postGodRays = 72;
		c.fxaa = true;

		c.worldBrightness = 99;
		c.worldContrast = 110;
		c.worldSaturation = 109;

		c.atmosphereDensity = 46;
		c.atmosphereHorizon = 70;
		c.ambientStrength = 74;

		c.sunWarmth = 78;
		c.moonBrightness = 62;

		c.cloudCoverage = 50;
		c.cloudDensity = 66;
		c.cloudSilverLining = 76;
		c.cloudShadowStrength = 78;
		c.cloudSpeed = 40;

		c.waterBumpiness = 96;
		c.waterReflection = 76;
		c.waterRefraction = 60;
		c.waterFoam = 34;
		c.waterSsrSteps = 0;
		c.waterTransparency = 56;
		c.waterAbsorption = 64;
		c.waterSpecular = 62;

		c.biomeBlendRadius = 6;
		c.biomeTintVibrance = 111;

		c.sunShadows = true;
		c.pointLightShadows = true;
		c.entityShadows = true;

		c.shadowNearResolution = 8192;
		c.shadowFarResolution = 4096;
		c.pointShadowResolution = 512;
		c.shadowDistanceChunks = 14;
		c.shadowNearChunks = 7;
		c.shadowSharpDistance = 44;
		c.shadowStrength = 76;
		c.shadowFarBlur = 2;
		c.shadowCascadeBlend = 12;
		c.shadowSoftness = 62;
		c.entityShadowDistance = 48;

		c.shadowGpuBudgetMs = 10;
		c.shadowMinResolution = 1024;

		c.depthPrePass = true;

		c.mobRenderDistance = 112;
		c.staticEntityRenderDistance = 192;
		c.itemRenderDistance = 48;
		c.xpOrbRenderDistance = 32;
		c.projectileRenderDistance = 64;
		c.blockEntityRenderDistance = 56;

		c.brightnessCacheSize = 65536;
		c.entityLightCacheMs = 75;

		c.frameBudgetMeshUploadMs = 4;
		c.frameBudgetSnapshotMs = 8;
		c.frameBudgetLightApplyMs = 1;
		c.frameBudgetShadowMs = 2;

		c.undergroundEvictionDelayMs = 2500;
		c.undergroundSleepGpuBudgetMb = 96;
	}
}
