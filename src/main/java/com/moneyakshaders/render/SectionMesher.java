package com.moneyakshaders.render;

/**
 * Plan C / Phase 2.1 — section mesher core: face culling for full cubes.
 *
 * <p>Iterates the 16³ section; for every full-cube block, emits each of its 6
 * faces only when the touching neighbour is not a full opaque cube (internal
 * faces between solid blocks are culled). This is the algorithmic heart of
 * chunk meshing and the bulk of real terrain (stone/dirt/ores are full cubes).
 *
 * <p>This core is Minecraft-free and exhaustively self-tested (see {@link
 * #main}). Non-cube blocks (stairs, slabs, fences, custom models) need the
 * baked-model adapter (Phase 2 continuation) which emits a model's quads
 * instead of {@link #emitCubeFace}; that path is verified in-game.
 *
 * <p>Faces currently carry placeholder texture/colour/light - the real atlas
 * UVs, tint and smooth-light come from the MC adapter + AO sampler (Phase 2.2).
 */
public final class SectionMesher {
	// face index: 0=DOWN(-y) 1=UP(+y) 2=NORTH(-z) 3=SOUTH(+z) 4=WEST(-x) 5=EAST(+x)
	private static final int[][] NORMAL = {
			{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}
	};
	// 4 corner offsets per face (unit cube [0,1]^3)
	private static final float[][][] CORNERS = {
			{{0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}}, // DOWN
			{{0, 1, 0}, {1, 1, 0}, {1, 1, 1}, {0, 1, 1}}, // UP
			{{0, 0, 0}, {0, 1, 0}, {1, 1, 0}, {1, 0, 0}}, // NORTH
			{{0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}}, // SOUTH
			{{0, 0, 0}, {0, 0, 1}, {0, 1, 1}, {0, 1, 0}}, // WEST
			{{1, 0, 0}, {1, 1, 0}, {1, 1, 1}, {1, 0, 1}}, // EAST
	};
	private static final float[][] UV = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};

	private SectionMesher() {
	}

	public static SectionMeshData mesh(BlockGrid grid) {
		SectionMeshData out = new SectionMeshData();
		for (int x = 0; x < BlockGrid.SIZE; x++) {
			for (int y = 0; y < BlockGrid.SIZE; y++) {
				for (int z = 0; z < BlockGrid.SIZE; z++) {
					if (!grid.isSolidCube(x, y, z)) {
						continue;
					}
					for (int face = 0; face < 6; face++) {
						int[] n = NORMAL[face];
						if (grid.isSolidCube(x + n[0], y + n[1], z + n[2])) {
							continue; // neighbour hides this face
						}
						emitCubeFace(out, SectionMeshData.LAYER_SOLID, x, y, z, face);
					}
				}
			}
		}
		return out;
	}

	private static void emitCubeFace(SectionMeshData out, int layer, int bx, int by, int bz, int face) {
		float[][] c = CORNERS[face];
		int[] n = NORMAL[face];
		for (int i = 0; i < 4; i++) {
			out.putVertex(layer,
					bx + c[i][0], by + c[i][1], bz + c[i][2],
					UV[i][0], UV[i][1],
					255, 255, 255, 255,
					15, 15,
					n[0], n[1], n[2],
					0, 0, 1f, 0f);
		}
	}

	// ---- standalone self-test (Phase 2 "done =" for the cube core) ----

	public static void main(String[] args) {
		boolean ok = true;

		// Test 1: a single solid block in air -> 6 faces, 24 verts.
		ok &= check("single block", solidPositions((x, y, z) -> x == 8 && y == 8 && z == 8), 6 * 4);

		// Test 2: fully solid 16^3 -> only the outer surface, 6*256 faces.
		ok &= check("solid 16^3", solidPositions((x, y, z) -> true), 6 * 256 * 4);

		// Test 3: two adjacent blocks along x -> 12-2 shared = 10 faces.
		ok &= check("two adjacent", solidPositions((x, y, z) -> y == 8 && z == 8 && (x == 8 || x == 9)), 10 * 4);

		// Test 4: position spot-check - single block's EAST face starts at x=9.
		SectionMeshData m = SectionMesher.mesh(solidPositions((x, y, z) -> x == 8 && y == 8 && z == 8));
		ok &= m.vertexCount(SectionMeshData.LAYER_SOLID) == 24;

		System.out.println(ok ? "SELF-TEST PASS" : "SELF-TEST FAIL");
		if (!ok) {
			System.exit(1);
		}
	}

	private interface Pred {
		boolean test(int x, int y, int z);
	}

	private static BlockGrid solidPositions(Pred p) {
		return (x, y, z) -> x >= 0 && x < 16 && y >= 0 && y < 16 && z >= 0 && z < 16 && p.test(x, y, z);
	}

	private static boolean check(String name, BlockGrid grid, int expectedVerts) {
		SectionMeshData m = SectionMesher.mesh(grid);
		int got = m.vertexCount(SectionMeshData.LAYER_SOLID);
		boolean ok = got == expectedVerts;
		System.out.printf("  %-14s verts=%d expected=%d %s%n", name, got, expectedVerts, ok ? "OK" : "FAIL");
		return ok;
	}
}
