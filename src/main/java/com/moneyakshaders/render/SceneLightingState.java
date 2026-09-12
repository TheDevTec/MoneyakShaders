package com.moneyakshaders.render;

import org.joml.Vector3f;

import com.moneyakshaders.MoneyakShadersConfig;
import com.moneyakshaders.mixin.client.ClientWorldTimeAccessor;

import net.minecraft.client.MinecraftClient;

/**
 * Jediný zdroj pravdy pro vizuální stav světla celé scény.
 *
 * <p>Tahle class záměrně neobsahuje žádný OpenGL stav ani uniform locations.
 * Pouze jednou za frame spočítá fyzikálně / vizuálně související hodnoty,
 * které následně používají:
 *
 * <ul>
 *     <li>terrain lighting,</li>
 *     <li>directional shadows,</li>
 *     <li>water shading,</li>
 *     <li>clouds,</li>
 *     <li>atmosphere / volumetrics,</li>
 *     <li>post-process.</li>
 * </ul>
 *
 * <p>Výsledkem je, že každá část rendereru nepoužívá vlastní mírně odlišný
 * výpočet dne/noci, barvy slunce, měsíce nebo ambientu.
 *
 * <p>Render-thread only.
 */
final class SceneLightingState {

	/*
	 * Směr ZE SCÉNY KE světelnému zdroji.
	 *
	 * Tedy v poledne:
	 *     sunDirection ~= (0, 1, 0)
	 *
	 * a diffuse lighting může přímo používat:
	 *     max(dot(N, sunDirection), 0)
	 */
	final Vector3f sunDirection = new Vector3f(0f, 1f, 0f);
	final Vector3f moonDirection = new Vector3f(0f, -1f, 0f);

	/*
	 * Barevné spektrum přímého světla.
	 *
	 * Intenzita je oddělená v directSunStrength/directMoonStrength.
	 * Nikdy tedy nenásobit barvu samotnou kvůli denní době.
	 */
	final Vector3f sunColor = new Vector3f(1.00f, 0.985f, 0.955f);
	final Vector3f moonColor = new Vector3f(0.50f, 0.64f, 1.00f);

	/*
	 * Ambient oblohy.
	 *
	 * skyAmbientColor:
	 *     obecný nepřímý skylight.
	 *
	 * shadowAmbientColor:
	 *     chladnější ambient používaný v místech mimo přímé světlo.
	 *
	 * Není to "shadow tint".
	 * Shadow pouze odstraňuje direct light; tohle je skutečné nepřímé světlo.
	 */
	final Vector3f skyAmbientColor = new Vector3f(0.74f, 0.84f, 1.00f);
	final Vector3f shadowAmbientColor = new Vector3f(0.48f, 0.61f, 0.84f);

	/*
	 * Barvy atmosféry.
	 *
	 * fogBaseColor = vyšší část oblohy / atmosféry.
	 * horizonFogColor = hustší scattering poblíž horizontu.
	 */
	final Vector3f fogBaseColor = new Vector3f(0.30f, 0.50f, 0.76f);
	final Vector3f horizonFogColor = new Vector3f(0.67f, 0.76f, 0.86f);

	/** 0 = noc, 1 = slunce plně nad horizontem. */
	float dayFactor;

	/** 0 = den, 1 = měsíc plně nad horizontem. */
	float nightFactor;

	/** Síla aktuální fáze měsíce v rozsahu 0..1. */
	float moonPhaseFactor = 1f;

	/** Vanilla rain interpolation 0..1. */
	float rainFactor;

	/**
	 * Výsledná síla nepřímého skylightu.
	 *
	 * Je oddělená od RGB barvy, takže můžeme ambient zeslabovat bez změny jeho
	 * chromatického charakteru.
	 */
	float skyAmbientStrength = 1f;

	/** Výsledná intenzita přímého slunečního světla. */
	float directSunStrength;

	/** Výsledná intenzita přímého měsíčního světla. */
	float directMoonStrength;

