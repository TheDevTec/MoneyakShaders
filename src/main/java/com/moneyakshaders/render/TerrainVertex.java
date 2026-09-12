package com.moneyakshaders.render;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Plan C / Phase 1.2 — compact terrain vertex format (Sodium-style).
 *
 * <p>20 bytes/vertex vs vanilla's ~32+: position is quantized 16-bit fixed
 * point (section-local), UV is normalized 16-bit, color/light/normal are
 * 8-bit. This is the data the custom mesher (Phase 2) will emit and the GL
 * uploader (Phase 3) will hand to the GPU.
 *
 * <p>Intentionally dependency-free (only {@link ByteBuffer}) so it can be
 * self-tested standalone without the Minecraft classpath - see {@link #main}.
 *
 * <pre>
 * offset  field            type      encode
 *   0..5  position x,y,z    u16 x3    round((coord + 8) * 256)   // 1/256 block, range [-8, +248)
 *   6..9  texture  u,v      u16 x2    round(t * 65535)           // normalized atlas coord
 *  10..13 tint     r,g,b,a  u8  x4 (a reserved; never lighting)
 *  14..15 light    block,sky u8 x2    level*16 (1/16-level precision)
 *  16..18 normal   x,y,z    i8  x3    round(n * 127)
 *     19  material flags    u8
 *  20..23 light chromaticity r,g,b + confidence u8 x4
 *     24  ambient occlusion u8
 *     25  sway weight        u8
 *  26..27 surface flags      u16 (reserved for explicit material extensions)
 * </pre>
 */
public final class TerrainVertex {
	public static final int STRIDE = 28;

	public static final int OFF_POS = 0;
	public static final int OFF_UV = 6;
	public static final int OFF_COLOR = 10;
	public static final int OFF_LIGHT = 14;
	public static final int OFF_NORMAL = 16;
	public static final int OFF_MATERIAL = 19;
	public static final int OFF_LIGHT_TINT = 20;
	public static final int OFF_AO = 24;
	public static final int OFF_SWAY = 25;
	public static final int OFF_SURFACE_FLAGS = 26;

	private static final float POS_SCALE = 256.0f;
	private static final float POS_BIAS = 8.0f;

	private TerrainVertex() {
	}

	/** Encodes one vertex at the buffer's current position and advances it by {@link #STRIDE}. */
	public static void write(ByteBuffer buf,
			float x, float y, float z,
			float u, float v,
			int r, int g, int b, int a,
			float blockLight, float skyLight,
			float nx, float ny, float nz,
			int material,
			int tintR, int tintG, int tintB, int tintW,
			float ambientOcclusion, float swayWeight) {
		buf.putShort((short) quantizePos(x));
		buf.putShort((short) quantizePos(y));
		buf.putShort((short) quantizePos(z));
		buf.putShort((short) clampU16(Math.round(u * 65535.0f)));
		buf.putShort((short) clampU16(Math.round(v * 65535.0f)));
		buf.put((byte) (r & 0xFF));
		buf.put((byte) (g & 0xFF));
		buf.put((byte) (b & 0xFF));
		buf.put((byte) (a & 0xFF));
		// Light is stored at 1/16-level precision (level × 16, 0..240) so per-vertex smooth lighting can
		// carry fractional levels; the VS divides by 16 again. The lightmap is LINEAR-filtered so these
		// interpolate into soft transitions instead of hard per-block steps.
		buf.put((byte) Math.round(Math.max(0f, Math.min(15f, blockLight)) * 16f));
		buf.put((byte) Math.round(Math.max(0f, Math.min(15f, skyLight)) * 16f));
		buf.put((byte) clampI8(Math.round(nx * 127.0f)));
		buf.put((byte) clampI8(Math.round(ny * 127.0f)));
		buf.put((byte) clampI8(Math.round(nz * 127.0f)));
		buf.put((byte) (material & 0xFF));
		// Chromaticity only: scalar block light remains the sole local-indirect energy owner.
		buf.put((byte) (tintR & 0xFF));
		buf.put((byte) (tintG & 0xFF));
		buf.put((byte) (tintB & 0xFF));
		buf.put((byte) (tintW & 0xFF));
		buf.put((byte) Math.round(Math.max(0f, Math.min(1f, ambientOcclusion)) * 255f));
		buf.put((byte) Math.round(Math.max(0f, Math.min(1f, swayWeight)) * 255f));
		buf.putShort((short) 0);
	}

	private static int quantizePos(float coord) {
		return clampU16(Math.round((coord + POS_BIAS) * POS_SCALE));
	}

	public static float decodePos(int u16) {
		return (u16 & 0xFFFF) / POS_SCALE - POS_BIAS;
	}

	public static float decodeUv(int u16) {
		return (u16 & 0xFFFF) / 65535.0f;
	}

	public static float decodeNormal(int i8) {
		return ((byte) i8) / 127.0f;
	}

	private static int clampU16(int v) {
		return v < 0 ? 0 : (v > 0xFFFF ? 0xFFFF : v);
	}

	private static int clampI8(int v) {
		return v < -127 ? -127 : (v > 127 ? 127 : v);
	}

	/** Standalone self-test (Phase 1.4). Run: javac TerrainVertex.java && java ...TerrainVertex */
	public static void main(String[] args) {
		ByteBuffer buf = ByteBuffer.allocate(STRIDE).order(ByteOrder.LITTLE_ENDIAN);
		float x = 3.25f, y = 1.5f, z = 15.999f, u = 0.5f, v = 0.25f, nx = 0f, ny = 1f, nz = 0f;
		int r = 10, g = 20, b = 30, a = 255, bl = 14, sl = 7, mat = 3;
		write(buf, x, y, z, u, v, r, g, b, a, bl, sl, nx, ny, nz, mat, 255, 160, 64, 128, 0.75f, 0.5f);
		buf.flip();

		float dx = decodePos(buf.getShort(0)), dy = decodePos(buf.getShort(2)), dz = decodePos(buf.getShort(4));
		float du = decodeUv(buf.getShort(6)), dv = decodeUv(buf.getShort(8));
		int dr = buf.get(10) & 0xFF, dg = buf.get(11) & 0xFF, db = buf.get(12) & 0xFF, da = buf.get(13) & 0xFF;
		int dbl = buf.get(14) & 0xFF, dsl = buf.get(15) & 0xFF;
		float dnx = decodeNormal(buf.get(16)), dny = decodeNormal(buf.get(17)), dnz = decodeNormal(buf.get(18));
		int dmat = buf.get(19) & 0xFF;

		boolean ok = true;
		ok &= near(dx, x, 1f / 256) && near(dy, y, 1f / 256) && near(dz, z, 1f / 256);
		ok &= near(du, u, 1f / 65535) && near(dv, v, 1f / 65535);
		ok &= dr == r && dg == g && db == b && da == a && dbl == bl * 16 && dsl == sl * 16;
		ok &= (buf.get(20) & 0xFF) == 255 && (buf.get(23) & 0xFF) == 128;
		ok &= (buf.get(OFF_AO) & 0xFF) == 191 && (buf.get(OFF_SWAY) & 0xFF) == 128;
		ok &= near(dnx, nx, 1f / 127) && near(dny, ny, 1f / 127) && near(dnz, nz, 1f / 127) && dmat == mat;

		System.out.printf("stride=%d  pos(%.4f,%.4f,%.4f) uv(%.4f,%.4f) rgba(%d,%d,%d,%d) light(%d,%d) n(%.3f,%.3f,%.3f) mat=%d%n",
				STRIDE, dx, dy, dz, du, dv, dr, dg, db, da, dbl, dsl, dnx, dny, dnz, dmat);
		System.out.println(ok ? "SELF-TEST PASS" : "SELF-TEST FAIL");
		if (!ok) {
			System.exit(1);
		}
	}

	private static boolean near(float got, float want, float tol) {
		return Math.abs(got - want) <= tol + 1e-6f;
	}
}
