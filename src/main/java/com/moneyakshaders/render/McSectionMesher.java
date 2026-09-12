package com.moneyakshaders.render;

import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;

import net.minecraft.block.BlockState;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.render.model.BlockModelPart;
import net.minecraft.client.render.model.BlockStateModel;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockRenderView;
import net.minecraft.world.biome.ColorResolver;
import org.joml.Vector3fc;

import com.moneyakshaders.MoneyakShadersConfig;
import com.moneyakshaders.client.DynamicLightSources;

/**
 * Plan C / Phase 2.1b-ii — real meshing through Minecraft's baked models.
 *
 * <p>Phase 2.2 additions: direction-based face brightness (MC vanilla values)
 * and per-vertex ambient occlusion baked directly into vertex RGB, so the
 * shader needs no directional math — just {@code albedo × vColor × lightmap}.
 *
 * <p>AO formula: for each vertex corner on an axis-aligned face, check the 3
 * adjacent blocks (2 sides + diagonal). Count of solid occluders → AO factor
 * from MC's brightness table {1.0, 0.8, 0.6, 0.4}. Direction brightness ×
 * AO is multiplied into the per-vertex r/g/b before upload.
 */
public final class McSectionMesher {
    private static final Direction[] DIRECTIONS = Direction.values();
	private static final java.util.concurrent.atomic.AtomicInteger BLOCK_MESH_FAILURES =
			new java.util.concurrent.atomic.AtomicInteger();
	private static final java.util.concurrent.atomic.AtomicInteger EXPOSED_EMPTY_MESHES =
			new java.util.concurrent.atomic.AtomicInteger();
	/** Public equivalent of BiomeColors' private water resolver, with stable identity for tint caches. */
	private static final ColorResolver WATER_COLOR = (biome, x, z) -> biome.getWaterColor();

    /**
     * Meshing runs serially per executor worker. Keeping its temporary state thread-local removes
     * ~24 KiB of short-lived connectivity arrays per section during a server chunk burst.
     */
    private static final class WorkerScratch {
        final Random random = Random.create();
        final BlockPos.Mutable pos = new BlockPos.Mutable();
        final BlockPos.Mutable neighbour = new BlockPos.Mutable();
		// Fluid meshing calls several corner/height helpers per cell. Reusing these avoids two
		// BlockPos allocations for every water or lava block in a streamed section.
		final BlockPos.Mutable fluidCorner = new BlockPos.Mutable();
		final BlockPos.Mutable fluidHeight = new BlockPos.Mutable();
        final boolean[] opaque = new boolean[4096];
        final boolean[] visited = new boolean[4096];
        final int[] floodQueue = new int[4096];
		// BlockState instances are canonical. A normal terrain section contains thousands of repeats,
		// so resolve its deterministic baked parts/layer/material once per distinct state, not once per
		// cell. The map is section-local: a resource reload can never leave stale model references here.
		final Map<BlockState, RenderTemplate> renderTemplates = new IdentityHashMap<>(64);
    }

	private record RenderTemplate(BlockModelPart[] parts, int layer, int material, boolean model) {
	}

	private static final RenderTemplate NO_MODEL = new RenderTemplate(new BlockModelPart[0], SectionMeshData.LAYER_SOLID,
			0, false);

    private static final ThreadLocal<WorkerScratch> WORKER_SCRATCH = ThreadLocal.withInitial(WorkerScratch::new);

    // MC vanilla direction brightness (AmbientOcclusionCalculator values without AO)
    private static final float DIR_UP    = 1.0f;
    private static final float DIR_DOWN  = 0.5f;
    private static final float DIR_NS    = 0.8f; // NORTH, SOUTH
    private static final float DIR_EW    = 0.6f; // EAST, WEST
    // null-face (cross-shaped plants etc.) — full brightness, no AO
    private static final float DIR_NULL  = 1.0f;

    // MC's AO brightness table: index = number of solid occluders (0-3)
    private static final float[] AO_TABLE = {1.0f, 0.8f, 0.6f, 0.4f};

    // Per-vertex material tag (TerrainVertex OFF_MATERIAL byte). Lets the shader treat
    // certain blocks specially — here: leaves, which the shader renders fully opaque past a
    // configurable distance to kill the alpha-test/mipmap shimmer of far foliage.
    public static final int MAT_DEFAULT = 0;
    public static final int MAT_LEAVES = 1;  // far-opaque + wind sway
    public static final int MAT_PLANT = 2;   // grass/flowers/crops — wind sway
    public static final int MAT_ORE = 3;     // glow-in-the-dark ores (except coal)
    public static final int MAT_WATER = 4;
    public static final int MAT_LEAVES_DEEP = 5; // canopy layer 3+ → cutout holes fully closed
    public static final int MAT_LEAVES_MID = 6;  // canopy layer 2 → cutout holes partly closed
    public static final int MAT_NO_SHADOW = 7;    // casts no shadow at all (e.g. leaf_litter)
    public static final int MAT_EMISSIVE_SWAY = 8; // hanging lantern: no shadow (it EMITS light) + wind sway
    public static final int MAT_ICE = 9; // stable derivative-filtered ice in the depth-writing terrain pass
    /** Water directly over ice needs earlier texture-footprint stabilisation: the bright substrate
     * makes each rotated flowing-water cell visible long before ordinary water aliases. */
    public static final int MAT_WATER_ICE = 10;
    public static final int MAT_HANGING_CHAIN = 11; // top-pinned vertical chain, continuous down to its payload
    /** Full-cube luminous geometry. The point-shadow pass uses this ownership tag to remove only the
     * emitter's own faces; a position-only AABB cut also clipped adjacent wall/floor fragments on the
     * shared boundary and produced a persistent bright seam. */
    public static final int MAT_EMISSIVE_SOLID = 12;
    /** The lower boundary of a real air pocket below a non-waterloggable block entity. */
    public static final int MAT_WATER_POCKET = 13;
	/** Thin glass pane geometry; receives a translucent depth bias against arbitrary model neighbours. */
	public static final int MAT_GLASS_PANE = 14;

    public static boolean isWaterMaterial(int material) {
        return material == MAT_WATER || material == MAT_WATER_ICE || material == MAT_WATER_POCKET;
    }

    /**
     * How deep a leaf block sits inside the canopy, 1..3. The shader closes that leaf's cutout holes
     * progressively with this value, so foliage thickens layer by layer and stops being see-through
     * at layer 3.
     *
     * <p>The old test was a single boolean demanding that EVERY block within 2 in ALL six directions
     * be leaf/solid. Almost nothing on a normal tree passes that, so the whole canopy stayed a
     * one-block cutout shell: you looked through the holes of the near side, out through the holes of
     * the far side, and the tree read as a single sparse leaf with nothing inside it.
     *
     * @return 1 when something open is adjacent (the silhouette shell — keeps its crisp cutout),
     *         2 when the nearest opening is two blocks away, 3 when fully enclosed
     */
    private static int leafCanopyDepth(BlockRenderView world, BlockPos pos, BlockPos.Mutable n) {
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        for (int dist = 1; dist <= 2; dist++) {
            for (Direction d : DIRECTIONS) {
                BlockState nb = world.getBlockState(n.set(x + d.getOffsetX() * dist, y + d.getOffsetY() * dist, z + d.getOffsetZ() * dist));
                if (!(nb.getBlock() instanceof net.minecraft.block.LeavesBlock || nb.isOpaqueFullCube())) {
                    return dist;
                }
            }
        }
        return 3;
    }

    // Ores that glow in the dark (request #5) — coal deliberately excluded.
    private static final java.util.Set<net.minecraft.block.Block> GLOWING_ORES = java.util.Set.of(
            net.minecraft.block.Blocks.IRON_ORE, net.minecraft.block.Blocks.DEEPSLATE_IRON_ORE,
            net.minecraft.block.Blocks.GOLD_ORE, net.minecraft.block.Blocks.DEEPSLATE_GOLD_ORE,
            net.minecraft.block.Blocks.NETHER_GOLD_ORE,
            net.minecraft.block.Blocks.DIAMOND_ORE, net.minecraft.block.Blocks.DEEPSLATE_DIAMOND_ORE,
            net.minecraft.block.Blocks.EMERALD_ORE, net.minecraft.block.Blocks.DEEPSLATE_EMERALD_ORE,
            net.minecraft.block.Blocks.LAPIS_ORE, net.minecraft.block.Blocks.DEEPSLATE_LAPIS_ORE,
            net.minecraft.block.Blocks.REDSTONE_ORE, net.minecraft.block.Blocks.DEEPSLATE_REDSTONE_ORE,
            net.minecraft.block.Blocks.COPPER_ORE, net.minecraft.block.Blocks.DEEPSLATE_COPPER_ORE,
            net.minecraft.block.Blocks.NETHER_QUARTZ_ORE);