	/** Celková síla aerial perspective. */
	float aerialPerspectiveStrength = 1f;

	/** Celková síla volumetric scattering / shafts. */
	float volumetricStrength = 1f;

	/** Síla stínů skutečných procedural clouds. */
	float cloudShadowStrength;

	/**
	 * True pokud je aktuálně dominantní directional source měsíc.
	 *
	 * Neznamená to pouze "je noc". Během sunrise/sunset se dominance vybírá podle
	 * skutečné intenzity obou zdrojů.
	 */
	boolean moonLighting;

	/** Kamera je pod vodou. Nastavuje renderer, nikoli tahle class. */
	boolean underwaterCamera;

	/**
	 * Aktuální Y hladiny vody kolem kamery.
	 *
	 * NEGATIVE_INFINITY = není známa / není relevantní.
	 */
	float waterSurfaceY = Float.NEGATIVE_INFINITY;

	/*
	 * Pomocné hodnoty užitečné pro shadows / sky.
	 */

	/** Výška slunce nad horizontem, přibližně -1..1. */
	float sunElevation;

	/** Výška měsíce nad horizontem, přibližně -1..1. */
	float moonElevation;

	/**
	 * 0 poblíž horizontu, 1 vysoko nad scénou.
	 *
	 * Liší se od dayFactor: ten zahrnuje fade pod horizontem.
	 */
	float directionalElevationFactor;

	/**
	 * Aktuální čas dne v rozsahu 0..1.
	 *
	 * 0     = MC tick 0
	 * 0.25  = přibližně poledne dle našeho současného celestial modelu
	 * 0.75  = půlnoc
	 */
	float timeOfDay01;

	private final Vector3f activeDirectionScratch = new Vector3f();
	private final Vector3f activeColorScratch = new Vector3f();

	SceneLightingState() {
		reset();
	}

	void reset() {
		sunDirection.set(0f, 1f, 0f);
		moonDirection.set(0f, -1f, 0f);

		sunColor.set(1.00f, 0.985f, 0.955f);
		moonColor.set(0.50f, 0.64f, 1.00f);

		skyAmbientColor.set(0.74f, 0.84f, 1.00f);
		shadowAmbientColor.set(0.48f, 0.61f, 0.84f);

		fogBaseColor.set(0.30f, 0.50f, 0.76f);
		horizonFogColor.set(0.67f, 0.76f, 0.86f);

		dayFactor = 1f;
		nightFactor = 0f;
		moonPhaseFactor = 1f;
		rainFactor = 0f;

		skyAmbientStrength = 1f;
		directSunStrength = 1f;
		directMoonStrength = 0f;

		aerialPerspectiveStrength = 1f;
		volumetricStrength = 1f;
		cloudShadowStrength = 0f;

		moonLighting = false;

		underwaterCamera = false;
		waterSurfaceY = Float.NEGATIVE_INFINITY;

		sunElevation = 1f;
		moonElevation = -1f;
		directionalElevationFactor = 1f;
		timeOfDay01 = 0.25f;
	}

	/**
	 * Přepočítá kompletní lighting state pro aktuální render frame.
	 */
	void computeFromWorld(MinecraftClient client, MoneyakShadersConfig cfg) {
		if (client == null || client.world == null) {
			reset();
			return;
		}

		float tickProgress = renderTickProgress(client);

		computeCelestialDirections(client, tickProgress);
		computeCelestialColors(cfg);
		computeWeather(client, tickProgress);
		computeAmbientColors(cfg);
		computeFogColors(cfg);
		computeStrengths(cfg);
		clampAndNormalize();
	}

	/**
	 * Stav vody se doplňuje z rendereru, protože právě renderer spolehlivě ví,
	 * zda se kamera nachází v jeho water volume.
	 */
	void setWaterState(boolean underwater, float surfaceY) {
		underwaterCamera = underwater;
		waterSurfaceY = Float.isFinite(surfaceY)
				? surfaceY
				: Float.NEGATIVE_INFINITY;
	}

