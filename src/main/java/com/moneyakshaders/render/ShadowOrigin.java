package com.moneyakshaders.render;

final class ShadowOrigin {
	double x, y, z;
	private double lastCameraX, lastCameraY, lastCameraZ;
	private double lastTexelSize = Double.NaN;
	private float lastBasisHash = Float.NaN;
	private boolean valid;

	void reset() {
		valid = false;
		lastTexelSize = Double.NaN;
		lastBasisHash = Float.NaN;
	}

	boolean isValid() {
		return valid;
	}

	boolean requiresReset(double texel, float basisHash) {
		if (!valid || !Double.isFinite(texel) || texel <= 0.0 || !Float.isFinite(basisHash)) return true;
		if (!Double.isFinite(lastTexelSize) || !Float.isFinite(lastBasisHash)) return true;
		double ratio = texel / lastTexelSize;
		if (ratio < 0.75 || ratio > 1.333333333) return true;
		return Math.abs(basisHash-lastBasisHash) > 0.35f;
	}

	void update(double cx, double cy, double cz, double texel,
			double rx, double ry, double rz, double ux, double uy, double uz) {
		float basisHash = basisHash(rx, ry, rz, ux, uy, uz);

		if (!valid) {
			setExact(cx, cy, cz, texel, basisHash);
			return;
		}

		double cameraDx = cx-lastCameraX;
		double cameraDy = cy-lastCameraY;
		double cameraDz = cz-lastCameraZ;

		if (cameraDx == 0.0 && cameraDy == 0.0 && cameraDz == 0.0) {
			lastTexelSize = texel;
			lastBasisHash = basisHash;
			return;
		}

		if (Math.abs(cameraDx)+Math.abs(cameraDy)+Math.abs(cameraDz) > 1024.0
				|| !Double.isFinite(texel) || texel <= 0.0) {
			setExact(cx, cy, cz, texel, basisHash);
			return;
		}

		if (requiresReset(texel, basisHash)) {
			setExact(cx, cy, cz, texel, basisHash);
			return;
		}

		double dx = cx-x;
		double dy = cy-y;
		double dz = cz-z;

		double r = dx*rx+dy*ry+dz*rz;
		double u = dx*ux+dy*uy+dz*uz;

		double snappedR = Math.rint(r/texel)*texel;
		double snappedU = Math.rint(u/texel)*texel;

		double correctionR = snappedR-r;
		double correctionU = snappedU-u;

		x = cx+correctionR*rx+correctionU*ux;
		y = cy+correctionR*ry+correctionU*uy;
		z = cz+correctionR*rz+correctionU*uz;

		lastCameraX = cx;
		lastCameraY = cy;
		lastCameraZ = cz;
		lastTexelSize = texel;
		lastBasisHash = basisHash;
	}

	private void setExact(double cx, double cy, double cz, double texel, float basisHash) {
		x = cx;
		y = cy;
		z = cz;
		lastCameraX = cx;
		lastCameraY = cy;
		lastCameraZ = cz;
		lastTexelSize = texel;
		lastBasisHash = basisHash;
		valid = true;
	}

	private static float basisHash(double rx, double ry, double rz, double ux, double uy, double uz) {
		return (float)(rx*0.173+ry*0.311+rz*0.547+ux*0.719+uy*0.887+uz*1.037);
	}
}