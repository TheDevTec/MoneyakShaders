package com.moneyakshaders.client;

import org.joml.Matrix3f;
import org.joml.Matrix4f;

/** Optional fast path implemented only by vanilla's standard BufferBuilder. */
public interface BufferBuilderBulkAccess {
	boolean moneyakshaders$appendTranslated(float[] fp, int[] ci, int count,
			float offsetX, float offsetY, float offsetZ, int light, int tint);

	/** Bulk path for animated model cuboids: immutable vertices, live part matrices. */
	boolean moneyakshaders$appendTransformed(float[] fp, int count, Matrix4f position, Matrix3f normal,
			int light, int overlay, int tint);
}