	void clearWaterState() {
		underwaterCamera = false;
		waterSurfaceY = Float.NEGATIVE_INFINITY;
	}

	/** Creates the immutable frame contract after world and camera state have been sampled. */
	SceneLightingSnapshot snapshot() {
		return new SceneLightingSnapshot(this);
	}

	/**
	 * Dominantní directional source.
	 *
	 * Výsledek je interní scratch objekt. Neukládat jeho referenci.
	 */
	Vector3f activeDirection() {
		return activeDirectionScratch.set(
				moonLighting ? moonDirection : sunDirection);
	}

	/**
	 * Barva dominantního directional source.
	 *
	 * Výsledek je interní scratch objekt. Neukládat jeho referenci.
	 */
	Vector3f activeColor() {
		return activeColorScratch.set(
				moonLighting ? moonColor : sunColor);
	}

	float activeDirectStrength() {
		return moonLighting
				? directMoonStrength
				: directSunStrength;
	}

	private void computeCelestialDirections(
			MinecraftClient client,
			float tickProgress) {

		long absoluteTime = client.world.getTimeOfDay();
		long tod = Math.floorMod(absoluteTime, 24_000L);

		boolean daylightAdvances =
				((ClientWorldTimeAccessor) client.world)
						.moneyakshaders$shouldTickTimeOfDay()
				&& client.world.getTickManager().shouldTick();

		float interpolatedTick = daylightAdvances
				? clamp01(tickProgress)
				: 0f;

		double t =
				(tod + interpolatedTick)
						/ 24_000.0;

		timeOfDay01 = (float) t;

		/*
		 * Zachováváme současnou orientaci rendereru:
		 *
		 * t = 0.25 -> slunce nahoře.
		 */
		double angle =
				(t - 0.25)
						* Math.PI
						* 2.0;

		float horizontal = (float) Math.sin(angle);
		float vertical = (float) Math.cos(angle);

		sunElevation = vertical;
		moonElevation = -vertical;

		/*
		 * Směr ze surface bodu směrem ke zdroji.
		 */
		sunDirection.set(
				-horizontal,
				vertical,
				0f);

		moonDirection.set(
				horizontal,
				-vertical,
				0f);

		safeNormalize(sunDirection, 0f, 1f, 0f);
		safeNormalize(moonDirection, 0f, -1f, 0f);

		/*
		 * Přímé světlo lehce pokračuje i těsně pod geometrickým horizontem,
		 * což zabrání tvrdému světelnému skoku při sunset / sunrise.
		 */
		dayFactor =
				smoothstep(
						-0.065f,
						0.22f,
						sunElevation);

		nightFactor =
				smoothstep(
						-0.065f,
						0.22f,
						moonElevation);

		long dayIndex =
				Math.floorDiv(
						absoluteTime,
						24_000L);

		int moonPhase =
				(int) Math.floorMod(
						dayIndex,
						8L);

		/*
		 * phase:
		 *
		 * 0 -> full
		 * 4 -> new moon
		 */
		moonPhaseFactor =
				1f
						- Math.min(
								moonPhase,
								8 - moonPhase)
						/ 4f;

		moonPhaseFactor =
				smoothstep(
						0f,
						1f,
						moonPhaseFactor);

		float sunScore = dayFactor;
		float moonScore =
				nightFactor
						* (0.35f + moonPhaseFactor * 0.65f);

		moonLighting = moonScore > sunScore;

		float activeElevation =
				moonLighting
						? Math.max(0f, moonElevation)
						: Math.max(0f, sunElevation);

		directionalElevationFactor =
				smoothstep(
						0.02f,
						0.68f,
						activeElevation);
	}

