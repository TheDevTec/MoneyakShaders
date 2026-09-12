package com.moneyakshaders.render;

import org.joml.Vector3f;

/** Immutable lighting contract shared by every world pass for one rendered frame. */
final class SceneLightingSnapshot {
	final Vector3f direction;
	final Vector3f directColor;
	final Vector3f skyAmbientColor;
	final Vector3f shadowAmbientColor;
	final Vector3f fogColor;
	final float directStrength;
	final float skyAmbientStrength;
	final float rainFactor;
	final float dayFactor;
	final boolean moonLighting;
	final boolean underwater;

	SceneLightingSnapshot(SceneLightingState state) {
		direction = new Vector3f(state.activeDirection());
		directColor = new Vector3f(state.activeColor());
		skyAmbientColor = new Vector3f(state.skyAmbientColor);
		shadowAmbientColor = new Vector3f(state.shadowAmbientColor);
		fogColor = new Vector3f(state.horizonFogColor);
		directStrength = state.activeDirectStrength();
		skyAmbientStrength = state.skyAmbientStrength;
		rainFactor = state.rainFactor;
		dayFactor = state.dayFactor;
		moonLighting = state.moonLighting;
		underwater = state.underwaterCamera;
	}
}