    private static int materialOf(BlockState state, boolean fullCubeGeometry) {
        net.minecraft.block.Block b = state.getBlock();
		// A pane can meet an arbitrarily shaped/tall neighbour (walls and resource-pack
		// models included). Keep it distinguishable in the fragment shader so its thin
		// caps never fight the neighbour's real surface.
		if (b instanceof net.minecraft.block.PaneBlock) return MAT_GLASS_PANE;
        if (b instanceof net.minecraft.block.LeafLitterBlock) return MAT_NO_SHADOW; // flat ground litter — no shadow
        // LIGHT-EMITTING non-full-cube blocks (torch, lantern, campfire, end rod…) cast NO shadow: a
        // glowing model throwing a long dark streak from another light reads absurd (user report). Full
		// geometric cubes (glowstone, sea lantern) still cast — they are walls. isOpaqueFullCube is the
		// wrong test here because luminous cubes may be non-opaque to the light engine while their model
		// still fills the block. Hanging lanterns keep
        // their wind sway via the dedicated material.
		if (state.getLuminance() >= 8) {
			if (fullCubeGeometry) return MAT_EMISSIVE_SOLID;
            if (b instanceof net.minecraft.block.LanternBlock) {
                try {
                    return state.get(net.minecraft.block.LanternBlock.HANGING) ? MAT_EMISSIVE_SWAY : MAT_NO_SHADOW;
                } catch (Throwable ignored) {
                    return MAT_NO_SHADOW;
                }
            }
            return MAT_NO_SHADOW;
        }
        if (b instanceof net.minecraft.block.LeavesBlock) return MAT_LEAVES;
        if (b instanceof net.minecraft.block.IceBlock) return MAT_ICE;
        if (b instanceof net.minecraft.block.PlantBlock
                || b instanceof net.minecraft.block.VineBlock
                || b instanceof net.minecraft.block.SugarCaneBlock) return MAT_PLANT;
        if (GLOWING_ORES.contains(b)) return MAT_ORE;
        return MAT_DEFAULT;
    }

    // fluid atlas sprite UVs (set once from the renderer when the block atlas is ready)
    private static float waterU0, waterU1, waterV0, waterV1; // water_still (top face)
    private static float wfU0, wfU1, wfV0, wfV1;            // water_flow  (side faces)
    private static float wovU0, wovU1, wovV0, wovV1;        // water_overlay (sides vs glass/leaves)
    private static float lavaU0, lavaU1, lavaV0, lavaV1;    // lava_still  (top face)
    private static float lfU0, lfU1, lfV0, lfV1;            // lava_flow   (side faces)

    private McSectionMesher() {
    }

    public static void setWaterSprite(float u0, float u1, float v0, float v1) {
        waterU0 = u0; waterU1 = u1; waterV0 = v0; waterV1 = v1;
    }

    public static void setWaterFlowSprite(float u0, float u1, float v0, float v1) {
        wfU0 = u0; wfU1 = u1; wfV0 = v0; wfV1 = v1;
    }

    public static void setWaterOverlaySprite(float u0, float u1, float v0, float v1) {
        wovU0 = u0; wovU1 = u1; wovV0 = v0; wovV1 = v1;
    }

    public static void setLavaSprite(float u0, float u1, float v0, float v1) {
        lavaU0 = u0; lavaU1 = u1; lavaV0 = v0; lavaV1 = v1;
    }

    public static void setLavaFlowSprite(float u0, float u1, float v0, float v1) {
        lfU0 = u0; lfU1 = u1; lfV0 = v0; lfV1 = v1;
    }