	private void computeCelestialColors(MoneyakShadersConfig cfg) {
		float warmth =
				unit(cfg.sunWarmth);

		/*
		 * warm = 1 při sunset/sunrise
		 * warm = 0 při vysokém slunci.
		 */
		float lowSun =
				1f
						- smoothstep(
								0.06f,
								0.58f,
								Math.max(
										0f,
										sunElevation));

		lowSun *= warmth;

		/*
		 * High sun zůstává téměř neutrální.
		 *
		 * Sunset může jít výrazně do zlaté/oranžové, aniž bychom
		 * přebarvili ambient celé scény.
		 */
		sunColor.set(
				1.000f,
				mix(0.985f, 0.720f, lowSun),
				mix(0.955f, 0.430f, lowSun));

		/*
		 * Měsíční světlo je studené, ale ne neonově modré.
		 *
		 * Full moon je lehce bělejší.
		 */
		float full =
				moonPhaseFactor;

		moonColor.set(
				mix(0.43f, 0.54f, full),
				mix(0.57f, 0.68f, full),
				mix(0.96f, 1.00f, full));
	}

	private void computeWeather(
			MinecraftClient client,
			float tickProgress) {

		rainFactor =
				clamp01(
						client.world
								.getRainGradient(
										tickProgress));
	}

	private void computeAmbientColors(MoneyakShadersConfig cfg) {
		/*
		 * DAY
		 *
		 * Ambient je schválně studenější než direct sun.
		 * To nám vytvoří žádaný kontrast:
		 *
		 *     teplé sunlight
		 *     +
		 *     chladnější shadows.
		 */
		float dayR = 0.73f;
		float dayG = 0.83f;
		float dayB = 1.00f;

		/*
		 * Při sunsetu ambient nezoranžoví.
		 *
		 * Lehce se naopak posune do hlubší modré, protože warm
		 * charakter scény má pocházet hlavně z direct light.
		 */
		float sunset =
				dayFactor
						* (1f - directionalElevationFactor);

		dayR =
				mix(
						dayR,
						0.56f,
						sunset * 0.52f);

		dayG =
				mix(
						dayG,
						0.67f,
						sunset * 0.52f);

		dayB =
				mix(
						dayB,
						0.96f,
						sunset * 0.30f);

		/*
		 * NIGHT
		 *
		 * Hodnoty jsou chroma, ne výsledný jas.
		 * Jas řídí skyAmbientStrength.
		 */
		float nightR =
				mix(
						0.095f,
						0.125f,
						moonPhaseFactor);

		float nightG =
				mix(
						0.145f,
						0.205f,
						moonPhaseFactor);

		float nightB =
				mix(
						0.290f,
						0.410f,
						moonPhaseFactor);

		float nightMix =
				smoothstep(
						0.12f,
						0.88f,
						nightFactor);

		skyAmbientColor.set(
				mix(dayR, nightR, nightMix),
				mix(dayG, nightG, nightMix),
				mix(dayB, nightB, nightMix));

		/*
		 * Shadow ambient je o něco tmavší a chladnější.
		 *
		 * Pozor:
		 * nebude se násobit do direct světla.
		 */
		shadowAmbientColor.set(
				skyAmbientColor.x * mix(0.72f, 0.78f, nightMix),
				skyAmbientColor.y * mix(0.80f, 0.84f, nightMix),
				skyAmbientColor.z * mix(0.94f, 1.02f, nightMix));

		/*
		 * Déšť ambient desaturuje a lehce ochladí.
		 */
		if (rainFactor > 0.0001f) {
			float r = rainFactor;

			mixInto(
					skyAmbientColor,
					0.60f,
					0.66f,
					0.74f,
					r * 0.46f);

			mixInto(
					shadowAmbientColor,
					0.46f,
					0.53f,
					0.64f,
					r * 0.40f);
		}
	}

