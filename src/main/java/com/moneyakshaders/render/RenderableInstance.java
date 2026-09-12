package com.moneyakshaders.render;

/**
 * Spec §11.1: one snapshot per entity per frame, shared by main pass + shadow passes so
 * animation / model / random-variant selection runs exactly once. Skeleton for phase 5.
 */
public final class RenderableInstance {
	public final int instanceId;
	public final long modelKey;
	public final float x, y, z;
	public final float yaw, pitch;
	public final float boundsR;
	public final boolean castsSunShadow;
	public final boolean castsLocalShadow;
	public final int shadowLod;
	public final long resourceGeneration;

	public RenderableInstance(int instanceId, long modelKey, float x, float y, float z,
			float yaw, float pitch, float boundsR,
			boolean castsSunShadow, boolean castsLocalShadow, int shadowLod,
			long resourceGeneration) {
		this.instanceId = instanceId;
		this.modelKey = modelKey;
		this.x = x; this.y = y; this.z = z;
		this.yaw = yaw; this.pitch = pitch;
		this.boundsR = boundsR;
		this.castsSunShadow = castsSunShadow;
		this.castsLocalShadow = castsLocalShadow;
		this.shadowLod = shadowLod;
		this.resourceGeneration = resourceGeneration;
	}
}