    public static SectionMeshData mesh(BlockRenderView world, BlockRenderManager blockRenderManager,
            BlockColors blockColors, int baseX, int baseY, int baseZ) {
        SectionMeshData out = new SectionMeshData();
        WorkerScratch worker = WORKER_SCRATCH.get();
        Random random = worker.random;
        BlockPos.Mutable pos = worker.pos;
        BlockPos.Mutable scratch = worker.neighbour; // reused for neighbour queries
		BlockPos.Mutable fluidCorner = worker.fluidCorner;
		BlockPos.Mutable fluidHeight = worker.fluidHeight;
        boolean[] opaque = worker.opaque; // [(x<<8)|(y<<4)|z] — for occlusion connectivity below
		worker.renderTemplates.clear();

        // Custom biome-tint blend (replaces vanilla's per-block 15×15 box average that spiked on chunk
        // load). Only the colour calls below use it; the block/fluid/light queries stay on `world`.
        // The custom terrain renderer bakes tint into its vertices, so it must use one deterministic
        // section-wide colour source even when the optional global vanilla replacement is disabled.
        // Delegating to ClientWorld's position cache here allowed sections meshed while neighbouring
        // biome data was arriving to retain different, chunk-shaped colour results.
		BlockRenderView tintView = BiomeBlurTint.wrap(world, baseX, baseY, baseZ);
		int nonAirBlocks = 0;
		int modelBlocks = 0;
		int blockEntityBlocks = 0;

        // Baked coloured block-light: gather every luminous block that can reach this section once,
        // then bake a per-vertex hue+strength into the mesh (per-thread state; see BakedLightTint).
        if (MoneyakShadersConfig.get().terrainPointLights) {
            BakedLightTint.begin(world, baseX, baseY, baseZ);
        } else {
            BakedLightTint.begin(null, baseX, baseY, baseZ); // clears this thread's source list
        }

        for (int x = 0; x < BlockGrid.SIZE; x++) {
            for (int y = 0; y < BlockGrid.SIZE; y++) {
                for (int z = 0; z < BlockGrid.SIZE; z++) {
					pos.set(baseX + x, baseY + y, baseZ + z);
					BlockState state = world.getBlockState(pos);
					int voxelIndex = (x << 8) | (y << 4) | z;
					boolean closedLightBarrier = (state.getBlock() instanceof net.minecraft.block.DoorBlock
							|| state.getBlock() instanceof net.minecraft.block.TrapdoorBlock)
							&& !state.get(net.minecraft.state.property.Properties.OPEN);
					// Dynamic point lights use a compact one-cell occupancy volume. Closed doors and
					// trapdoors are thin rather than opaque full cubes, but treating them as empty made their
					// entire cell transmit point shadows. Conservatively close that cell only while the block
					// is closed; an open doorway remains transmissive.
					opaque[voxelIndex] = state.isOpaqueFullCube() || closedLightBarrier;
					if (opaque[voxelIndex]) out.markOpaqueVoxel(voxelIndex);
                    if (state.isAir()) {
                        continue;
                    }
					nonAirBlocks++;
					if (state.getRenderType() == net.minecraft.block.BlockRenderType.MODEL) modelBlocks++;
					if (state.hasBlockEntity()) blockEntityBlocks++;
					// Carry the section's own strong light blocks and luminous fluids to the render thread. Point-shadow
					// selection can then query already-published section metadata instead of reading a 31^3
					// live-world cube in render-frame slices (the source of distance pop and CPU spikes).
					int luminance = state.getLuminance();
					if (luminance >= 8) {
						boolean solidEmitter = state.isFullCube(world, pos);
						int exposedFaceMask = solidEmitter ? emitterFaceMask(world, pos, scratch) : 0x3F;
						float[] lightColor = DynamicLightSources.blockColor(state.getBlock());
						float peak = Math.max(lightColor[0], Math.max(lightColor[1], lightColor[2]));
						if (peak > 0.001f) {
							out.addPlacedLight(x, y, z, luminance,
									lightColor[0] / peak, lightColor[1] / peak, lightColor[2] / peak,
									solidEmitter, exposedFaceMask);
						}
					}
                    try {
                        meshBlock(out, world, tintView, blockRenderManager, blockColors, random,
							worker.renderTemplates, state, pos, scratch, x, y, z);
					} catch (Throwable failure) {
						// One bad block must not break the whole section, but never hide systemic worker/model
						// failures: if every surface block throws, the old silent catch uploads an apparently
						// valid empty mesh and leaves a permanent white 16x16 hole.
						int failures = BLOCK_MESH_FAILURES.incrementAndGet();
						if (failures <= 12 || failures % 1000 == 0) {
							com.moneyakshaders.MoneyakShaders.LOGGER.warn(
									"[Plan C/mesh] block failure #{} at {}/{}/{} state={}",
									failures, pos.getX(), pos.getY(), pos.getZ(), state, failure);
						}
                    }
                    // Placed light-emitting blocks (terrain point lights) are collected live from the
                    // world by DynamicLightSources each tick, not baked into the mesh — so a freshly
                    // placed torch lights up immediately instead of waiting for this section's re-mesh.
                    FluidState fluid = state.getFluidState();
                    // Blocks with a block entity (chest, …) are drawn by their own renderer, which fills
                    // the cell — meshing the cell's water on top of that made a bright closed water cube
                    // that hid the chest. Skip the OWN-cell water; neighbouring water still treats this
                    // cell as water (effFluid) so there's no air pocket.
                    if (!fluid.isEmpty() && !state.hasBlockEntity()) {
                        try {
                            if (fluid.isIn(FluidTags.WATER)) {
                                meshFluid(out, world, tintView, state, fluid, pos, scratch, fluidCorner, fluidHeight, x, y, z, false);
                            } else if (fluid.isIn(FluidTags.LAVA)) {
                                meshFluid(out, world, tintView, state, fluid, pos, scratch, fluidCorner, fluidHeight, x, y, z, true);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        }
		// A section made only of ENTITYBLOCK_ANIMATED/INVISIBLE states is legitimately absent from the
		// terrain mesh: its geometry comes from the block-entity renderer. The old non-air-only test
		// reported those as scary "exposed empty" failures on RP servers (sign/head/display warehouses).
		if (modelBlocks > 0 && out.vertexCount(SectionMeshData.LAYER_SOLID) == 0
				&& out.vertexCount(SectionMeshData.LAYER_CUTOUT) == 0
				&& out.vertexCount(SectionMeshData.LAYER_TRANSLUCENT) == 0) {
			boolean exposedToAir = false;
			BlockState exposedState = null;
			for (int x = 0; x < BlockGrid.SIZE && !exposedToAir; x++) {
				for (int y = 0; y < BlockGrid.SIZE && !exposedToAir; y++) {
					for (int z = 0; z < BlockGrid.SIZE; z++) {
						pos.set(baseX + x, baseY + y, baseZ + z);
						BlockState exposed = world.getBlockState(pos);
						if (!exposed.isAir() && exposed.getRenderType() == net.minecraft.block.BlockRenderType.MODEL
								&& world.getBlockState(scratch.set(baseX + x, baseY + y + 1, baseZ + z)).isAir()) {
							exposedToAir = true;
							exposedState = exposed;
							break;
						}
					}
				}
			}
			if (exposedToAir) {
				int empty = EXPOSED_EMPTY_MESHES.incrementAndGet();
				if (empty <= 32 || empty % 250 == 0) {
					int missingMask = world instanceof SectionInputSnapshot input ? input.missingColumnMask : -1;
					com.moneyakshaders.MoneyakShaders.LOGGER.warn(
							"[Plan C/mesh] exposed empty mesh #{} section={}/{}/{} nonAir={} model={} blockEntity={} sample={} missingMask=0x{}",
							empty, baseX >> 4, baseY >> 4, baseZ >> 4, nonAirBlocks, modelBlocks,
							blockEntityBlocks, exposedState, Integer.toHexString(missingMask));
				}
			}
		}
        // Phase 4.2: scan the 6 boundary faces for non-opaque blocks so the BFS knows
        // which section faces can be entered. Short-circuits once all 6 faces are open.
        int last = BlockGrid.SIZE - 1;
        for (int i = 0; i < BlockGrid.SIZE && out.openFaces != 63; i++) {
            for (int j = 0; j < BlockGrid.SIZE && out.openFaces != 63; j++) {
				// All 4096 opacity values were already captured above. Re-querying the snapshot on six
				// faces added up to 1536 redundant virtual lookups to every streamed section.
                if ((out.openFaces & 1)  == 0 && !opaque[(i << 4) | j]) out.openFaces |= 1;                    // WEST
                if ((out.openFaces & 2)  == 0 && !opaque[(last << 8) | (i << 4) | j]) out.openFaces |= 2;     // EAST
                if ((out.openFaces & 4)  == 0 && !opaque[(i << 8) | j]) out.openFaces |= 4;                    // DOWN
                if ((out.openFaces & 8)  == 0 && !opaque[(i << 8) | (last << 4) | j]) out.openFaces |= 8;     // UP
                if ((out.openFaces & 16) == 0 && !opaque[(i << 8) | (j << 4)]) out.openFaces |= 16;           // NORTH
                if ((out.openFaces & 32) == 0 && !opaque[(i << 8) | (j << 4) | last]) out.openFaces |= 32;    // SOUTH
            }
        }
		out.visibility = computeVisibility(worker);
        return out;
    }

	/** Which faces of a full luminous cube can actually emit into non-opaque space. The bit order is
	 * shared with the point cubemap: +X,-X,+Y,-Y,+Z,-Z. */
	private static int emitterFaceMask(BlockRenderView world, BlockPos pos, BlockPos.Mutable scratch) {
		int mask = 0;
		for (Direction direction : DIRECTIONS) {
			BlockState neighbour = world.getBlockState(scratch.set(
					pos.getX() + direction.getOffsetX(), pos.getY() + direction.getOffsetY(),
					pos.getZ() + direction.getOffsetZ()));
			if (neighbour.isOpaqueFullCube()) {
				continue;
			}
			mask |= switch (direction) {
				case EAST -> 1 << 0;
				case WEST -> 1 << 1;
				case UP -> 1 << 2;
				case DOWN -> 1 << 3;
				case SOUTH -> 1 << 4;
				case NORTH -> 1 << 5;
			};
		}
		return mask;
	}

    /**
     * Minecraft-style occlusion connectivity: flood-fill the section's transparent space and record,
     * for every pair of section faces, whether sight can pass between them. Result is a directed 6×6
     * matrix packed as bit {@code from*6+to} of a long (face order = Direction.ordinal()).
     *
     * @param opaque per-cell {@code isOpaqueFullCube} indexed {@code (x<<8)|(y<<4)|z}
     */
    private static long computeVisibility(WorkerScratch worker) {
        boolean[] opaque = worker.opaque;
        boolean[] visited = worker.visited;
        int[] queue = worker.floodQueue;
        java.util.Arrays.fill(visited, false);
        long vis = 0L;
        for (int start = 0; start < 4096; start++) {
            if (opaque[start] || visited[start]) {
                continue;
            }
            int sx = (start >> 8) & 15, sy = (start >> 4) & 15, sz = start & 15;
            if (sx != 0 && sx != 15 && sy != 0 && sy != 15 && sz != 0 && sz != 15) {
                continue; // interior air pocket touches no face — can't connect any pair
            }
            int faces = 0;
            int head = 0, tail = 0;
            queue[tail++] = start;
            visited[start] = true;
            while (head < tail) {
                int c = queue[head++];
                int x = (c >> 8) & 15, y = (c >> 4) & 15, z = c & 15;
                if (y == 0)  faces |= 1;       // DOWN  (ordinal 0)
                if (y == 15) faces |= 1 << 1;  // UP    (1)
                if (z == 0)  faces |= 1 << 2;  // NORTH (2)
                if (z == 15) faces |= 1 << 3;  // SOUTH (3)
                if (x == 0)  faces |= 1 << 4;  // WEST  (4)
                if (x == 15) faces |= 1 << 5;  // EAST  (5)
                if (x > 0)  { int n = c - 256; if (!opaque[n] && !visited[n]) { visited[n] = true; queue[tail++] = n; } }
                if (x < 15) { int n = c + 256; if (!opaque[n] && !visited[n]) { visited[n] = true; queue[tail++] = n; } }
                if (y > 0)  { int n = c - 16;  if (!opaque[n] && !visited[n]) { visited[n] = true; queue[tail++] = n; } }
                if (y < 15) { int n = c + 16;  if (!opaque[n] && !visited[n]) { visited[n] = true; queue[tail++] = n; } }
                if (z > 0)  { int n = c - 1;   if (!opaque[n] && !visited[n]) { visited[n] = true; queue[tail++] = n; } }
                if (z < 15) { int n = c + 1;   if (!opaque[n] && !visited[n]) { visited[n] = true; queue[tail++] = n; } }
            }
            for (int a = 0; a < 6; a++) {
                if ((faces & (1 << a)) == 0) {
                    continue;
                }
                for (int b = 0; b < 6; b++) {
                    if ((faces & (1 << b)) != 0) {
                        vis |= 1L << (a * 6 + b); // directed; both (a,b) and (b,a) get set as a/b iterate
                    }
                }
            }
        }
        return vis;
    }

    private static int blockRenderLayer(BlockState state) {
        // Glass families need alpha blending. Ice is deliberately kept in the depth-writing terrain
        // pass: a frozen ocean contains thousands of visible ice quads and moving all of them into
        // sorted transparency creates both ordering flicker and a large camera-motion CPU spike.
        // Its distant texture is instead stabilised by MAT_ICE in the shader.
        net.minecraft.block.Block b = state.getBlock();
        if (b instanceof net.minecraft.block.TransparentBlock
                || b instanceof net.minecraft.block.StainedGlassPaneBlock
                || b == net.minecraft.block.Blocks.GLASS_PANE) {
            return SectionMeshData.LAYER_TRANSLUCENT;
        }
        return SectionMeshData.LAYER_SOLID;
    }

    private static void meshBlock(SectionMeshData out, BlockRenderView world, BlockRenderView tintView, BlockRenderManager brm,
            BlockColors blockColors, Random random, Map<BlockState, RenderTemplate> templates,
			BlockState state, BlockPos pos, BlockPos.Mutable scratch,
            int x, int y, int z) {
        // Vanilla only meshes MODEL-rendered states. Meshing INVISIBLE/animated ones (moving_piston,
        // block 36 tricks servers use for decor) draws a placeholder model INSIDE the block-entity's
        // animated render → coplanar z-fighting ("piston head flickering" report).
		RenderTemplate template = templates.get(state);
		if (template == null) {
			if (state.getRenderType() != net.minecraft.block.BlockRenderType.MODEL) {
				template = NO_MODEL;
			} else {
				BlockStateModel model = brm.getModel(state);
				random.setSeed(42L);
				template = new RenderTemplate(model.getParts(random).toArray(BlockModelPart[]::new), blockRenderLayer(state),
						materialOf(state, state.isFullCube(world, pos)), true);
			}
			templates.put(state, template);
		}
        if (!template.model()) {
            return;
        }
		BlockModelPart[] parts = template.parts();
		int layer = template.layer();
		int material = template.material();
        if (material == MAT_LEAVES) {
            // Grade the leaf by how far it sits inside the canopy: the shell keeps its crisp cutout
            // silhouette, layer 2 closes the small holes, layer 3+ closes them all.
            int depth = leafCanopyDepth(world, pos, scratch);
            if (depth == 2) {
                material = MAT_LEAVES_MID;
            } else if (depth >= 3) {
                material = MAT_LEAVES_DEEP;
            }
        }
        // Vines attached to a SOLID (non-leaf) block — e.g. on a tree log — must NOT sway: the wind
        // displacement was pushing the vine geometry into the log it's stuck to, making the visible
        // part disappear into the trunk. Vines on leaves stay MAT_PLANT (sway in unison with leaves).
        if (material == MAT_PLANT && state.getBlock() instanceof net.minecraft.block.VineBlock) {
            for (Direction dir : DIRECTIONS) {
                BlockState n = world.getBlockState(scratch.set(pos.getX() + dir.getOffsetX(),
                        pos.getY() + dir.getOffsetY(), pos.getZ() + dir.getOffsetZ()));
                if (n.isOpaqueFullCube() && !(n.getBlock() instanceof net.minecraft.block.LeavesBlock)) {
                    material = MAT_DEFAULT;
                    break;
                }
            }
        }
        // Chains: hang/sway only when VERTICAL (axis = Y) and NOT connected to the ground (block
        // below is air / non-solid). A dedicated material is necessary: plant sway is rooted at the
        // bottom, while a hanging chain must do the exact opposite and keep its top attachment fixed.
        if (material == MAT_DEFAULT && state.getBlock() instanceof net.minecraft.block.ChainBlock) {
            try {
                if (state.get(net.minecraft.state.property.Properties.AXIS) == net.minecraft.util.math.Direction.Axis.Y) {
                    BlockState below = world.getBlockState(scratch.set(pos.getX(), pos.getY() - 1, pos.getZ()));
                    if (below.isAir() || !below.isOpaqueFullCube()) {
                        material = MAT_HANGING_CHAIN;
                    }
                }
            } catch (Throwable ignored) {
                // bad state — leave as MAT_DEFAULT
            }
        }
		for (int partIndex = 0; partIndex < parts.length; partIndex++) {
			BlockModelPart part = parts[partIndex];
			boolean pane = state.getBlock() instanceof net.minecraft.block.PaneBlock;
            for (Direction dir : DIRECTIONS) {
                scratch.set(pos.getX() + dir.getOffsetX(), pos.getY() + dir.getOffsetY(), pos.getZ() + dir.getOffsetZ());
                BlockState neighbor = world.getBlockState(scratch);
				// Pane models contain a paper-thin top and bottom cap. Unlike a full glass
				// cube, the vanilla culling shape does not hide that cap against a full
				// block above/below, leaving two coplanar translucent/opaque fragments that
				// flicker at the contact edge. The cap is internal in this one case only.
				if (pane && (dir == Direction.UP || dir == Direction.DOWN)
						&& neighbor.isOpaqueFullCube()) continue;
                // Vanilla occlusion test: correctly handles opaque neighbours, partial shapes
                // (slabs, stairs) and same-block invisibility (glass<->glass). The old crude
                // isOpaqueFullCube + same-block checks over-culled faces between dissimilar
                // slabs/stairs, leaving see-through holes.
                // Leaves cull against each other (their culling shape is a full cube), so a canopy came
                // out as a hollow one-block shell — and a cutout shell is see-through from BOTH sides
                // at once, which is why a tree read as a single sparse leaf with sky behind it. Keep
                // the leaf↔leaf faces for the outer two layers so there is real geometry to stack
                // opacity on; layer 3+ is enclosed by that and stays culled, so the extra vertices are
                // bounded to the visible rind instead of the whole canopy volume.
                boolean leafInterior = (material == MAT_LEAVES || material == MAT_LEAVES_MID)
                        && neighbor.getBlock() instanceof net.minecraft.block.LeavesBlock;
                if (!leafInterior && !net.minecraft.block.Block.shouldDrawSide(state, neighbor, dir)) continue;
                int light = packedLight(world, scratch);
                // Deep-canopy leaves render OPAQUE, but the canopy INTERIOR often has light 0 (servers
                // don't light it) → they showed as solid black walls behind the see-through outer leaves.
                // Lift their light toward the lit canopy top by sampling a few blocks upward.
                if (material == MAT_LEAVES_DEEP || material == MAT_LEAVES_MID) {
                    int lu2 = packedLight(world, scratch.set(pos.getX(), pos.getY() + 2, pos.getZ()));
                    int lu4 = packedLight(world, scratch.set(pos.getX(), pos.getY() + 4, pos.getZ()));
                    light = maxLight(maxLight(light, lu2), lu4);
                    scratch.set(pos.getX() + dir.getOffsetX(), pos.getY() + dir.getOffsetY(), pos.getZ() + dir.getOffsetZ());
                }
				emitQuads(out, blockColors, world, tintView, state, pos, scratch,
						part.getQuads(dir), x, y, z, light, 1.0f, layer, material);
            }
            // null-face quads (cross-shaped plants, etc.): no AO, full brightness
            emitQuads(out, blockColors, world, tintView, state, pos, scratch,
                    part.getQuads(null), x, y, z,
                    packedLight(world, pos), DIR_NULL, layer, material);
        }
    }

    private static void emitQuads(SectionMeshData out, BlockColors blockColors, BlockRenderView world, BlockRenderView tintView,
            BlockState state, BlockPos pos, BlockPos.Mutable scratch,
            List<BakedQuad> quads, int bx, int by, int bz, int light, float dirFactor, int layer, int material) {
        int blockLight = light >>> 4 & 15;
        int skyLight = light >>> 20 & 15;
        // Dyed glass is commonly layered to build colour/depth effects. Applying the custom coloured
        // light tint on top of its texture drives those already-saturated colours towards white and
        // destroys the intended composition. Keep vanilla block/sky light and transparency, but leave
        // the custom hue contribution at zero for both full blocks and panes.
        net.minecraft.block.Block block = state.getBlock();
        boolean acceptsColouredLightTint = !(block instanceof net.minecraft.block.StainedGlassBlock)
                && !(block instanceof net.minecraft.block.StainedGlassPaneBlock)
                && !(block instanceof net.minecraft.block.LeavesBlock);
        float lsmooth = MoneyakShadersConfig.get().lightSmoothing / 100.0f; // 0 = flat per-block light
        // Sway anchor: for stacking plants (sugar cane, cactus, …) find the column's ROOT block by
        // walking down through identical blocks. The per-vertex sway weight then grows with height
        // above that root, so the base block's lower half stays pinned to the ground (weight 0) and
        // only the upper part waves. Single-block plants (grass/flowers) root on themselves.
        int plantRootY = Integer.MIN_VALUE;
        if (material == MAT_PLANT) {
            plantRootY = pos.getY();
            net.minecraft.block.Block pb = state.getBlock();
			// Snapshot extraction deliberately keeps only a short visual plant-root tail below a
			// section. Geometry/AO already have their complete one-block halo; do not probe past the
			// immutable input and accidentally treat a tall plant as live-world data.
			for (int s = 0; s < SectionInputSnapshot.BELOW_HALO - 1; s++) {
                if (world.getBlockState(scratch.set(pos.getX(), plantRootY - 1, pos.getZ())).getBlock() != pb) {
                    break;
                }
                plantRootY--;
            }
        }
        int chainTopY = Integer.MIN_VALUE;
        int chainLength = 1;
        boolean lanternContinuesChain = false;
        if (material == MAT_HANGING_CHAIN) {
            int top = pos.getY();
            int bottom = pos.getY();
            // Walk the whole immutable snapshot (the section plus vertical overlap), never mutable
            // ClientWorld. This keeps every segment in an ordinary multi-block chain on one curve.
            for (int s = 0; s < SectionInputSnapshot.BLOCK_HEIGHT - 1; s++) {
                BlockState above = world.getBlockState(scratch.set(pos.getX(), top + 1, pos.getZ()));
                if (!isVerticalChain(above)) break;
                top++;
            }
            for (int s = 0; s < SectionInputSnapshot.BLOCK_HEIGHT - 1; s++) {
                BlockState below = world.getBlockState(scratch.set(pos.getX(), bottom - 1, pos.getZ()));
                if (!isVerticalChain(below)) break;
                bottom--;
            }
            chainTopY = top;
            chainLength = Math.max(1, top - bottom + 1);
        } else if (material == MAT_EMISSIVE_SWAY) {
            lanternContinuesChain = isVerticalChain(
                    world.getBlockState(scratch.set(pos.getX(), pos.getY() + 1, pos.getZ())));
        }
		// BlockModelPart stores these as immutable lists. Enhanced-for allocates an iterator here for
		// every face of every block; during chunk streaming that dominated the complete allocation
		// profile and forced visible GC pauses. RandomAccess/indexing keeps this hot path allocation-free.
		for (int quadIndex = 0, quadCount = quads.size(); quadIndex < quadCount; quadIndex++) {
			BakedQuad quad = quads.get(quadIndex);
			Direction face = quad.face();
			// Pane caps are commonly emitted in the model's null-face list, so the
			// side-level culling in meshBlock cannot see them. Apply the same rule to
			// every concrete baked quad: an UP/DOWN cap touching a full opaque cube is
			// internal and otherwise exactly coplanar with that cube's face.
			if (block instanceof net.minecraft.block.PaneBlock
					&& (face == Direction.UP || face == Direction.DOWN)) {
				BlockState capNeighbor = world.getBlockState(scratch.set(
						pos.getX() + face.getOffsetX(), pos.getY() + face.getOffsetY(),
						pos.getZ() + face.getOffsetZ()));
				if (capNeighbor.isOpaqueFullCube()) continue;
			}
            int r = 255, g = 255, b = 255;
            if (quad.hasTint()) {
                int c = blockColors.getColor(state, tintView, pos, quad.tintIndex());
                r = c >> 16 & 0xFF;
                g = c >> 8 & 0xFF;
                b = c & 0xFF;
            }
            int nx = face == null ? 0 : face.getOffsetX();
            int ny = face == null ? 1 : face.getOffsetY();
            int nz = face == null ? 0 : face.getOffsetZ();
            for (int i = 0; i < 4; i++) {
                Vector3fc p = quad.getPosition(i);
                long uv = quad.getTexcoords(i);
                float u = Float.intBitsToFloat((int) (uv >>> 32));
                float v = Float.intBitsToFloat((int) (uv & 0xFFFFFFFFL));
                // AO + smooth light share the same corner-block fetches, so when smoothing is on they're
                // computed together in ONE pass (vertexShade) instead of two — the per-block reads were
                // the dominant mesh cost. Smooth lighting blends the flat block light toward the average
                // of the non-opaque blocks around the corner (fractional level → soft transitions).
                float ao;
                float vBlockLight = blockLight;
                float vSkyLight = skyLight;
                if (lsmooth > 0f && face != null) {
                    long sh = vertexShade(world, scratch, face, pos.getX(), pos.getY(), pos.getZ(), p.x(), p.y(), p.z());
                    ao = ((sh >>> 16) & 0xFF) / 255f;
                    if (((sh >>> 24) & 1L) != 0L) {
                        float avgBl = ((sh >>> 8) & 0xFF) / 16f;
                        float avgSl = (sh & 0xFF) / 16f;
                        vBlockLight = blockLight + (avgBl - blockLight) * lsmooth;
                        vSkyLight = skyLight + (avgSl - skyLight) * lsmooth;
                    }
                } else {
                    ao = computeAO(world, pos, face, scratch, p.x(), p.y(), p.z());
                }
				// Meshes carry material colour, AO and sway independently.  In particular, neither
				// directional face darkening nor AO may be pre-applied to albedo.
				int vr = r;
				int vg = g;
				int vb = b;
                // Per-vertex sway weight in the (otherwise-unused-for-opaque) alpha byte. The phase is
                // still keyed on world x/z so a stacked column moves in sync (joins stay connected),
                // but the amplitude now ramps with height above the root: the base block's lower half
                // is pinned (weight 0) and the wave grows in over the next block. 255 (full sway) for
                // leaves and everything else, so their behaviour is unchanged.
				int va = 255;
                if (material == MAT_PLANT) {
                    float h = (pos.getY() + p.y()) - plantRootY;          // blocks above the column root
                    float w = Math.max(0f, Math.min(1f, (h - 0.5f) * 1.25f)); // 0 for lower half, →1 over a block
                    va = (int) (w * 255f + 0.5f);
                } else if (material == MAT_HANGING_CHAIN) {
                    // One continuous pendulum coordinate for the complete vertical chain: top plane is
                    // exactly zero, every shared block boundary receives the same weight, bottom is one.
                    float depth = (chainTopY + 1f) - (pos.getY() + p.y());
                    float w = Math.max(0f, Math.min(1f, depth / chainLength));
                    va = (int) (w * 255f + 0.5f);
                } else if (material == MAT_EMISSIVE_SWAY && !lanternContinuesChain) {
                    // A lantern attached directly to a ceiling is itself the pendulum: pin its top and
                    // increase displacement toward its bottom. Beneath a chain it moves rigidly with
                    // the chain endpoint (the default full weight), so the join can never separate.
                    float w = Math.max(0f, Math.min(1f, 1f - p.y()));
                    va = (int) (w * 255f + 0.5f);
                }
                // Baked coloured block-light at this vertex corner (0 when no source reaches it).
                int lightTint = !acceptsColouredLightTint || BakedLightTint.isEmpty() ? 0
                        : BakedLightTint.tintAt(pos.getX() + p.x(), pos.getY() + p.y(), pos.getZ() + p.z());
				out.putVertex(layer,
						bx + p.x(), by + p.y(), bz + p.z(),
						u, v,
						vr, vg, vb, 255,
                        vBlockLight, vSkyLight,
                        nx, ny, nz,
						material, lightTint, ao, va / 255.0f);
            }
        }
    }

    private static boolean isVerticalChain(BlockState state) {
        if (!(state.getBlock() instanceof net.minecraft.block.ChainBlock)) return false;
        try {
            return state.get(net.minecraft.state.property.Properties.AXIS)
                    == net.minecraft.util.math.Direction.Axis.Y;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // --- AO helpers ---

    private static float dirBrightness(Direction face) {
        if (face == null) return DIR_NULL;
        return switch (face) {
            case UP -> DIR_UP;
            case DOWN -> DIR_DOWN;
            case NORTH, SOUTH -> DIR_NS;
            case EAST, WEST -> DIR_EW;
        };
    }

    /**
     * Per-vertex ambient occlusion for axis-aligned faces.
     *
     * <p>Determines which corner of the face the vertex occupies (using the vertex's
     * local block position), then queries the 3 adjacent blocks (2 sides + diagonal)
     * for solidity. MC's formula: if both sides are solid → fully occluded (value 0),
     * otherwise value = 3 − (side1 + side2 + diagonal). The value indexes
     * {@link #AO_TABLE} to get the brightness factor.
     *
     * <p>Returns 1.0 for null-face quads (cross-shaped plants etc.).
     */
    /**
     * Combined ambient-occlusion + smooth-light for one face vertex, fetching each of the four corner
     * blocks ONCE (AO previously fetched 3 and smooth-light another 4 of the same blocks). Returns a
     * packed long: bits 0..7 = skyLevel×16, 8..15 = blockLevel×16, bit 24 = light-valid, bits 16..23 =
     * AO×255. Opaque full cubes are skipped from the light average (their ~0 light would darken edges).
     */
    private static long vertexShade(BlockRenderView w, BlockPos.Mutable n, Direction face,
            int bx, int by, int bz, float lx, float ly, float lz) {
        int cx = lx < 0.5f ? -1 : 1, cy = ly < 0.5f ? -1 : 1, cz = lz < 0.5f ? -1 : 1;
        int fx, fy, fz, ax, ay, az, b2x, b2y, b2z, dx, dy, dz; // face block + the two edge sides + diagonal
        switch (face) {
            case UP ->    { fx=bx; fy=by+1; fz=bz;   ax=bx+cx; ay=by+1; az=bz;   b2x=bx; b2y=by+1; b2z=bz+cz;   dx=bx+cx; dy=by+1; dz=bz+cz; }
            case DOWN ->  { fx=bx; fy=by-1; fz=bz;   ax=bx+cx; ay=by-1; az=bz;   b2x=bx; b2y=by-1; b2z=bz+cz;   dx=bx+cx; dy=by-1; dz=bz+cz; }
            case NORTH -> { fx=bx; fy=by; fz=bz-1;   ax=bx+cx; ay=by; az=bz-1;   b2x=bx; b2y=by+cy; b2z=bz-1;   dx=bx+cx; dy=by+cy; dz=bz-1; }
            case SOUTH -> { fx=bx; fy=by; fz=bz+1;   ax=bx+cx; ay=by; az=bz+1;   b2x=bx; b2y=by+cy; b2z=bz+1;   dx=bx+cx; dy=by+cy; dz=bz+1; }
            case WEST ->  { fx=bx-1; fy=by; fz=bz;   ax=bx-1; ay=by+cy; az=bz;   b2x=bx-1; b2y=by; b2z=bz+cz;   dx=bx-1; dy=by+cy; dz=bz+cz; }
            default ->    { fx=bx+1; fy=by; fz=bz;   ax=bx+1; ay=by+cy; az=bz;   b2x=bx+1; b2y=by; b2z=bz+cz;   dx=bx+1; dy=by+cy; dz=bz+cz; }
        }
        int lf = lightIf(w, n, fx, fy, fz); boolean of = lf == -1;
        int la = lightIf(w, n, ax, ay, az); boolean oa = la == -1;
        int lb = lightIf(w, n, b2x, b2y, b2z); boolean ob = lb == -1;
        int ld = lightIf(w, n, dx, dy, dz); boolean od = ld == -1;
        int occ = (oa && ob) ? 3 : (oa ? 1 : 0) + (ob ? 1 : 0) + (od ? 1 : 0); // AO uses the 3 sides (a,b,d)
        long out = (long) ((int) (AO_TABLE[occ] * 255f + 0.5f)) << 16;
        int bl = 0, sl = 0, c = 0;
        if (lf >= 0) { bl += lf >>> 4 & 15; sl += lf >>> 20 & 15; c++; }
        if (la >= 0) { bl += la >>> 4 & 15; sl += la >>> 20 & 15; c++; }
        if (lb >= 0) { bl += lb >>> 4 & 15; sl += lb >>> 20 & 15; c++; }
        // A diagonal sample is reachable only through both edge neighbours. Including it when one
        // of those neighbours is a full cube pulls sky-light 15 around a solid corner into a cave
        // vertex; interpolation then turns that single bad corner into a bright line over the whole
        // face. Open surfaces still receive the same four-sample smoothing.
        if (!oa && !ob && ld >= 0) { bl += ld >>> 4 & 15; sl += ld >>> 20 & 15; c++; }
        if (c > 0) {
            out |= (1L << 24) | ((long) ((bl * 16 + c / 2) / c) << 8) | ((sl * 16 + c / 2) / c);
        }
        return out;
    }

    /** Lightmap at a position, or -1 if it's an opaque full cube (skipped from smooth-light averaging). */
    private static int lightIf(BlockRenderView w, BlockPos.Mutable n, int x, int y, int z) {
        if (w.getBlockState(n.set(x, y, z)).isOpaqueFullCube()) {
            return -1;
        }
        return packedLight(w, n);
    }

    private static float computeAO(BlockRenderView world, BlockPos pos, Direction face,
            BlockPos.Mutable n, float lx, float ly, float lz) {
        if (face == null) return 1.0f;
        int bx = pos.getX(), by = pos.getY(), bz = pos.getZ();
        int cx = lx < 0.5f ? -1 : 1;
        int cy = ly < 0.5f ? -1 : 1;
        int cz = lz < 0.5f ? -1 : 1;
        return switch (face) {
            case UP    -> aoAt(world, n, bx + cx, by + 1, bz,      bx, by + 1, bz + cz, bx + cx, by + 1, bz + cz);
            case DOWN  -> aoAt(world, n, bx + cx, by - 1, bz,      bx, by - 1, bz + cz, bx + cx, by - 1, bz + cz);
            case NORTH -> aoAt(world, n, bx + cx, by,     bz - 1,  bx, by + cy, bz - 1, bx + cx, by + cy, bz - 1);
            case SOUTH -> aoAt(world, n, bx + cx, by,     bz + 1,  bx, by + cy, bz + 1, bx + cx, by + cy, bz + 1);
            case WEST  -> aoAt(world, n, bx - 1,  by + cy, bz,     bx - 1, by, bz + cz, bx - 1, by + cy, bz + cz);
            case EAST  -> aoAt(world, n, bx + 1,  by + cy, bz,     bx + 1, by, bz + cz, bx + 1, by + cy, bz + cz);
        };
    }

    private static float aoAt(BlockRenderView world, BlockPos.Mutable n,
            int s1x, int s1y, int s1z,
            int s2x, int s2y, int s2z,
            int dx, int dy, int dz) {
        boolean s1 = world.getBlockState(n.set(s1x, s1y, s1z)).isOpaqueFullCube();
        boolean s2 = world.getBlockState(n.set(s2x, s2y, s2z)).isOpaqueFullCube();
        boolean d  = world.getBlockState(n.set(dx,  dy,  dz )).isOpaqueFullCube();
        int occ = (s1 && s2) ? 3 : (s1 ? 1 : 0) + (s2 ? 1 : 0) + (d ? 1 : 0);
        return AO_TABLE[occ];
    }

    // --- fluid meshing (water + lava; faithful port of vanilla net.minecraft.client.render.block.FluidRenderer) ---
    //
    // Replaces the old simple-average corner-height code that produced "solid block" /
    // brown-patch water on slopes. Now: weighted-average corner heights, flow-direction top
    // sprite (water_still when still, water_flow rotated by FluidState.getVelocity when
    // flowing), full-height falling water, water_overlay against glass/leaves, vanilla face
    // visibility (shouldRenderSide / shouldSkipRendering). The translucent pass disables
    // backface culling, so each face is a single double-sided quad (no vanilla back-faces).

    private static final float FLUID_FULL = 0.8888889F; // vanilla source-block surface height

	/**
	 * Fluid geometry stays exactly on the cell boundary so an air pocket around a door/chest is a
	 * closed volume. Coplanar texture conflicts are resolved by the water fragment depth bias instead
	 * of moving this face inward — an inset leaves a visible slit through the pocket wall.
	 */
	private static final float FACE_INSET = 0.0F;

    private static float frame(float lo, float hi, float f) {
        return lo + f * (hi - lo);
    }

    private static int shade(float c, float bright) {
        return Math.min(255, (int) (c * bright * 255f + 0.5f));
    }

    /** Per-channel max of two packed lightmap coords (block bits 4..7, sky bits 20..23). */
    private static int maxLight(int a, int b) {
        int bl = Math.max(a >>> 4 & 15, b >>> 4 & 15);
        int sl = Math.max(a >>> 20 & 15, b >>> 20 & 15);
        return (bl << 4) | (sl << 20);
    }

    /** Snapshot path avoids the mutable LightingProvider entirely; vanilla path retains its lookup. */
    private static int packedLight(BlockRenderView world, BlockPos pos) {
        return world instanceof SectionInputSnapshot snapshot
                ? snapshot.packedLight(pos)
                : WorldRenderer.getLightmapCoordinates(world, pos);
    }

    private static void meshFluid(SectionMeshData out, BlockRenderView world, BlockRenderView tintView, BlockState blockState,
            FluidState fluidState, BlockPos pos, BlockPos.Mutable scratch, BlockPos.Mutable corner,
			BlockPos.Mutable height, int x, int y, int z, boolean lava) {
        Fluid fluid = fluidState.getFluid();
        // Lava: white tint, opaque, solid layer, lava sprites, no glass/leaf overlay.
        // Water: biome tint, alpha-blended translucent layer, water sprites + overlay vs glass/leaves.
        float cr, cg, cb;
        if (lava) {
            cr = cg = cb = 1f;
        } else {
			// Unlike grass/leaves, a lake is one continuous screen-space surface. Switching it to the
			// approximate far biome kernel produced a camera-centred colour ring, so water always uses
			// the detailed kernel while retaining its per-quart worker cache.
            int color = BiomeBlurTint.getDetailedColor(tintView, pos, WATER_COLOR);
            cr = (color >> 16 & 0xFF) / 255f;
            cg = (color >> 8 & 0xFF) / 255f;
            cb = (color & 0xFF) / 255f;
        }
        int a = lava ? 255 : 210;
        int layer = lava ? SectionMeshData.LAYER_SOLID : SectionMeshData.LAYER_TRANSLUCENT;
        float stU0 = lava ? lavaU0 : waterU0, stU1 = lava ? lavaU1 : waterU1;
        float stV0 = lava ? lavaV0 : waterV0, stV1 = lava ? lavaV1 : waterV1;
        float flU0 = lava ? lfU0 : wfU0, flU1 = lava ? lfU1 : wfU1;
        float flV0 = lava ? lfV0 : wfV0, flV1 = lava ? lfV1 : wfV1;
        int wx = pos.getX(), wy = pos.getY(), wz = pos.getZ();
		SectionInputSnapshot input = world instanceof SectionInputSnapshot snapshot ? snapshot : null;
		boolean missingNorth = input != null && input.isColumnMissing(wx, wz - 1);
		boolean missingSouth = input != null && input.isColumnMissing(wx, wz + 1);
		boolean missingWest = input != null && input.isColumnMissing(wx - 1, wz);
		boolean missingEast = input != null && input.isColumnMissing(wx + 1, wz);

        BlockState bsDown = world.getBlockState(scratch.set(wx, wy - 1, wz));
		boolean waterOnIce = !lava && isIceSurface(bsDown);
        FluidState fsDown = bsDown.getFluidState();
        BlockState bsUp = world.getBlockState(scratch.set(wx, wy + 1, wz));
        FluidState fsUp = bsUp.getFluidState();
		// Unknown halo is not AIR.  Use the current fluid as a conservative continuation: this keeps
		// the loaded top surface flat and suppresses a temporary internal side wall. The captured
		// missingColumnMask already guarantees a remesh when the real neighbour arrives.
        BlockState bsNorth = missingNorth ? blockState : world.getBlockState(scratch.set(wx, wy, wz - 1));
        FluidState fsNorth = bsNorth.getFluidState();
        BlockState bsSouth = missingSouth ? blockState : world.getBlockState(scratch.set(wx, wy, wz + 1));
        FluidState fsSouth = bsSouth.getFluidState();
        BlockState bsWest = missingWest ? blockState : world.getBlockState(scratch.set(wx - 1, wy, wz));
        FluidState fsWest = bsWest.getFluidState();
        BlockState bsEast = missingEast ? blockState : world.getBlockState(scratch.set(wx + 1, wy, wz));
        FluidState fsEast = bsEast.getFluidState();

		// A fluid cell below a full opaque block is still physically part of the same water
		// volume. Rendering its 8/9-high top face leaves a thin fake air layer under every
		// submerged block. Non-full objects (doors, chests, fences) deliberately keep their
		// visible water boundary, because that is the real air pocket around the object.
		boolean renderTop = !isSameFluid(fluidState, fsUp) && (lava || !bsUp.isOpaqueFullCube());
		// Preserve the lower rim of a real air pocket around a non-full object. A bottom quad below
		// a full opaque block is instead the unwanted thin underwater bubble and is omitted.
		// Lava keeps its underside because a suspended/falling lava sheet is visibly bounded there.
		boolean renderBottom = (lava || !bsDown.isOpaqueFullCube())
				&& shouldRenderSide(fluidState, blockState, Direction.DOWN, fsDown)
				&& !shouldSkipRendering(Direction.DOWN, FLUID_FULL, bsDown);
        boolean rN = shouldRenderSide(fluidState, blockState, Direction.NORTH, fsNorth);
        boolean rS = shouldRenderSide(fluidState, blockState, Direction.SOUTH, fsSouth);
        boolean rW = shouldRenderSide(fluidState, blockState, Direction.WEST, fsWest);
        boolean rE = shouldRenderSide(fluidState, blockState, Direction.EAST, fsEast);
        if (!(renderTop || renderBottom || rN || rS || rW || rE)) {
            return;
        }

        // light = max(this block, block above) — same as vanilla getLight
        int lp = packedLight(world, pos);
        int lu = packedLight(world, scratch.set(wx, wy + 1, wz));
        int bl = Math.max(lp >>> 4 & 15, lu >>> 4 & 15);
        int sl = Math.max(lp >>> 20 & 15, lu >>> 20 & 15);
        // Baked coloured light: one sample per fluid cell (centre) is plenty for a 1-block water face.
        int cellTint = BakedLightTint.isEmpty() ? 0 : BakedLightTint.tintAt(wx + 0.5f, wy + 0.5f, wz + 0.5f);

        float n = getFluidHeight(world, fluid, wx, wy, wz, blockState, fluidState, height);
        float o, p, q, r; // NE, NW, SE, SW corner heights
        if (n >= 1.0f) {
            o = p = q = r = 1.0f;
        } else {
            float hN = getFluidHeight(world, fluid, wx, wy, wz - 1, bsNorth, fsNorth, height);
            float hS = getFluidHeight(world, fluid, wx, wy, wz + 1, bsSouth, fsSouth, height);
            float hE = getFluidHeight(world, fluid, wx + 1, wy, wz, bsEast, fsEast, height);
            float hW = getFluidHeight(world, fluid, wx - 1, wy, wz, bsWest, fsWest, height);
            o = calculateFluidHeight(world, fluid, n, hN, hE, wx + 1, wy, wz - 1, corner, height);
            p = calculateFluidHeight(world, fluid, n, hN, hW, wx - 1, wy, wz - 1, corner, height);
            q = calculateFluidHeight(world, fluid, n, hS, hE, wx + 1, wy, wz + 1, corner, height);
            r = calculateFluidHeight(world, fluid, n, hS, hW, wx - 1, wy, wz + 1, corner, height);
        }

        // Vanilla's SECOND per-side check (FluidRenderer does this at render time): skip a side face
        // whose neighbour's culling face fully covers it — ice, glass, any full cube. The UP/DOWN faces
        // already do this; without it on the sides, water rendered a face coplanar with a TRANSLUCENT
        // neighbour's own face (ice under water), and with both depth-writing in the translucent pass
        // they z-fight → flicker. The 0.001 inset alone is below depth precision at distance.
        if (rN) rN = !shouldSkipRendering(Direction.NORTH, Math.max(p, o), bsNorth);
        if (rS) rS = !shouldSkipRendering(Direction.SOUTH, Math.max(q, r), bsSouth);
        if (rW) rW = !shouldSkipRendering(Direction.WEST, Math.max(r, p), bsWest);
        if (rE) rE = !shouldSkipRendering(Direction.EAST, Math.max(o, q), bsEast);

        float w = renderBottom ? FACE_INSET : 0.0f;

        // --- top surface ---
        if (renderTop && !shouldSkipRendering(Direction.UP, Math.min(Math.min(p, r), Math.min(q, o)), bsUp)) {
            p -= 0.001f; r -= 0.001f; q -= 0.001f; o -= 0.001f;
            net.minecraft.util.math.Vec3d vel = fluidState.getVelocity(world, pos);
            float uA, vA, uB, vB, uC, vC, uD, vD;
            // Match vanilla: still cells use the still animation, moving cells rotate the flow
            // animation along their velocity. Far/minified flow contrast is stabilised in the
            // translucent shader from the actual texture footprint, so motion is retained nearby
            // without turning every differently-oriented cell into a distant checkerboard.
            if (vel.x == 0.0 && vel.z == 0.0) {
                uA = frame(stU0, stU1, 0f); vA = frame(stV0, stV1, 0f);
                uB = frame(stU0, stU1, 0f); vB = frame(stV0, stV1, 1f);
                uC = frame(stU0, stU1, 1f); vC = frame(stV0, stV1, 1f);
                uD = frame(stU0, stU1, 1f); vD = frame(stV0, stV1, 0f);
            } else {
                float af = (float) net.minecraft.util.math.MathHelper.atan2(vel.z, vel.x) - (float) (Math.PI / 2);
                float ag = net.minecraft.util.math.MathHelper.sin(af) * 0.25f;
                float ah = net.minecraft.util.math.MathHelper.cos(af) * 0.25f;
                uA = frame(flU0, flU1, 0.5f + (-ah - ag)); vA = frame(flV0, flV1, 0.5f + (-ah + ag));
                uB = frame(flU0, flU1, 0.5f + (-ah + ag)); vB = frame(flV0, flV1, 0.5f + (ah + ag));
                uC = frame(flU0, flU1, 0.5f + (ah + ag));  vC = frame(flV0, flV1, 0.5f + (ah - ag));
                uD = frame(flU0, flU1, 0.5f + (ah - ag));  vD = frame(flV0, flV1, 0.5f + (-ah - ag));
            }
            int tr = Math.round(cr * 255f), tg = Math.round(cg * 255f), tb = Math.round(cb * 255f);
            int topMat = lava ? MAT_DEFAULT : (waterOnIce ? MAT_WATER_ICE : MAT_WATER);
            // Water's normal render alpha is 210. For a top vertex only, 255 is an internal flag for
            // the vertex shader: all four cells sharing that exact world-space corner are exposed,
            // still source water. Computing it per CORNER (not per quad) makes adjacent quads agree,
            // so the cosmetic wave can never tear a seam at a shore, current or chunk boundary.
            int aP = lava ? a : waterWaveAlpha(world, fluid, wx,     wy, wz,     corner, a);
            int aR = lava ? a : waterWaveAlpha(world, fluid, wx,     wy, wz + 1, corner, a);
            int aQ = lava ? a : waterWaveAlpha(world, fluid, wx + 1, wy, wz + 1, corner, a);
            int aO = lava ? a : waterWaveAlpha(world, fluid, wx + 1, wy, wz,     corner, a);
            out.putVertex(layer, x,      y + p, z,      uA, vA, tr, tg, tb, aP, bl, sl, 0, 1, 0, topMat, cellTint, 1f, 0f);
            out.putVertex(layer, x,      y + r, z + 1f, uB, vB, tr, tg, tb, aR, bl, sl, 0, 1, 0, topMat, cellTint, 1f, 0f);
            out.putVertex(layer, x + 1f, y + q, z + 1f, uC, vC, tr, tg, tb, aQ, bl, sl, 0, 1, 0, topMat, cellTint, 1f, 0f);
            out.putVertex(layer, x + 1f, y + o, z,      uD, vD, tr, tg, tb, aO, bl, sl, 0, 1, 0, topMat, cellTint, 1f, 0f);
        }

        // All water faces are MAT_WATER (>=3.5) so the translucent shader does NOT back-face cull them
        // like glass — without this, water sides/bottom were single-sided and you saw clean through the
        // water from many angles. Water is flat (no geometric wave), so no per-vertex wave split is
        // needed. Lava stays MAT_DEFAULT (opaque layer).
        // A shallow spreading cell above bright ice exposes both its top and short side faces. Tag the
		// complete cell consistently; stabilising only the top left the darker animated side texture as
		// a one-block outline around every flow level.
        int waterMat = lava ? MAT_DEFAULT : (waterOnIce ? MAT_WATER_ICE : MAT_WATER);
        // This face is the deliberate ceiling of a door/chest air pocket. It must survive the
        // underwater-only bottom-face rejection in the fragment shader; ordinary water bottoms do not.
        int botMat = !lava && renderBottom && !bsDown.isOpaqueFullCube() ? MAT_WATER_POCKET : waterMat;
        int sideTopMat = waterMat, sideBotMat = waterMat;

        // --- bottom face ---
        if (renderBottom) {
            int br = Math.round(cr * 255f), bg = Math.round(cg * 255f), bb = Math.round(cb * 255f);
            out.putVertex(layer, x,     y + w, z + 1f, stU0, stV1, br, bg, bb, a, bl, sl, 0, -1, 0, botMat, cellTint, 1f, 0f);
            out.putVertex(layer, x,     y + w, z,     stU0, stV0, br, bg, bb, a, bl, sl, 0, -1, 0, botMat, cellTint, 1f, 0f);
            out.putVertex(layer, x + 1f, y + w, z,     stU1, stV0, br, bg, bb, a, bl, sl, 0, -1, 0, botMat, cellTint, 1f, 0f);
            out.putVertex(layer, x + 1f, y + w, z + 1f, stU1, stV1, br, bg, bb, a, bl, sl, 0, -1, 0, botMat, cellTint, 1f, 0f);
        }

        // --- side faces ---
        for (Direction dir : Direction.Type.HORIZONTAL) {
            // Reuse the same conservative neighbour resolved above. Re-reading the immutable snapshot
            // here turned an absent halo column back into AIR even though the visibility/height pass had
            // deliberately treated it as continuing fluid. That emitted a dark vertical water wall at
            // every not-yet-arrived 16-block boundary and left a conspicuous grid until its remesh won
            // the streaming queue.
            BlockState neighbor = switch (dir) {
                case NORTH -> bsNorth;
                case SOUTH -> bsSouth;
                case WEST -> bsWest;
                default -> bsEast;
            };
            float inset = neighbor.isAir() ? 0.0f : FACE_INSET;
            float ad, yy, aa, ae, ac, am; // top-heights + X/Z edges (vanilla naming ad,y,aa,ae,ac,am)
            boolean vis;
            switch (dir) {
                case NORTH -> { ad = p; yy = o; aa = x;        ae = x + 1f;   ac = z + inset;      am = z + inset;      vis = rN; }
                case SOUTH -> { ad = q; yy = r; aa = x + 1f;   ae = x;        ac = z + 1f - inset; am = z + 1f - inset; vis = rS; }
                case WEST  -> { ad = r; yy = p; aa = x + inset; ae = x + inset; ac = z + 1f;       am = z;               vis = rW; }
                default    -> { ad = o; yy = q; aa = x + 1f - inset; ae = x + 1f - inset; ac = z;  am = z + 1f;          vis = rE; }
            }
            if (!vis) continue;
            if (shouldSkipRendering(dir, Math.max(ad, yy), neighbor)) continue;

            // water_overlay against glass/leaves; otherwise flow sprite (water_flow / lava_flow).
            boolean overlay = !lava && (neighbor.getBlock() instanceof net.minecraft.block.TranslucentBlock
                    || neighbor.getBlock() instanceof net.minecraft.block.LeavesBlock);
            float su0 = overlay ? wovU0 : flU0, su1 = overlay ? wovU1 : flU1;
            float sv0 = overlay ? wovV0 : flV0, sv1 = overlay ? wovV1 : flV1;
            float ui = frame(su0, su1, 0f);
            float un = frame(su0, su1, 0.5f);
			boolean falling = !overlay && fluidState.get(net.minecraft.fluid.FlowableFluid.FALLING);
			// A falling sheet is visually one column, not a stack of one-block textures. Span one
			// complete animated frame over four world blocks; adjacent cells share exactly the same V
			// at their boundary and only the sprite's own tile edge repeats once per four blocks.
			float phase = wy & 3;
			float vAd = falling ? frame(sv0, sv1, 1f - (phase + ad) * 0.25f)
					: frame(sv0, sv1, (1f - ad) * 0.5f);
			float vY  = falling ? frame(sv0, sv1, 1f - (phase + yy) * 0.25f)
					: frame(sv0, sv1, (1f - yy) * 0.5f);
			float vQ  = falling ? frame(sv0, sv1, 1f - phase * 0.25f)
					: frame(sv0, sv1, 0.5f);
            int sr = Math.round(cr * 255f), sg = Math.round(cg * 255f), sb = Math.round(cb * 255f);
            int nx = dir.getOffsetX(), nz = dir.getOffsetZ();
            out.putVertex(layer, aa, y + ad, ac, ui, vAd, sr, sg, sb, a, bl, sl, nx, 0, nz, sideTopMat, cellTint, 1f, 0f);
            out.putVertex(layer, ae, y + yy, am, un, vY,  sr, sg, sb, a, bl, sl, nx, 0, nz, sideTopMat, cellTint, 1f, 0f);
            out.putVertex(layer, ae, y + w,  am, un, vQ,  sr, sg, sb, a, bl, sl, nx, 0, nz, sideBotMat, cellTint, 1f, 0f);
            out.putVertex(layer, aa, y + w,  ac, ui, vQ,  sr, sg, sb, a, bl, sl, nx, 0, nz, sideBotMat, cellTint, 1f, 0f);
        }
    }

    private static boolean isSameFluid(FluidState a, FluidState b) {
        return b.getFluid().matchesType(a.getFluid());
    }

    private static boolean isIceSurface(BlockState state) {
        net.minecraft.block.Block block = state.getBlock();
        return block instanceof net.minecraft.block.IceBlock
                || block == net.minecraft.block.Blocks.PACKED_ICE
                || block == net.minecraft.block.Blocks.BLUE_ICE
                || block == net.minecraft.block.Blocks.FROSTED_ICE;
    }

    /**
     * Encode a wave weight only when every fluid cell sharing a top vertex is safe to move. The four
     * independent calls made by neighbouring quads inspect the same four world cells, therefore they
     * produce the same position and cannot disconnect the surface.
     */
    private static int waterWaveAlpha(BlockRenderView world, Fluid fluid, int vertexX, int y, int vertexZ,
            BlockPos.Mutable tmp, int normalAlpha) {
        SectionInputSnapshot snapshot = world instanceof SectionInputSnapshot input ? input : null;
        for (int dz = -1; dz <= 0; dz++) {
            for (int dx = -1; dx <= 0; dx++) {
                int x = vertexX + dx;
                int z = vertexZ + dz;
                if (snapshot != null && snapshot.isColumnMissing(x, z)) return normalAlpha;
                BlockState state = world.getBlockState(tmp.set(x, y, z));
                FluidState sample = state.getFluidState();
                if (state.getBlock() != net.minecraft.block.Blocks.WATER
                        || !fluid.matchesType(sample.getFluid())
                        || !sample.isStill()
                        || sample.get(net.minecraft.fluid.FlowableFluid.FALLING)) {
                    return normalAlpha;
                }
                FluidState above = world.getFluidState(tmp.set(x, y + 1, z));
                if (fluid.matchesType(above.getFluid())) return normalAlpha;
            }
        }
        return 255;
    }

    /**
     * A block that can't hold water (door, fence gate, chest, …) but is non-full and sits directly
     * under water leaves an ugly air pocket in vanilla. Detect that case so its cell can be treated
     * as water and the surrounding water stays continuous.
     */
    private static boolean isSubmergedGap(BlockRenderView world, int x, int y, int z, BlockState bs, BlockPos.Mutable tmp) {
        if (bs.isAir() || !bs.getFluidState().isEmpty()) {
            return false; // air, or already (waterlogged) fluid — nothing to fill
        }
		// Glass and panes deliberately remain their own transparent geometry. Treating their
		// non-full culling shape as an air gap injected a complete water cube into the same cell;
		// depending on the camera sort that looked like a block-sized underwater "air bubble".
		net.minecraft.block.Block block = bs.getBlock();
		if (block instanceof net.minecraft.block.TransparentBlock
				|| block instanceof net.minecraft.block.PaneBlock
				|| block instanceof net.minecraft.block.LeavesBlock) {
			return false;
		}
        // Waterloggable blocks (stairs, slabs, …) that are DRY are dry on purpose — treating them as
        // water made neighbouring REAL water cull its faces toward them, punching see-through holes in
        // flowing water over terraced terrain. The fix targets only non-waterloggable shapes (doors,
        // fence gates) that genuinely cannot hold water.
        if (bs.getBlock() instanceof net.minecraft.block.Waterloggable) {
            return false;
        }
        if (bs.isFullCube(world, tmp.set(x, y, z))) {
            return false; // full-shape blocks (glass, solid cubes) leave no visible gap
        }
        return world.getFluidState(tmp.set(x, y + 1, z)).isIn(FluidTags.WATER); // water directly above ⇒ submerged
    }

    private static boolean isSideCovered(Direction side, float height, BlockState state) {
        VoxelShape shape = state.getCullingFace(side.getOpposite());
        if (shape == VoxelShapes.empty()) {
            return false;
        }
        if (shape == VoxelShapes.fullCube()) {
            return side != Direction.UP || height == 1.0f;
        }
        VoxelShape self = VoxelShapes.cuboid(0.0, 0.0, 0.0, 1.0, height, 1.0);
        return VoxelShapes.isSideCovered(self, shape, side);
    }

    private static boolean shouldSkipRendering(Direction side, float height, BlockState state) {
		// A pane has no full face, so water must remain visible through the same cell. A full glass
		// block does have a real face: suppress the coplanar water quad there, otherwise the animated
		// water texture is painted directly over the stained-glass texture.
		net.minecraft.block.Block block = state.getBlock();
		if (block instanceof net.minecraft.block.PaneBlock) {
			return false;
		}
		if (block instanceof net.minecraft.block.TransparentBlock) {
			return true; // full glass owns this face; never paint a coplanar water quad over it
		}
        return isSideCovered(side, height, state);
    }

    private static boolean shouldRenderSide(FluidState fluid, BlockState state, Direction side, FluidState fluidFromSide) {
        return !isSideCovered(side.getOpposite(), 1.0f, state) && !isSameFluid(fluid, fluidFromSide);
    }

    /** Fluid surface height at one block: 1.0 if same fluid is above, else the state's height; -1 if a solid non-fluid block, 0 if open. */
    private static float getFluidHeight(BlockRenderView world, Fluid fluid, int bx, int by, int bz,
            BlockState bs, FluidState fs, BlockPos.Mutable up) {
        if (fluid.matchesType(fs.getFluid())) {
            BlockState above = world.getBlockState(up.set(bx, by + 1, bz));
            return fluid.matchesType(above.getFluidState().getFluid()) ? 1.0f : fs.getHeight();
        }
        return !bs.isSolid() ? 0.0f : -1.0f;
    }

    /** Weighted-average corner height (vanilla calculateFluidHeight + addHeight), allocation-free. */
    private static float calculateFluidHeight(BlockRenderView world, Fluid fluid, float origin,
            float northSouth, float eastWest, int cx, int cy, int cz, BlockPos.Mutable mp, BlockPos.Mutable up) {
        if (eastWest >= 1.0f || northSouth >= 1.0f) {
            return 1.0f;
        }
		if (world instanceof SectionInputSnapshot snapshot && snapshot.isColumnMissing(cx, cz)) {
			// A diagonal AIR fallback would pull only the boundary corner down and expose the section
			// grid from above. Preserve the best known adjacent height until the real halo is remeshed.
			return Math.max(origin, Math.max(northSouth, eastWest));
		}
        float sum = 0f, weight = 0f;
        if (eastWest > 0.0f || northSouth > 0.0f) {
            BlockState bs = world.getBlockState(mp.set(cx, cy, cz));
            float f = getFluidHeight(world, fluid, cx, cy, cz, bs, bs.getFluidState(), up);
            if (f >= 1.0f) {
                return 1.0f;
            }
            if (f >= 0.8f) { sum += f * 10f; weight += 10f; } else if (f >= 0.0f) { sum += f; weight += 1f; }
        }
        if (origin >= 0.8f)   { sum += origin * 10f;   weight += 10f; } else if (origin >= 0.0f)   { sum += origin;   weight += 1f; }
        if (eastWest >= 0.8f) { sum += eastWest * 10f; weight += 10f; } else if (eastWest >= 0.0f) { sum += eastWest; weight += 1f; }
        if (northSouth >= 0.8f) { sum += northSouth * 10f; weight += 10f; } else if (northSouth >= 0.0f) { sum += northSouth; weight += 1f; }
        return weight == 0f ? origin : sum / weight;
    }

}