	private void computeFogColors(MoneyakShadersConfig cfg) {
		float night =
				smoothstep(
						0.10f,
						0.90f,
						nightFactor);

		/*
		 * Day zenith / upper aerial perspective.
		 */
		float dayBaseR = 0.26f;
		float dayBaseG = 0.48f;
		float dayBaseB = 0.76f;

		/*
		 * Day horizon.
		 */
		float dayHorizonR = 0.66f;
		float dayHorizonG = 0.75f;
		float dayHorizonB = 0.86f;

		/*
		 * Sunset horizon dostává trochu teplého scattering.
		 *
		 * Na rozdíl od starého systému tím neobarvujeme terrain.
		 */
		float sunset =
				dayFactor
						* (1f - directionalElevationFactor);

		dayHorizonR =
				mix(
						dayHorizonR,
						0.94f,
						sunset * 0.70f);

		dayHorizonG =
				mix(
						dayHorizonG,
						0.61f,
						sunset * 0.60f);

		dayHorizonB =
				mix(
						dayHorizonB,
						0.39f,
						sunset * 0.50f);

		float nightBaseR = 0.015f;
		float nightBaseG = 0.032f;
		float nightBaseB = 0.085f;

		float nightHorizonR = 0.055f;
		float nightHorizonG = 0.085f;
		float nightHorizonB = 0.155f;

		fogBaseColor.set(
				mix(dayBaseR, nightBaseR, night),
				mix(dayBaseG, nightBaseG, night),
				mix(dayBaseB, nightBaseB, night));

		horizonFogColor.set(
				mix(dayHorizonR, nightHorizonR, night),
				mix(dayHorizonG, nightHorizonG, night),
				mix(dayHorizonB, nightHorizonB, night));

		if (rainFactor > 0.0001f) {
			float r = rainFactor;

			mixInto(
					fogBaseColor,
					0.33f,
					0.39f,
					0.47f,
					r * 0.65f);

			mixInto(
					horizonFogColor,
					0.48f,
					0.53f,
					0.59f,
					r * 0.72f);
		}
	}

	private void computeStrengths(MoneyakShadersConfig cfg) {
		float ambientSetting =
				unit(cfg.ambientStrength);

		float moonSetting =
				unit(cfg.moonBrightness);

		/*
		 * Slunce.
		 *
		 * Déšť tlumí přímé světlo výrazněji než ambient.
		 */
		directSunStrength =
				dayFactor
						* mix(
								0.84f,
								0.42f,
								rainFactor);

		/*
		 * Měsíc má výrazně menší světelný výkon než slunce.
		 *
		 * Full moon:
		 * ~0.16 při moonBrightness=100
		 *
		 * Default 58:
		 * zhruba ~0.11
		 */
		float moonEnergy =
				0.045f
						+ moonPhaseFactor
						* 0.115f;

		directMoonStrength =
				nightFactor
						* moonEnergy
						* mix(
								0.50f,
								1.0f,
								moonSetting);

		directMoonStrength *=
				mix(
						1f,
						0.60f,
						rainFactor);

		/*
		 * Sky ambient nesmí v noci zmizet.
		 *
		 * Chceme viditelnou, ale tmavou noc bez permanentního
		 * full-black crush.
		 */
		float daylightAmbient =
				mix(
						0.26f,
						0.78f,
						dayFactor);

		float moonAmbient =
				nightFactor
						* (0.08f
								+ moonPhaseFactor
								* 0.11f);

		skyAmbientStrength =
				Math.max(
						daylightAmbient,
						moonAmbient);

		skyAmbientStrength *=
				mix(
						0.55f,
						0.90f,
						ambientSetting);

		/*
		 * Cloud/rainy day zůstane čitelný díky ambientu,
		 * pouze se ztratí část contrast/direct light.
		 */
		skyAmbientStrength *=
				mix(
						1f,
						0.82f,
						rainFactor);

		/*
		 * Zatím používáme existující config.
		 *
		 * V MoneyakShadersConfig redesignu později dostane vlastní
		 * explicitní knob.
		 */
		aerialPerspectiveStrength =
				0.45f
						+ unit(cfg.atmosphereDensity)
						* 0.90f;

		aerialPerspectiveStrength *=
				mix(
						1f,
						1.32f,
						rainFactor);

		volumetricStrength =
				unit(cfg.postGodRays);

		volumetricStrength *=
				mix(
						0.76f,
						1.08f,
						1f - directionalElevationFactor);

		/*
		 * Za deště nechceme fantasy god-rays.
		 */
		volumetricStrength *=
				mix(
						1f,
						0.42f,
						rainFactor);

		// Vanilla's cloud renderer owns its geometry but has no custom projected
		// shadow texture for the terrain pass, so do not retain a dead slider.
		cloudShadowStrength = 0f;

		/*
		 * V noci je cloud shadow na terrainu méně relevantní,
		 * protože direct moon energy je výrazně nižší.
		 */
		cloudShadowStrength *=
				moonLighting
						? 0.34f
						: 1f;
	}

	void clampAndNormalize() {
		safeNormalize(
				sunDirection,
				0f,
				1f,
				0f);

		safeNormalize(
				moonDirection,
				0f,
				-1f,
				0f);

		clampColor(sunColor);
		clampColor(moonColor);
		clampColor(skyAmbientColor);
		clampColor(shadowAmbientColor);
		clampColor(fogBaseColor);
		clampColor(horizonFogColor);

		dayFactor =
				clamp01(dayFactor);

		nightFactor =
				clamp01(nightFactor);

		moonPhaseFactor =
				clamp01(moonPhaseFactor);

		rainFactor =
				clamp01(rainFactor);

		skyAmbientStrength =
				clamp(
						skyAmbientStrength,
						0f,
						2f);

		directSunStrength =
				clamp(
						directSunStrength,
						0f,
						2f);

		directMoonStrength =
				clamp(
						directMoonStrength,
						0f,
						0.50f);

		aerialPerspectiveStrength =
				clamp(
						aerialPerspectiveStrength,
						0f,
						2.5f);

		volumetricStrength =
				clamp(
						volumetricStrength,
						0f,
						2f);

		cloudShadowStrength =
				clamp01(
						cloudShadowStrength);

		sunElevation =
				clamp(
						sunElevation,
						-1f,
						1f);

		moonElevation =
				clamp(
						moonElevation,
						-1f,
						1f);

		directionalElevationFactor =
				clamp01(
						directionalElevationFactor);

		timeOfDay01 =
				timeOfDay01
						- (float) Math.floor(timeOfDay01);
	}

	private static float renderTickProgress(MinecraftClient client) {
		if (client.getRenderTickCounter() == null) {
			return 0f;
		}

		return clamp01(
				client
						.getRenderTickCounter()
						.getTickProgress(false));
	}

	private static void mixInto(
			Vector3f value,
			float r,
			float g,
			float b,
			float amount) {

		float a =
				clamp01(amount);

		value.set(
				mix(value.x, r, a),
				mix(value.y, g, a),
				mix(value.z, b, a));
	}

	private static void clampColor(Vector3f color) {
		color.set(
				clamp(color.x, 0f, 4f),
				clamp(color.y, 0f, 4f),
				clamp(color.z, 0f, 4f));
	}

	private static void safeNormalize(
			Vector3f value,
			float fallbackX,
			float fallbackY,
			float fallbackZ) {

		float lenSq =
				value.x * value.x
						+ value.y * value.y
						+ value.z * value.z;

		if (!Float.isFinite(lenSq)
				|| lenSq < 1.0e-10f) {

			value.set(
					fallbackX,
					fallbackY,
					fallbackZ);

			return;
		}

		float inv =
				1f
						/ (float) Math.sqrt(lenSq);

		value.mul(inv);
	}

	private static float unit(int value) {
		return clamp01(
				value / 100f);
	}

	private static float smoothstep(
			float edge0,
			float edge1,
			float value) {

		if (edge1 <= edge0) {
			return value >= edge1
					? 1f
					: 0f;
		}

		float t =
				clamp01(
						(value - edge0)
								/ (edge1 - edge0));

		return t
				* t
				* (3f - 2f * t);
	}

	private static float mix(
			float a,
			float b,
			float t) {

		return a
				+ (b - a)
				* t;
	}

	private static float clamp01(float value) {
		return clamp(
				value,
				0f,
				1f);
	}

	private static float clamp(
			float value,
			float min,
			float max) {

		return value < min
				? min
				: Math.min(
						value,
						max);
	}
}
