package com.moneyakshaders.render;

import static com.moneyakshaders.render.TerrainShaders.*;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.textures.GpuTexture;
import com.moneyakshaders.MoneyakShaders;
import com.moneyakshaders.MoneyakShadersConfig;
import com.moneyakshaders.client.DynamicLightSources;
import com.moneyakshaders.client.EntityShadowCapture;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.model.ModelBaker;
import net.minecraft.client.texture.GlTexture;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.AMDDrawBuffersBlend;
import org.lwjgl.opengl.ARBDrawBuffersBlend;
import org.lwjgl.opengl.EXTTextureFilterAnisotropic;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL44;
import org.lwjgl.system.MemoryUtil;

/**
 * Plan C / Phase 3.4 — render the visible world (many sections) through our own
 * pipeline. Meshes a region of sections around the player once, uploads each to
 * its own VBO/IBO, and draws them all each frame with a shared shader/MVP and a
 * per-section base offset. Textured (block atlas), depth-tested, backface-culled.
 *
 * <p>Still HUD-time + a one-shot region mesh (not yet world-pass / live re-mesh /
 * multidraw — those are 3.4 cont. / Phase 5).
 */
public final class ExperimentalSectionRender {
	private static final int RADIUS_CAP = 16;
	private static final int VERTICAL_SECTIONS = 6; // player +/- 6 sections (96 blocks up/down)
	private static long renderFrameRevision;
	private static long frustumRevision = Long.MIN_VALUE;
	private static final int DIRECTIONAL_CASCADE_COUNT = ShadowCascadeLayout.CASCADE_COUNT;
	private static final int[] SHADOW_TEXTURE_UNITS = { 2, 3, 12 };
	private static final int[] WATER_SHADOW_TEXTURE_UNITS = { 8, 9, 13 };
	private static final SceneLightingState SCENE_LIGHTING = new SceneLightingState();
	private static final ShadowCascadeLayout SHADOW_LAYOUT = new ShadowCascadeLayout();
	private static final float[] SHADOW_DEPTH_BORDER = { 1f, 1f, 1f, 1f };
	private static final float[] SHADOW_WATER_BORDER = { 1f, 0f, 0f, 1f };
	/**
	 * Vertical section range is ASYMMETRIC: fixed {@link #VERTICAL_SECTIONS} upward, but downward it
	 * always reaches the world's surface band (section y=2 ≈ y32..47, capped) — otherwise flying high
	 * left everything below ±96 blocks unmeshed (the "ocean disappears from a mountain" report) and
	 * DROPPED already-meshed ground while ascending.
	 */
	private static boolean inVerticalRange(int sy, int psy) {
		int up = psy + VERTICAL_SECTIONS;
		int down = Math.min(psy - VERTICAL_SECTIONS, 2);
		down = Math.max(down, psy - 40); // hard cap so absurd heights don't demand hundreds of sections
		return sy >= down && sy <= up;
	}
	// Async submissions per frame. Workers mesh off-thread so submissions are cheap;
	// the budget controls how many new candidates we enqueue per frame for gradual loading.
	private static final int MESH_BUDGET_PER_FRAME = 32;
	// End-to-end streaming lanes.  The value is carried through snapshot extraction, worker meshing
	// and GL upload; lower lanes always win, so a completed buried section cannot jump ahead of a
	// visible surface merely because its worker happened to finish first.
	private static final int STREAM_URGENT = 0;
	private static final int STREAM_SURFACE = 1;
	private static final int STREAM_VISIBLE = 2;
	private static final int STREAM_REFRESH = 3;
	private static final int STREAM_BACKGROUND = 4;
	/** Producer capacity held open for already-visible block/fluid edits during heavy streaming. */
	private static final int URGENT_SNAPSHOT_RESERVE = 8;
	private static final long STREAM_TIER_SCORE = 1_000_000L;
	// A snapshot contains roughly 13k cells.  Tiny 128-cell slices spent a disproportionate amount
	// of the render-thread budget repeatedly acquiring the light read lock and scheduling the same
	// job, while the outer time budget already prevents one frame from running away.  512 cells is
	// still sub-millisecond on the supported clients, but lets the producer keep mesh workers fed.
	private static final int SNAPSHOT_SLICE_CELLS = 512;
	/**
	 * A join/teleport can enqueue a full producer window at once. The adaptive frame budget reacts to
	 * the previous frame, so the first few frames used to spend roughly 6 ms copying snapshots before
	 * it had enough history to throttle. Keep the initial 2.5 ms guard, then allow measured headroom
	 * to raise it up to 6 ms. A permanent 2.5 ms clamp starved dedicated-GPU teleport streaming.
	 */
	private static final int SNAPSHOT_BURST_JOB_THRESHOLD = 32;
	// The configured ceiling is intentionally generous for startup/teleports, but copying immutable
	// world/light data still runs on the render thread. The event-driven surface queue now needs far
	// fewer speculative cells to keep workers saturated: cap foreground extraction to roughly ten
	// complete section inputs per frame and let actually invisible detail work use only a quarter of
	// that. The adaptive time bucket remains the hard latency limit if a resource pack makes copying
	// unusually expensive.
	// Match the streaming-policy ceiling for the visible surface lane. FrameWorkBudget remains the
	// latency guard and contracts this work immediately when the previous frame approaches its target;
	// the former lower hard cap left roughly a third of configured producer throughput unreachable.
	private static final int FOREGROUND_SNAPSHOT_CELLS_PER_FRAME = 196_608;
	private static final int BACKGROUND_SNAPSHOT_CELLS_PER_FRAME = 16_384;
	// Scratch arrays for the prioritised submission pass (avoid per-frame allocations).
	// Sized for (2*RADIUS_CAP+1)² × (2*VERTICAL_SECTIONS+1) = 33×33×13 ≈ 14 157 max candidates.
	private static final int SUBMIT_SCRATCH = 16384;
	private static final double[] shadowRenderCamX = { Double.NaN, Double.NaN, Double.NaN };
	private static final double[] shadowRenderCamY = { Double.NaN, Double.NaN, Double.NaN };
	private static final double[] shadowRenderCamZ = { Double.NaN, Double.NaN, Double.NaN };
	private static final float[] shadowRenderSunX = { Float.NaN, Float.NaN, Float.NaN };
	private static final float[] shadowRenderSunY = { Float.NaN, Float.NaN, Float.NaN };
	private static final float[] shadowRenderSunZ = { Float.NaN, Float.NaN, Float.NaN };
	private static final float[] shadowRenderHalf = { Float.NaN, Float.NaN, Float.NaN };

	/** horizontal radius in chunks - follows the player's render distance (capped). */
	private static int radiusChunks = 8;
	// Progressive (load-gated) render distance: after a world change / teleport, the effective loading
	// + fog radius starts small and grows ONE step at a time only once the current ring has finished
	// meshing — so a join / teleport doesn't mesh + light the whole view at once (the big load lag).
	// Ramps up to radiusChunks.
	private static final int MIN_LOAD_RADIUS = 4;
	private static final int RADIUS_RAMP_STEP = 2;
	private static int effectiveRadiusChunks = MIN_LOAD_RADIUS;
	private static net.minecraft.client.world.ClientWorld lastLoadWorld;
	private static int lastPlayerCx = Integer.MIN_VALUE, lastPlayerCz = Integer.MIN_VALUE;
	/**
	 * First-mesh discovery is event driven. Once a complete ring scan finds no eligible section, do
	 * not walk up to 33x33 columns and their vertical bands again every rendered frame. Chunk arrival,
	 * viewer movement and radius growth wake it; REMESH/shadow queues keep their independent lanes.
	 */
	private static boolean admissionScanDirty = true;
	private static net.minecraft.client.world.ClientWorld admissionScanWorld;
	private static int admissionScanCx = Integer.MIN_VALUE, admissionScanCy = Integer.MIN_VALUE,
			admissionScanCz = Integer.MIN_VALUE, admissionScanRadius = Integer.MIN_VALUE;
	/**
	 * Columns known to have arrived from the server. First-mesh discovery is driven from this set
	 * instead of repeatedly asking ClientChunkManager about every coordinate in every concentric ring.
	 * The set is pruned whenever priorities are rebuilt after movement/radius changes.
	 */
	private static final it.unimi.dsi.fastutil.longs.LongOpenHashSet KNOWN_ADMISSION_COLUMNS =
			new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
	private static final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<ColumnAdmission> COLUMN_ADMISSIONS =
			new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
	private static final java.util.PriorityQueue<ColumnAdmissionEntry> SURFACE_ADMISSION_QUEUE =
			new java.util.PriorityQueue<>();
	private static final java.util.PriorityQueue<ColumnAdmissionEntry> ENVELOPE_ADMISSION_QUEUE =
			new java.util.PriorityQueue<>();
	private static final java.util.PriorityQueue<ColumnAdmissionEntry> DETAIL_ADMISSION_QUEUE =
			new java.util.PriorityQueue<>();
	private static long admissionRevision;
	private static long admissionSequence;
	// The admission heaps contain camera-dependent priorities. Rebuild only their cheap entries after
	// a meaningful turn; the expensive per-column surface/detail discovery remains intact.
	private static float admissionPriorityFwdX, admissionPriorityFwdZ;
	private static boolean haveAdmissionPriorityFwd;

	private static final class ColumnAdmission {
		final long key;
		final int chunkX;
		final int chunkZ;
		final int[] surfaceSections;
		final int primarySurfaceCount;
		final int[] detailSections;
		final long revision;
		int surfaceCursor;
		int detailCursor;

		ColumnAdmission(long key, int chunkX, int chunkZ, int[] surfaceSections, int primarySurfaceCount,
				int[] detailSections, long revision) {
			this.key = key;
			this.chunkX = chunkX;
			this.chunkZ = chunkZ;
			this.surfaceSections = surfaceSections;
			this.primarySurfaceCount = primarySurfaceCount;
			this.detailSections = detailSections;
			this.revision = revision;
		}
	}

	private record ColumnAdmissionEntry(long key, long revision, long priority, long sequence)
			implements Comparable<ColumnAdmissionEntry> {
		@Override public int compareTo(ColumnAdmissionEntry other) {
			int byPriority = Long.compare(priority, other.priority);
			return byPriority != 0 ? byPriority : Long.compare(sequence, other.sequence);
		}
	}
	// Region eviction depends only on the world, viewer section and configured radius. Avoid walking
	// every live section again on a frame where none of those boundaries changed.
	private static net.minecraft.client.world.ClientWorld lastEvictionWorld;
	private static int lastEvictionCx = Integer.MIN_VALUE, lastEvictionSy = Integer.MIN_VALUE,
			lastEvictionCz = Integer.MIN_VALUE, lastEvictionRadius = Integer.MIN_VALUE;
	private static boolean lastEvictionCacheOn;
	// Smoothed horizontal travel direction feeds snapshot scheduling. It is deliberately independent
	// of camera facing: elytra/sprint streaming must prepare the terrain the player will enter next.
	private static int lastStreamPlayerX = Integer.MIN_VALUE, lastStreamPlayerZ = Integer.MIN_VALUE;
	private static float streamDirectionX, streamDirectionZ;
	private static int streamDirectionIdleFrames;
	/**
	 * Biome-tint tier conversion (see {@link BiomeBlurTint}): set when the player enters a new chunk,
	 * cleared once a full scan finds no section left on the wrong side of the hysteresis band. Marks
	 * are budgeted so a ring crossing the boundary can't flood one frame's mesh budget.
	 */
	private static boolean biomeTierSweepPending;
	private static final int BIOME_TIER_MARKS_PER_FRAME = 24;

	private static final class Sec {
		enum Residency { ACTIVE, SLEEPING, WAKING }
		Residency residency = Residency.ACTIVE;
		long sleepingSinceMs;
		long appearanceStartNs = Long.MIN_VALUE;
		/** A provider light update arrived while this mesh was deliberately asleep. */
		boolean sleepingLightDirty;
		long vOff = -1; // opaque: byte offset in the vertex arena (-1 = none / didn't fit)
		long iOff = -1; // opaque: byte offset in the index arena
		int count;      // opaque index count
		long wvOff = -1; // water (translucent) layer, same arenas
		long wiOff = -1;
		int wcount;
		// Per-quad centroids (section-local, 3 floats/quad) for translucent back-to-front quad
		// sorting. Even with depth writes disabled, water over ice must blend after the ice at every
		// distance; a stale same-section order shows the animated water as a flashing block grid.
		float[] wCentroids;
		// Mixed water + glass sections cannot be left with stale quad order: glass would
		// otherwise blend after the water surface and appear to shine through it.
		boolean wMixedWater;
		// A section made only of horizontal water tops has no overlapping alpha geometry. Waterfalls and
		// shore sides remain sortable; freezing those in mesh order leaves permanently dark layers.
		boolean wOnlyWater;
		float wSortX = Float.NaN, wSortY, wSortZ; // camera pos (world) at last quad sort
		// Transparency order depends on view direction too, not only camera position.
		float wSortViewX = Float.NaN, wSortViewY, wSortViewZ;
		int cx;
		int sy;
		int cz;
		float bx;
		float by;
		float bz;
		// Which biome-tint tier this mesh was built with (see BiomeBlurTint). Tints are baked into the
		// vertices, so upgrading a section from the far tier to the detailed one needs a re-mesh.
		boolean biomeFar;
		/** Neighbour columns absent from the immutable input that produced this resident mesh. */
		int missingColumnMask;
		byte openFaces; // Phase 4.2: which of 6 faces have non-opaque boundary blocks (BFS traversal)
		long visibility = -1L; // occlusion connectivity: directed 6×6 face matrix, bit (from*6+to). -1 = all open.
		// Strong light blocks owned by this section. Packed level:4,y:4,z:4,x:4 by SectionMeshData.
		int[] placedLights;
		int placedLightCount;
		int placedLightTotal;
		long placedLightSolidMask;
		byte[] placedLightFaceMasks;
		long[] opaqueVoxels;
	}

	/** keyed by ChunkSectionPos.asLong; value with count==0 marks a known-empty section (don't re-mesh). */
	private static Long2ObjectOpenHashMap<Sec> SECTIONS = new Long2ObjectOpenHashMap<>();
	/** Minimal tombstones for underground meshes whose GPU ranges were released. */
	private static final it.unimi.dsi.fastutil.longs.LongOpenHashSet EVICTED_UNDERGROUND =
			new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
	private static final SceneResidencyScheduler SCENE_RESIDENCY = new SceneResidencyScheduler();
	private static final SceneResidencyScheduler CACHE_RESIDENCY = new SceneResidencyScheduler();
	private static final DeferredDisposal<Sec> RETIRED_SCENES = new DeferredDisposal<>();
	private static int sceneEvictions;
	private static long evictedUndergroundBytes;

	/**
	 * Sections flagged dirty by blockstate/light changes. This is a primitive MPSC queue rather than
	 * a ConcurrentHashMap-backed set: dirty keys arrive in bursts from multiplayer packets and light
	 * propagation, where boxing one Long and allocating a hash-map node per event hurts GC and p99.
	 */
	private static final MpscLongDeduplicatingQueue DIRTY = new MpscLongDeduplicatingQueue();
	private static final it.unimi.dsi.fastutil.longs.LongArrayList DIRTY_SCRATCH =
			new it.unimi.dsi.fastutil.longs.LongArrayList();
	/**
	 * World-content revision captured by every immutable snapshot. Executor generations only identify
	 * one worker submission; they cannot detect a block packet that arrives after a completed result
	 * entered the upload queue but before the next dirty drain. This token closes that one-frame stale
	 * publish window for every block edit, including fluid packet bursts.
	 */
	private static final java.util.concurrent.ConcurrentHashMap<Long, Long> CONTENT_REVISIONS =
			new java.util.concurrent.ConcurrentHashMap<>();
	private static final java.util.Set<Long> CONTENT_DIRTY_PENDING =
			java.util.concurrent.ConcurrentHashMap.newKeySet();
	private static final java.util.Set<Long> BLOCK_EDIT_BURST_SECTIONS =
			java.util.concurrent.ConcurrentHashMap.newKeySet();
	private static final java.util.Set<Long> BLOCK_EDIT_LIGHT_PENDING =
			java.util.concurrent.ConcurrentHashMap.newKeySet();
	private static final java.util.concurrent.atomic.AtomicInteger BLOCK_EDIT_BURST_COUNT =
			new java.util.concurrent.atomic.AtomicInteger();
	private static final int BULK_EDIT_DEPENDENCY_THRESHOLD = 32;
	private static volatile boolean bulkBlockEditBurst;
	private static final it.unimi.dsi.fastutil.longs.LongOpenHashSet LOCAL_BLOCK_EDITS =
			new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
	/**
	 * Packet handlers must return quickly: neighbouring columns can arrive in a dense burst and all
	 * target the same 3x3 section halo.  Publish the column once here and expand it on the render
	 * thread in bounded batches, rather than doing up to 216 map lookups and dirty publications for
	 * every network packet.
	 */
	private static final MpscLongDeduplicatingQueue RECEIVED_COLUMN_REMESH = new MpscLongDeduplicatingQueue();
	private static final it.unimi.dsi.fastutil.longs.LongArrayList RECEIVED_COLUMN_REMESH_SCRATCH =
			new it.unimi.dsi.fastutil.longs.LongArrayList();
	private static final int RECEIVED_COLUMN_REMESH_PER_FRAME = 32;

	/**
	 * Double-buffering: sections whose mesh is stale and needs rebuilding, but whose OLD
	 * mesh stays uploaded and drawn until the replacement is ready (so an edit never leaves
	 * a hole). Render-thread-only (drained from {@link #DIRTY} and consumed in
	 * {@link #updateSections}), so a plain fastutil set is enough. A key is re-submitted only
	 * when not already in-flight; the new mesh atomically swaps out the old one on completion.
	 */
	private static final it.unimi.dsi.fastutil.longs.LongOpenHashSet REMESH = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
	/**
	 * Real block edits need a second, post-propagation light rebuild. Provider callbacks are useful for
	 * the general case but cannot be the guarantee: their queue also contains streamed/sleeping light
	 * sections. Keep a small per-terrain-section deadline map so rapid edits coalesce, while unrelated
	 * far-world light traffic can never starve the visible result.
	 */
	private static final class BlockEditRelight {
		long targetLightPass;
		long fallbackDueMs;

		BlockEditRelight(long targetLightPass, long fallbackDueMs) {
			this.targetLightPass = targetLightPass;
			this.fallbackDueMs = fallbackDueMs;
		}
	}
	/** Block packets publish only their primitive source-section key. The render thread expands the
	 * 3x3x3 light-consumer halo once per distinct source per frame, avoiding 27 boxed map merges and
	 * short-lived relight records for every block in a server-side schematic paste. */
	private static final MpscLongDeduplicatingQueue BLOCK_EDIT_RELIGHT_SOURCES =
			new MpscLongDeduplicatingQueue();
	private static final it.unimi.dsi.fastutil.longs.LongArrayList BLOCK_EDIT_RELIGHT_SOURCE_SCRATCH =
			new it.unimi.dsi.fastutil.longs.LongArrayList();
	private static final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<BlockEditRelight>
			BLOCK_EDIT_RELIGHT_DUE_MS = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
	private static final java.util.concurrent.atomic.AtomicLong COMPLETED_LIGHT_PASSES =
			new java.util.concurrent.atomic.AtomicLong();
	private static final long BLOCK_EDIT_RELIGHT_FALLBACK_MS = 75L;
	/** A provisional first mesh must not immediately occupy a snapshot slot with a strict replacement.
	 * Wait outside the producer pipeline until its local 3x3x3 light revision has actually gone quiet. */
	private static final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<ProvisionalRelight>
			PROVISIONAL_RELIGHTS = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
	/** Last light revision for which a provisional resident already received its one strict retry. */
	private static final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap
			PROVISIONAL_RETRY_REVISIONS = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
	private static final int PROVISIONAL_URGENT_SUBMITS_PER_FRAME = 4;
	private static final int[] PROVISIONAL_NEAR_ORDER = { 0, -1, 1 };
	private static final class ProvisionalRelight {
		long revision;
		long quietSinceMs;
		ProvisionalRelight(long revision, long quietSinceMs) {
			this.revision = revision;
			this.quietSinceMs = quietSinceMs;
		}
	}

	/** Incomplete immutable inputs. Kept render-thread-only and bounded separately from worker queues. */
	private static final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<SnapshotJob> SNAPSHOT_JOBS =
			new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
	private record SnapshotJob(SectionSnapshotBuilder builder, int bx, int by, int bz, int streamPriority,
			long contentRevision, boolean snapshotRecorded, long enqueuedNs) { }
	/**
	 * Snapshot extraction is sliceable. Selecting the next slice by scanning the whole job map made
	 * a chunk burst cost O(jobs × slices) on the render thread. These two lazy queues keep the
	 * urgent edit/light path separate from ordinary streaming; the map remains authoritative.
	 */
	private record SnapshotQueueEntry(long key, long priority, long enqueuedNs)
			implements Comparable<SnapshotQueueEntry> {
		@Override public int compareTo(SnapshotQueueEntry other) {
			int byPriority = Long.compare(priority, other.priority);
			return byPriority != 0 ? byPriority : Long.compare(enqueuedNs, other.enqueuedNs);
		}
	}
	private static final java.util.PriorityQueue<SnapshotQueueEntry> HIGH_SNAPSHOT_QUEUE = new java.util.PriorityQueue<>();
	private static final java.util.PriorityQueue<SnapshotQueueEntry> EDIT_SNAPSHOT_QUEUE = new java.util.PriorityQueue<>();
	private static final java.util.PriorityQueue<SnapshotQueueEntry> STREAM_SNAPSHOT_QUEUE = new java.util.PriorityQueue<>();
	/** Zero-work/light-settle jobs skipped for the remainder of one pass; render-thread-only scratch. */
	private static final java.util.ArrayList<SnapshotQueueEntry> DEFERRED_SNAPSHOT_ENTRIES =
			new java.util.ArrayList<>();
	private static boolean snapshotQueuesDirty = true;
	private static long snapshotServiceFrame;
	private static long snapshotUpdatesPreserved, snapshotUpdatesRestarted;
	private static int snapshotQueuePlayerX = Integer.MIN_VALUE, snapshotQueuePlayerY = Integer.MIN_VALUE,
			snapshotQueuePlayerZ = Integer.MIN_VALUE, snapshotQueueHeadingSignature = Integer.MIN_VALUE;
	private static long snapshotQueueViewSignature = Long.MIN_VALUE;
	/**
	 * Per-received-column min/max heightmap cache used only for scheduling.  A 3x3 minimum preserves
	 * ravines and cliff skirts as surface work, while sections below every neighbouring surface can
	 * remain in the background lane. Render-thread-only; packet callbacks merely invalidate keys.
	 */
	private static final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap SURFACE_RANGE_CACHE =
			new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
	/** Non-empty section bitset touched by at least one local terrain/water surface column.  This must
	 * not be derived from the chunk-wide min/max range: one ravine would otherwise wake every section
	 * between its floor and the highest tree anywhere in the chunk. */
	private static final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap SURFACE_SECTION_MASK_CACHE =
			new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
	/**
	 * One-frame memo for the 3x3 surface minimum. The vertical admission pass asks the same question
	 * for every Y section of a column; recomputing nine hash lookups each time was one of the largest
	 * render-thread costs in the flight recording. Frame scope avoids stale results as neighbour chunks
	 * arrive while still collapsing all duplicate work inside one scheduling pass.
	 */
	private static final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap NEIGHBOR_SURFACE_FRAME_CACHE =
			new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
	private static net.minecraft.client.world.ClientWorld surfaceRangeWorld;
	/** Cached exact player-height classification used by all section candidates until the player moves. */
	private static net.minecraft.client.world.ClientWorld playerSurfaceWorld;
	private static int playerSurfaceX = Integer.MIN_VALUE, playerSurfaceY = Integer.MIN_VALUE,
			playerSurfaceZ = Integer.MIN_VALUE;
	private static boolean playerAtOrAboveSurface;

	/**
	 * v0 section cache (config {@code sectionCache}). Sections that scroll out of range are stashed here
	 * with their GPU arena regions still allocated, instead of being freed, so returning to those
	 * coordinates (a teleport back to spawn / a home) can redraw them INSTANTLY while the server resends
	 * the chunks. Insertion-ordered → eldest-first eviction. Bounded by {@link #ORPHAN_VERTEX_BUDGET} so
	 * the cache can never starve live terrain (the vertex arena is 512 MB; this caps the cache at a
	 * small slice). Render-thread-only, like {@link #SECTIONS}. A key is in AT MOST one of the two maps.
	 */
	private static it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<Sec> ORPHANS =
			new it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<>();
	private static long orphanVertexBytes;
	/**
	 * 192 MB of the 512 MB vertex arena. A cached section costs only arena bytes and skips meshing
	 * ENTIRELY when its coordinates come back into range — no worker, no biome tint, no light sampling,
	 * no upload. At 48 MB the cache held roughly a couple of hundred sections, so ordinary back-and-forth
	 * movement around a base evicted them and paid the full mesh cost again on the way back. Four times
	 * the budget still leaves the majority of the arena for live terrain, which is bounded by the render
	 * distance and cannot grow into it.
	 */
	private static final long ORPHAN_VERTEX_BUDGET = 192L * 1024 * 1024;
	/**
	 * GPU readback is synchronous even though compression/file IO happens later. Keep the persistent
	 * snapshot to the nearest useful working set instead of stalling a proxy/world transfer while up to
	 * the full in-memory orphan budget (observed near 200 MB) is copied back through the render thread.
	 */
	private static final long DISK_CACHE_WRITE_BUDGET = 48L * 1024 * 1024;

	// v1 disk cache: entries decoded off-thread on join wait here to be uploaded to GL on the render
	// thread (budgeted, so a big cache load doesn't spike one frame). diskLastWorld / cachedServerKey
	// track the connection so a full disconnect (world → null, when updateSections stops) still flushes.
	private record PendingDiskInstall(long generation, SectionDiskCache.Entry entry) { }
	private static final java.util.concurrent.ConcurrentLinkedQueue<PendingDiskInstall> PENDING_INSTALL =
			new java.util.concurrent.ConcurrentLinkedQueue<>();
	private static final java.util.concurrent.atomic.AtomicLong diskLoadGeneration = new java.util.concurrent.atomic.AtomicLong();
	private static volatile boolean diskLoadInFlight;
	private static net.minecraft.client.world.ClientWorld diskLastWorld;
	private static String cachedServerKey;
	private static boolean initialised;
	private static int program;
	private static int uProj;
	private static int uView;
	private static int uCamPos;
	private static int uAtlas;
	private static int uLightmap;
	private static int uFogColor;
	private static int uWaterY, uFogStart2, uFogEnd2;
	private static int uFogStart;
	private static int uFogEnd;
	private static int uTranslucent, uOit, uCameraUnderwater;
	private static int uWaterSceneColor, uWaterSceneDepth, uWaterWaveTex, uInvProj, uScreenSize;
	private static int uWaterBumpiness, uWaterReflection, uWaterRefraction, uWaterFoam, uWaterSsrSteps;
	private static int uWaterTransparency, uWaterAbsorption, uWaterSpecular;
	private static int uLeavesFarDist;
	private static int uPackLodNear, uPackLodFar, uPackLodBias;
	private static int uTime;
	private static int uDepthOnly;
	private static int uWind;
	private static int uHeldLight;
	private static int uHeldRadius;
	private static int uOreGlow;
	private static long shaderStartMs;
	// Shadow mapping (sun/moon directional shadows).
	private static int shadowProgram;
	private static int suLightMVP;   // shadow program: light view-projection
	private static int suCamPos;     // shadow program: block-aligned high origin
	private static int suCamPosLow;  // shadow program: small origin remainder
	private static int suTime, suWind; // shadow program: wind sway (match main pass)
	private static int suShadowAtlas;  // shadow program: block atlas for cutout alpha-test
	private static int suNearCut;      // shadow program: terrain-only emitter cut (point pass; sun sets 0)
	private static int waterShadowProgram, swLightMVP, swCamPos, swCamPosLow, swTime, swWind, swWaterWaveTex;
	// Entity-box shadow caster program + dynamic geometry
	private static int boxProgram, suBoxLightMVP, suBoxCamPos, suBoxNearCut, boxVao, boxVbo;
	private static java.nio.FloatBuffer boxScratch;
	private static int boxVertCount;
	/** Revision currently resident in the three entity-shadow VBOs; unchanged scenes need no upload. */
	private static long entityShadowVboRevision = Long.MIN_VALUE;
	private static final int MAX_ENTITY_BOXES = 256;
	// Capacity (floats) of the entity-model shadow geometry buffer; matches EntityShadowCapture's CAP.
	private static final int ENTITY_SHADOW_FLOATS = 300_000;
	// Item shadow caster (textured, alpha-discard): pos+uv geometry, drawn with the block atlas.
	private static int itemShadowProgram, suItemLightMVP, suItemCamPos, suItemAtlas, suItemNearCut, itemVao, itemVbo;
	private static int blockItemVao, blockItemVbo, blockItemVertCount; // block-atlas item shadows (held/dropped blocks)
	private static java.nio.FloatBuffer itemScratch;
	private static int itemVertCount;
	private static final int ITEM_SHADOW_FLOATS = 150_000; // 5 floats/vert; matches EntityShadowCapture ICAP
	// Directional CSM: 0=near sharp, 1=mid balanced, 2=far soft.
	private static final int[] uShadowMap = new int[DIRECTIONAL_CASCADE_COUNT];
	private static final int[] uShadowWaterMap = new int[DIRECTIONAL_CASCADE_COUNT];
	private static final int[] uLightMVP = new int[DIRECTIONAL_CASCADE_COUNT];
	private static final int[] uShadowBias = new int[DIRECTIONAL_CASCADE_COUNT];
	private static final int[] uShadowNormalBias = new int[DIRECTIONAL_CASCADE_COUNT];
	private static final int[] uShadowCam = new int[DIRECTIONAL_CASCADE_COUNT];
	private static final int[] uCascadeEnd = new int[DIRECTIONAL_CASCADE_COUNT];
	private static final int[] uCascadeBlend = new int[DIRECTIONAL_CASCADE_COUNT - 1];
	private static int uShadowStrength, uDayFactor, uSunDir, uCelestialColor, uCelestialMoon;
	private static int uDirectionalDir, uDirectionalColor, uDirectionalStrength;
	private static int uSkyAmbientColor, uShadowAmbientColor, uSkyAmbientStrength;
	private static int uHorizonFogColor, uFoliageTransmission, uRainFactor;
	private static int uLightCount, uLights, uLightCols;
	// Terrain point lights (placed light blocks + dropped items + burning entities)
	private static final int MAX_TERRAIN_LIGHTS = 32;
	private static final int LIGHT_CAND_MAX = 256;
	private static final float[] lightCand = new float[LIGHT_CAND_MAX * 7]; // wx,wy,wz,lvl,r,g,b
	private static final float[] bestD2 = new float[MAX_TERRAIN_LIGHTS];
	private static final int[] bestIdx = new int[MAX_TERRAIN_LIGHTS];
	/** Render-thread scratch for the two terrain passes; avoids tiny arrays every frame. */
	private static final float[] EFFECT_TINT = new float[3];
	private static final float[] HELD_LIGHT = new float[4];
	private static java.nio.FloatBuffer lightPosBuf;
	private static java.nio.FloatBuffer lightColBuf;
	private static final float[] orthoHalf = new float[DIRECTIONAL_CASCADE_COUNT];
	private static float sunDirX, sunDirY, sunDirZ;
	private static final double[] shadowCamX = new double[DIRECTIONAL_CASCADE_COUNT];
	private static final double[] shadowCamY = new double[DIRECTIONAL_CASCADE_COUNT];
	private static final double[] shadowCamZ = new double[DIRECTIONAL_CASCADE_COUNT];
	private static final float[] shadowBaseX = new float[DIRECTIONAL_CASCADE_COUNT];
	private static final float[] shadowBaseY = new float[DIRECTIONAL_CASCADE_COUNT];
	private static final float[] shadowBaseZ = new float[DIRECTIONAL_CASCADE_COUNT];
	private static final float[] shadowLowX = new float[DIRECTIONAL_CASCADE_COUNT];
	private static final float[] shadowLowY = new float[DIRECTIONAL_CASCADE_COUNT];
	private static final float[] shadowLowZ = new float[DIRECTIONAL_CASCADE_COUNT];
	private static final int[] shadowFbo = new int[DIRECTIONAL_CASCADE_COUNT];
	private static final int[] shadowTex = new int[DIRECTIONAL_CASCADE_COUNT];
	private static final int[] shadowWaterTex = new int[DIRECTIONAL_CASCADE_COUNT];
	private static final int[] shadowRes = new int[DIRECTIONAL_CASCADE_COUNT];
	// The projection follows the continuously interpolated celestial direction. Translation remains
	// texel-snapped; sub-texel rotation is handled by the compact near-cascade PCF instead of turning
	// the whole projection through visible discrete angular steps.
	private static float shadowBasisX, shadowBasisY, shadowBasisZ;
	private static float shadowRightX, shadowRightY, shadowRightZ;
	private static float shadowUpX, shadowUpY, shadowUpZ;
	private static final GpuPassProfiler GPU_PROFILER = new GpuPassProfiler();
	private static final ShadowOrigin[] SHADOW_ORIGINS = { new ShadowOrigin(), new ShadowOrigin(), new ShadowOrigin() };
	private static final AdaptiveShadowQuality GPU_WORKLOAD = new AdaptiveShadowQuality();
	private static int gpuWorkloadLevel;
	private static long uploadFrameBytesRemaining = Long.MAX_VALUE;
	private static int adaptiveShadowLevel;
	private static final AdaptiveShadowQuality SUN_QUALITY = new AdaptiveShadowQuality();
	private static final float[] shadowDepthSpan = new float[DIRECTIONAL_CASCADE_COUNT];
	private static int adaptivePointShadowLevel;
	private static final AdaptiveShadowQuality POINT_QUALITY = new AdaptiveShadowQuality();
	private static long nextGpuProfileLogMs;
	private static int debugPointShadowPasses;
	private static int debugPointShadowSlots;
	private static int debugPointEntitySlots;
	private static int debugPointRelevant;
	private static int debugSunNearPasses, debugSunMidPasses, debugSunFarPasses;
	private static int debugSunNearDraws, debugSunMidDraws, debugSunFarDraws;
	private static int debugShadowReceivers;
	private static int debugCasterRebuilds;
	private static long debugCasterRebuildTotalNs;
	private static long debugCasterRebuildMaxNs;
	private static int debugRenderFrames;
	private static final Matrix4f[] lightMx = { new Matrix4f(), new Matrix4f(), new Matrix4f() };
	// Render-thread-only scratch: avoid a few permanent tiny allocations in the hottest shadow path.
	private static final Vector3f SHADOW_FWD = new Vector3f();
	private static final Vector3f SHADOW_RIGHT = new Vector3f();
	private static final Vector3f SHADOW_UP = new Vector3f();
	private static final int[] DRAW_VIEWPORT_SCRATCH = new int[4];
	private static final int[] POINT_VIEWPORT_SCRATCH = new int[4];
	private static final int[] SUN_VIEWPORT_SCRATCH = new int[4];
	private static float dayFactor;
	private static float sunUpFactor; // 1 = actual sun overhead, 0 = night/moon (torch↔celestial shadow interplay)
	private static boolean celestialMoon;
	private static float celestialR = 1f, celestialG = 0.95f, celestialB = 0.84f;

	// MULTI-LIGHT point shadows (config pointLightShadows): up to POINT_MAX lights cast simultaneously.
	// Each slot owns a 3×2 cube-face block in ONE shared depth atlas (stacked vertically) and 6 matrices
	// that depend only on its range (position folds in via world−lightPos in the depth pass). Slots are
	// STICKY: a light keeps its slot while it exists, so nothing strobes when several sources compete —
	// the old single-hero pick relaid every shadow each time the winner changed. Reuses shadowProgram.
	private static final int POINT_MAX = 8;
	private static int pointShadowFbo, pointShadowTex, pointShadowRes; // cached terrain depth
	/** Bounds the shared terrain atlas independently of slot count and requested resolution. */
	private static final long POINT_SHADOW_ATLAS_PIXEL_BUDGET = 32L * 1024L * 1024L;
	private static final int POINT_VOXEL_SIZE = 32;
	private static final int POINT_VOXEL_VOLUME = POINT_VOXEL_SIZE * POINT_VOXEL_SIZE * POINT_VOXEL_SIZE;
	/** Flattened 32^3 R8UI volumes; avoids unstable AMD 3D-texture uploads. */
	private static int pointVoxelTex;
	private static final ByteBuffer POINT_VOXEL_UPLOAD = ByteBuffer.allocateDirect(POINT_VOXEL_VOLUME);
	private static final int[] pVoxelOriginX = new int[POINT_MAX], pVoxelOriginY = new int[POINT_MAX],
			pVoxelOriginZ = new int[POINT_MAX];
	private static final boolean[] pVoxelValid = new boolean[POINT_MAX];
	private static int pointEntityShadowFbo, pointEntityShadowTex, pointEntityShadowRes; // live entity/item depth
	private static final Matrix4f[] pointFaceMx = new Matrix4f[POINT_MAX * 6];
	static {
		for (int i = 0; i < pointFaceMx.length; i++) {
			pointFaceMx[i] = new Matrix4f();
		}
	}
	private static final float[] pLightX = new float[POINT_MAX], pLightY = new float[POINT_MAX],
			pLightZ = new float[POINT_MAX], pLightRange = new float[POINT_MAX];
	private static final float[] pLightR = new float[POINT_MAX], pLightG = new float[POINT_MAX],
			pLightB = new float[POINT_MAX];
	private static final boolean[] pLightValid = new boolean[POINT_MAX];
	private static int cachedMaxTextureSize;
	/** True only when the light sphere can touch terrain that contributes to the current image. */
	private static final boolean[] pLightRelevant = new boolean[POINT_MAX];
	private static final boolean[] pLightDyn = new boolean[POINT_MAX]; // held/dropped — no vanilla block light
	private static final boolean[] pLightEmbedded = new boolean[POINT_MAX]; // source sits inside a carrier model
	private static final boolean[] pLightSolid = new boolean[POINT_MAX]; // placed opaque cube needs self-caster removal
	private static final int[] pLightFaceMask = new int[POINT_MAX]; // exposed solid faces: +X,-X,+Y,-Y,+Z,-Z
	// Per-slot shadow fade 0..1 (ramps in on assign, ramps out before the slot is freed). Decouples a
	// slot being OCCUPIED from a light's shadow being at FULL strength, so sources entering/leaving the
	// 8 cast slots never pop the room's darkness. Stepped once per shadow pass in selectPointLights().
	private static final float[] pLightFade = new float[POINT_MAX];
	private static final float[] POINT_FADE_TARGET = new float[POINT_MAX]; // per-pass scratch (reused, not persisted)
	private static final float POINT_DYNAMIC_ENTER_SCORE = 0.5f;
	// A placed lamp's physically lit pool can remain clearly visible while the player is far outside
	// its 15-block emission radius. Discover it ahead of time and retain it across the whole view rather
	// than making the shadow suddenly appear only when the player walks into the pool.
	private static final float POINT_STATIC_ENTER_SCORE = -(RADIUS_CAP * 16f + 16f);
	private static final float POINT_STATIC_EXIT_SCORE = -(RADIUS_CAP * 16f + 32f);
	private static int uPointLightMVP, uPointShadowMap, uPointEntityShadowMap, uPointVoxelMap,
			uPointData, uPointCols, uPointVoxelOrigin, uPointVoxelValid,
			uPointDynA, uPointSolidA, uPointFaceMask,
			uPointFade_, uPointShadowStr;
	private static int uSunUp;
	private static int cachedAtlasGlId = -1;
	private static int cachedItemsAtlasGlId = -1;
	private static int atlasSampler;
	private static float maxAnisoCached = -1f;
	/** Last sampler inputs; terrain is drawn twice per frame, so unchanged GL sampler state is not resent. */
	private static int atlasSamplerConfigKey = Integer.MIN_VALUE;
	private static boolean waterSpriteResolved;
	private static boolean waterFlowResolved;
	private static boolean waterOverlayResolved;
	private static boolean lavaResolved;
	private static boolean lavaFlowResolved;
	private static long lastFluidResolveMs;        // last time we re-checked the atlas (resourcepack swap detect)
	private static float resolvedWaterU0 = Float.NaN; // water sprite U when last resolved; change = atlas re-stitched
	private static long firstWorldMs;
	/** True for a normal player in a closed underground area; avoids moving shadow maps altering cave light. */
	private static boolean undergroundView;
	// Residency scans used to walk every live section (and sample its heightmap) on every render frame.
	// The state only changes with movement/mode/configuration; a short cadence remains solely for the
	// eviction timer.  This removes a large render-thread CPU cost in dense multiplayer worlds.
	private static net.minecraft.client.world.ClientWorld residencyWorld;
	private static int lastResidencyCx = Integer.MIN_VALUE, lastResidencySy = Integer.MIN_VALUE,
			lastResidencyCz = Integer.MIN_VALUE;
	private static boolean lastResidencyUnderground, lastResidencySpectator;
	private static int lastResidencyConfigSignature = Integer.MIN_VALUE;
	private static long nextUndergroundResidencySweepMs;
	// Residency changes are section-scale and delayed underground eviction is already measured in
	// seconds. A 125 ms full scan of every resident mesh competed directly with fast chunk streaming.
	private static final long UNDERGROUND_RESIDENCY_SWEEP_MS = 500L;
	// Snapshot extraction only reads already-received ClientWorld chunks, so a multi-second blind
	// warm-up buys no safety and makes multiplayer joins look stalled. A short delay lets the first
	// packet burst settle without withholding the first visible terrain ring.
	private static final long WORLD_STREAM_WARMUP_MS = 250L;
	private static boolean captured;
	/** our own GL FBO, lazily created, that wraps MC's real color+depth attachments. */
	private static int mcFbo;
	// Legacy MSAA targets are intentionally unreachable: the option and all call sites were removed.
	// They remain only until the old isolated helper block can be deleted as one mechanical cleanup.
	private static int msaaFbo, msaaColorRb, msaaDepthRb;
	private static int resolveFbo, resolveColorTex, resolveDepthTex;
	private static int msaaW, msaaH, msaaActiveSamples;
	private static int aaCompositeProg, aaCompColor, aaCompDepth, aaVao;
	private static int oitFbo, oitAccumTex, oitRevealTex, oitW, oitH, oitDepthTex;
	private static int oitCompositeProg, oitCompAccum, oitCompReveal, oitVao;
	private static boolean weightedOitActive;
	/** The vanilla translucent hook closes entity capture; actual alpha composition runs after particles. */
	private static boolean lateTranslucentPending;
	private static boolean oitDisabledWarningLogged;
	/** 0 unknown, 1 OpenGL 4.0 core, 2 ARB extension, 3 AMD extension, -1 unsupported. */
	private static int oitIndependentBlendApi;

	// --- shared arena + multidraw (Phase 5): all sections live in two big buffers and
	// are drawn with one glMultiDrawElementsIndirect call per layer. ---
	// Sized generously: glass/water-heavy scenes have a huge translucent vertex count, and a
	// transient arena-full used to leave permanent holes. The vertex side is deliberately larger
	// than the index side because opaque layers share their quad indices.
	private static final long VERTEX_ARENA_BYTES = 640L * 1024 * 1024;
	private static final long INDEX_ARENA_BYTES = 160L * 1024 * 1024;
	// Every opaque block-model layer has the same quad topology (0,1,2, 0,2,3 ...). Reserve one
	// shared pattern instead of allocating, filling and uploading an identical index slice for every
	// streamed section. 262k vertices covers even very dense resource-pack sections; pathological
	// larger layers retain the private-index fallback.
	private static final int SHARED_QUAD_INDEX_MAX_VERTICES = 262_144;
	private static long sharedQuadIndexOffset = -1L;
	private static int sharedQuadIndexCount;
	private static final int MAX_DRAWS = 1 << 16; // commands/instances we can build per frame
	// Phase 2.3: off-thread meshing executor + per-frame result drain buffer
	private static ChunkMeshExecutor executor;
	private static final List<ChunkMeshExecutor.MeshResult> RESULT_SCRATCH = new ArrayList<>();

	/**
	 * Meshes drained from the workers but not uploaded yet.
	 *
	 * <p>{@code maxChunkUploadsPerFrame} is a COUNT, and a count is the wrong unit: an upload is a
	 * {@code glBufferSubData} into the vertex arena, so its cost scales with the section's geometry.
	 * A frame that drew 128 dense forest sections stalled far longer than one that drew 128 empty
	 * cave sections, and at the default of 128 a chunk-load burst put the whole spike into a single
	 * frame — a visible hitch even though the second-averaged GPU and mesh numbers looked fine.
	 *
	 * <p>Uploads now run against a TIME budget and whatever does not fit stays here for the next
	 * frame, so the burst is spread instead of dropped. The count still applies as the ceiling on how
	 * much is pulled off the workers per frame, which keeps this queue bounded.
	 */
	private static final java.util.ArrayDeque<ChunkMeshExecutor.MeshResult> UPLOAD_BACKLOG = new java.util.ArrayDeque<>();
	/** One dense mesh whose arena allocations are filled over multiple frames and published atomically. */
	private static IncrementalMeshUpload activeIncrementalUpload;
	private static final int MIN_INCREMENTAL_UPLOAD_BYTES = 512 * 1024;
	private static final int INDEX_UPLOAD_SCRATCH_INTS = 32 * 1024;
	private static java.nio.IntBuffer incrementalIndexScratch;

	private static final class IncrementalLayerUpload {
		ByteBuffer vertices;
		int vertexBytes;
		int vertexCopied;
		int vertexCount;
		int indexCount;
		int indexCopied;
		long vertexOffset;
		long indexOffset;
		boolean sharedIndices;
	}

	private static final class IncrementalMeshUpload {
		final ChunkMeshExecutor.MeshResult result;
		final Sec section;
		final IncrementalLayerUpload solid;
		final IncrementalLayerUpload translucent;

		IncrementalMeshUpload(ChunkMeshExecutor.MeshResult result, Sec section,
				IncrementalLayerUpload solid, IncrementalLayerUpload translucent) {
			this.result = result;
			this.section = section;
			this.solid = solid;
			this.translucent = translucent;
		}
	}
	/** Failed arena allocations are hidden from the prioritiser until the end of this frame. */
	private static final java.util.ArrayList<ChunkMeshExecutor.MeshResult> ARENA_DEFERRED_SCRATCH = new java.util.ArrayList<>(8);
	private static final int MAX_ARENA_FAILURES_PER_FRAME = 8;
	/**
	 * Keys in {@link #UPLOAD_BACKLOG}. A worker key leaves ChunkMeshExecutor#inFlight when its result
	 * is drained, but may wait several frames for the GL upload budget. Light notifications during
	 * that gap must still retain their revision so the stale completed mesh fails upload validation.
	 */
	private static final it.unimi.dsi.fastutil.longs.LongOpenHashSet UPLOAD_BACKLOG_KEYS =
			new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
	/**
	 * Cross-section edit publication order. Per-section double buffering alone is insufficient at a
	 * section boundary: publishing a removed boundary block before its neighbour has emitted the newly
	 * exposed face creates a short x-ray hole. The old waiter mesh remains visible until every dependency
	 * has published its current content revision.
	 */
	private static final UploadDependencyGraph UPLOAD_DEPENDENCIES = new UploadDependencyGraph();
	// Cache restore performs native copies and GL uploads just like a fresh mesh.  A cache hit is more
	// valuable than a new mesh (it fills already-known terrain immediately), but it must still share the
	// mesh-upload frame budget so reconnecting never trades a faster world for a join-frame hitch.
	// When no fresh upload is waiting, use a slightly larger finite slice to make an on-disk cache useful
	// in practice; when streaming is active, retain the conservative slice for p99 frame time.
	private static final int CACHE_INSTALL_BUSY_MAX_PER_FRAME = 8;
	private static final long CACHE_INSTALL_BUSY_BUDGET_NANOS = 1_000_000L;
	private static final int CACHE_INSTALL_IDLE_MAX_PER_FRAME = 24;
	private static final long CACHE_INSTALL_IDLE_BUDGET_NANOS = 3_000_000L;

	/** Lightweight live pipeline counters for the performance HUD. Render thread only. */
	public static int pendingSnapshotCount() { return SNAPSHOT_JOBS.size(); }
	public static int sectionCount() { return SECTIONS.size(); }
	private static void logSnapshotQueueState() {
		if (SNAPSHOT_JOBS.isEmpty()) return;
		int[] stages = new int[5];
		int urgent = 0, surface = 0, visible = 0, background = 0, resident = 0, provisional = 0;
		for (var entry : SNAPSHOT_JOBS.long2ObjectEntrySet()) {
			SnapshotJob job = entry.getValue();
			stages[Math.max(0, Math.min(stages.length - 1, job.builder.debugStage()))]++;
			if (job.streamPriority <= STREAM_URGENT) urgent++;
			else if (job.streamPriority == STREAM_SURFACE) surface++;
			else if (job.streamPriority == STREAM_VISIBLE) visible++;
			else background++;
			if (SECTIONS.containsKey(entry.getLongKey())) resident++;
			if (job.builder.debugAllowsProvisionalLight()) provisional++;
		}
		MoneyakShaders.LOGGER.info("[Plan C/GL] snapshot-state blocks/biomes/sources/relight/ready={}/{}/{}/{}/{} priority urgent/surface/visible/background={}/{}/{}/{} resident={} provisional={}",
				stages[0], stages[1], stages[2], stages[3], stages[4], urgent, surface, visible, background,
				resident, provisional);
		MoneyakShaders.LOGGER.info("[Plan C/GL] snapshot-dispatch urgent/stream={}/{} preserved/restarted={}/{} targetMs={} snapshotAllowanceMs={}",
				HIGH_SNAPSHOT_QUEUE.size(), STREAM_SNAPSHOT_QUEUE.size(), snapshotUpdatesPreserved, snapshotUpdatesRestarted,
				com.moneyakshaders.client.FrameWorkBudget.effectiveTargetMs(),
				com.moneyakshaders.client.FrameWorkBudget.allowanceMs(com.moneyakshaders.client.FrameWorkBudget.BUCKET_SNAPSHOT));
		MoneyakShaders.LOGGER.info("[Plan C/GL] block-edit queued={} preparationMs={}", EDIT_SNAPSHOT_QUEUE.size(),
				com.moneyakshaders.client.FrameWorkBudget.consumedMs("block-edit"));
		logSurfaceCoverageState();
	}

	/** Expensive only at the 15-second diagnostic cadence; identifies which pipeline state owns a hole. */
	private static void logSurfaceCoverageState() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.world == null || client.player == null) return;
		int pcx = client.player.getBlockX() >> 4, pcz = client.player.getBlockZ() >> 4;
		int radius = Math.min(radiusChunks, 16);
		int expected = 0, active = 0, sleeping = 0, emptyMesh = 0, emptyLiveExposed = 0;
		int pending = 0, remesh = 0, orphaned = 0;
		StringBuilder samples = new StringBuilder();
		StringBuilder emptySamples = new StringBuilder();
		int emptyExposureScanBudget = 64;
		int bottomSection = client.world.getBottomY() >> 4;
		for (int cx = pcx - radius; cx <= pcx + radius; cx++) {
			for (int cz = pcz - radius; cz <= pcz + radius; cz++) {
				var chunk = client.world.getChunk(cx, cz, net.minecraft.world.chunk.ChunkStatus.FULL, false);
				if (chunk == null) continue;
				long mask = surfaceSectionMask(client.world, cx, cz);
				int topSurfaceIndex = mask == 0L ? -1 : 63 - Long.numberOfLeadingZeros(mask);
				while (mask != 0L) {
					int index = Long.numberOfTrailingZeros(mask);
					mask &= mask - 1L;
					int sy = bottomSection + index;
					if (targetSectionIsEmpty(chunk, sy)) continue;
					expected++;
					long key = ChunkSectionPos.asLong(cx, sy, cz);
					Sec section = SECTIONS.get(key);
					if (section != null) {
						if (section.residency == Sec.Residency.ACTIVE) active++; else sleeping++;
						if (section.count + section.wcount == 0) {
							emptyMesh++;
							// Prefer the top surface section. A low-to-high scan spent the whole diagnostic
							// budget on legitimately enclosed stone and never sampled the white holes above.
							boolean topSurface = index == topSurfaceIndex;
							if ((topSurface || emptyExposureScanBudget-- > 0)
									&& liveSectionHasExposedBlock(client.world, chunk, sy)) {
								emptyLiveExposed++;
								if (emptySamples.length() < 160) {
									if (emptySamples.length() > 0) emptySamples.append(' ');
									emptySamples.append(cx).append('/').append(sy).append('/').append(cz);
								}
							}
						}
					} else if (hasPendingSectionMesh(key)) {
						pending++;
					} else if (REMESH.contains(key)) {
						remesh++;
					} else {
						orphaned++;
						if (samples.length() < 160) {
							if (samples.length() > 0) samples.append(' ');
							samples.append(cx).append('/').append(sy).append('/').append(cz);
						}
					}
				}
			}
		}
		MoneyakShaders.LOGGER.info(
				"[Plan C/GL] surface-coverage expected={} active={} sleeping={} emptyMesh={} emptyLiveExposed={}/{} pending={} remesh={} orphaned={} orphanSamples={} emptySamples={}",
				expected, active, sleeping, emptyMesh, emptyLiveExposed,
				Math.min(emptyMesh, 64), pending, remesh, orphaned, samples, emptySamples);
	}

	/** Diagnostic only: compare an empty GPU mesh with the current live section contents. */
	private static boolean liveSectionHasExposedBlock(net.minecraft.client.world.ClientWorld world,
			net.minecraft.world.chunk.Chunk chunk, int sectionY) {
		int sectionIndex = chunk.getSectionIndex(sectionY << 4);
		net.minecraft.world.chunk.ChunkSection[] sections = chunk.getSectionArray();
		if (sectionIndex < 0 || sectionIndex >= sections.length) return false;
		net.minecraft.world.chunk.ChunkSection section = sections[sectionIndex];
		if (section == null || section.isEmpty()) return false;
		for (int y = 0; y < 16; y++) {
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					if (section.getBlockState(x, y, z).isAir()) continue;
					if (y < 15) {
						if (section.getBlockState(x, y + 1, z).isAir()) return true;
					} else if (world.getBlockState(new net.minecraft.util.math.BlockPos(
							(chunk.getPos().x << 4) + x, (sectionY << 4) + 16,
							(chunk.getPos().z << 4) + z)).isAir()) {
						return true;
					}
				}
			}
		}
		return false;
	}
	/** Render-thread query used to discard light refreshes that have no GPU mesh to refresh yet. */
	public static boolean hasResidentSection(long key) { return SECTIONS.containsKey(key); }
	/** A first mesh already captures the current light revision; it needs validation, not a second remesh job. */
	public static boolean hasPendingSectionMesh(long key) {
		return SNAPSHOT_JOBS.containsKey(key) || UPLOAD_BACKLOG_KEYS.contains(key)
				|| activeIncrementalUpload != null && activeIncrementalUpload.result.key() == key
				|| (executor != null && executor.isInFlight(key));
	}
	public static int sleepingSectionCount() { return SCENE_RESIDENCY.sleepingCount(); }
	public static long sleepingGpuBytes() { return SCENE_RESIDENCY.sleepingBytes(); }
	public static int evictedUndergroundCount() { return EVICTED_UNDERGROUND.size(); }
	public static long evictedUndergroundBytes() { return evictedUndergroundBytes; }
	public static int meshInFlightCount() { return executor == null ? 0 : executor.inFlightCount(); }
	public static int uploadBacklogCount() { return UPLOAD_BACKLOG.size(); }
	public static boolean incrementalUploadInFlight() { return activeIncrementalUpload != null; }
	public static int pooledSnapshotCount() { return SectionInputSnapshot.pooledCount(); }
	/** Decoded disk-cache sections still awaiting their bounded render-thread install. */
	public static int pendingDiskCacheInstallCount() { return PENDING_INSTALL.size(); }

	/**
	 * Readiness for the multiplayer level-entry gate. A resident custom mesh is ideal, but the gate
	 * itself can prevent the custom render pipeline from advancing during a server world switch. In
	 * that state waiting for a mesh creates a circular dependency and Minecraft only escapes through
	 * its hard timeout. Once the player's FULL chunk column has arrived, releasing the overlay is safe:
	 * the custom renderer can immediately submit the urgent 3x3x3 neighbourhood while the already
	 * received world data remains authoritative. This may expose a very short progressive first draw,
	 * but cannot expose stale data from the previous world.
	 */
	public static boolean isEntryRenderingReady(BlockPos playerPos) {
		if (playerPos == null) return false;
		int playerCx = playerPos.getX() >> 4;
		int playerCz = playerPos.getZ() >> 4;
		for (Sec section : SECTIONS.values()) {
			if (section.residency == Sec.Residency.ACTIVE && section.count + section.wcount > 0
					&& Math.abs(section.cx - playerCx) <= 1 && Math.abs(section.cz - playerCz) <= 1) {
				return true;
			}
		}
		MinecraftClient client = MinecraftClient.getInstance();
		return client != null && client.world != null
				&& client.world.getChunk(playerCx, playerCz,
						net.minecraft.world.chunk.ChunkStatus.FULL, false) != null;
	}
	public static boolean diskCacheLoadInFlight() { return diskLoadInFlight; }

	/**
	 * Picks the closest completed mesh for upload. Completion order is arbitrary across workers, so
	 * FIFO could let a far dense section delay terrain directly in front of the player. A bounded age
	 * bonus retains eventual progress for far sections during continuous movement.
	 */
	private static ChunkMeshExecutor.MeshResult pollPrioritisedUpload(int playerCx, int playerSy, int playerCz) {
		ChunkMeshExecutor.MeshResult best = null;
		ChunkMeshExecutor.MeshResult oldestBlocked = null;
		long bestScore = Long.MAX_VALUE;
		long nowNs = System.nanoTime();

		for (ChunkMeshExecutor.MeshResult candidate : UPLOAD_BACKLOG) {
			if (!uploadDependenciesSatisfied(candidate.key())) {
				TerrainEditDebug.blocked(candidate.key());
				if (oldestBlocked == null || candidate.readyNs() < oldestBlocked.readyNs()) oldestBlocked = candidate;
				continue;
			}

			long dx = (candidate.bx() >> 4) - playerCx;
			long dy = (candidate.by() >> 4) - playerSy;
			long dz = (candidate.bz() >> 4) - playerCz;
			long locality = dx * dx + dz * dz + (dy * dy << 1);
			long ageBonus = Math.min(4096L, Math.max(0L, nowNs - candidate.readyNs()) / 10_000_000L);
			long score = candidate.streamPriority() * STREAM_TIER_SCORE + Math.max(0L, locality - ageBonus);
			if (score < bestScore) {
				bestScore = score;
				best = candidate;
			}
		}

		// A current mesh is always better than rebuilding the exact same result forever. Dependency
		// ordering is only a transient seam-prevention aid; after 250 ms drop this waiter's requirements
		// and publish the already-complete current mesh. Its dependants remain blocked until this publish.
		if (best == null && oldestBlocked != null && nowNs - oldestBlocked.readyNs() >= 250_000_000L) {
			TerrainEditDebug.retry(oldestBlocked.key());
			UPLOAD_DEPENDENCIES.remove(oldestBlocked.key());
			best = oldestBlocked;
			if (!dependencyDeadlockWarned) {
				dependencyDeadlockWarned = true;
				MoneyakShaders.LOGGER.warn(
						"[Plan C/GL] upload dependency wait exceeded 250 ms; forcing current mesh publish instead of rebuilding it");
			}
		}

		if (best != null) {
			UPLOAD_BACKLOG.remove(best);
			UPLOAD_BACKLOG_KEYS.remove(best.key());
		}
		return best;
	}

	private static boolean hasReadyUrgentUpload() {
		for (ChunkMeshExecutor.MeshResult candidate : UPLOAD_BACKLOG) {
			if (candidate.streamPriority() <= STREAM_URGENT
					&& uploadDependenciesSatisfied(candidate.key())) return true;
		}
		return false;
	}

	private static boolean uploadDependenciesSatisfied(long key) {
		return UPLOAD_DEPENDENCIES.ready(key, dependency -> {
			Sec resident = SECTIONS.get(dependency);
			return EVICTED_UNDERGROUND.contains(dependency)
					|| resident != null && resident.residency != Sec.Residency.ACTIVE
					|| contentRevision(dependency) == 0L;
		});
	}

	private static void addUploadDependency(long waiter, long dependency) {
		if (contentRevision(dependency) != 0L) UPLOAD_DEPENDENCIES.add(waiter, dependency);
	}

	private static boolean dependencyDeadlockWarned;
	/**
	 * Per-frame GL upload budget. 2 ms leaves room for the rest of a 60 fps frame (16.7 ms) while
	 * still streaming several hundred sections a second. Always uploads at least one section, so
	 * loading can never stall completely no matter how heavy a single section is.
	 */
	private static final long UPLOAD_BUDGET_NANOS = 2_000_000L;

	/**
	 * Combined budget gate for the upload drain. Always allows at least one section per frame so a
	 * single heavy section can never block loading. When {@code useFrameBudget} is true, defers to
	 * the shared {@link com.moneyakshaders.client.FrameWorkBudget} bucket (adaptive); otherwise
	 * uses the fixed {@link #UPLOAD_BUDGET_NANOS} deadline.
	 */
	private static boolean checkUploadBudget(int uploaded, long deadlineNs, boolean useFrameBudget) {
		if (uploaded < 1) return false;
		if (uploadFrameBytesRemaining <= 0) return true;
		if (useFrameBudget) {
			return !com.moneyakshaders.client.FrameWorkBudget.hasBudget(
					com.moneyakshaders.client.FrameWorkBudget.BUCKET_MESH_UPLOAD);
		}
		return System.nanoTime() >= deadlineNs;
	}

	private static boolean uploadResultStillCurrent(ChunkMeshExecutor.MeshResult result) {
		if (result.contentRevision() != contentRevision(result.key())) return false;
		return result.lightGeneration() < 0L
				|| result.lightGeneration() == com.moneyakshaders.client.ClientLightDispatcher
						.renderLightRevisionSignature(result.bx() >> 4, result.by() >> 4, result.bz() >> 4);
	}

	private static void rejectStaleUploadResult(ChunkMeshExecutor.MeshResult result) {
		TerrainEditDebug.stale(result.key(), result.contentRevision(), contentRevision(result.key()), result.lightGeneration());
		com.moneyakshaders.client.DebugStats.staleMeshes.incrementAndGet();
		result.mesh().free();
		executor.releaseReadyResultSlot(result.streamPriority());
		REMESH.add(result.key());
	}

	/** Publish only a completely uploaded section; old geometry remains visible until this atomic swap. */
	private static void publishUploadedMesh(ChunkMeshExecutor.MeshResult result, Sec section, boolean timeline) {
		section.missingColumnMask = result.missingColumnMask();
		result.mesh().free();
		executor.releaseReadyResultSlot(result.streamPriority());
		section.cx = result.bx() >> 4;
		section.sy = result.by() >> 4;
		section.cz = result.bz() >> 4;
		section.bx = result.bx();
		section.by = result.by();
		section.bz = result.bz();
		dropOrphan(result.key());
		Sec old = SECTIONS.put(result.key(), section);
		if (old != null) section.appearanceStartNs = old.appearanceStartNs;
		SCENE_RESIDENCY.register(result.key());
		boolean visibilityChanged = old == null || old.visibility != section.visibility;
		if (visibilityChanged) visibilityDirty = true;
		TerrainEditDebug.publish(result.key(), result.contentRevision(), result.lightGeneration(), visibilityChanged);
		castersDirty = true;

		sunTerrainRevision++;

		invalidateStaticPointMapsForSection(section.cx, section.sy, section.cz, old != null);
		boolean placedLightSetChanged = old == null
				? section.placedLightCount > 0 : placedLightsChanged(old, section);
		if (placedLightSetChanged) {
			heroScanCooldown = 0;
			refreshPlacedLightConsumers(section, result.key());
		}
		CONTENT_REVISIONS.remove(result.key(), result.contentRevision());
		if (contentRevision(result.key()) == 0L) LOCAL_BLOCK_EDITS.remove(result.key());
		UPLOAD_DEPENDENCIES.published(result.key());
		if (result.lightGeneration() < 0L) {
			long provisionalNowMs = System.currentTimeMillis();
			long revision = com.moneyakshaders.client.ClientLightDispatcher.renderLightRevisionSignature(
					section.cx, section.sy, section.cz);

			if (PROVISIONAL_RETRY_REVISIONS.getOrDefault(result.key(), Long.MIN_VALUE) != revision) {
				PROVISIONAL_RELIGHTS.put(
						result.key(),
						new ProvisionalRelight(revision, provisionalNowMs));
			} else {
				PROVISIONAL_RELIGHTS.remove(result.key());
			}
		} else {
			PROVISIONAL_RELIGHTS.remove(result.key());
			PROVISIONAL_RETRY_REVISIONS.remove(result.key());
		}
		EVICTED_UNDERGROUND.remove(result.key());
		// Opaque-only arrivals do not affect alpha order. Invalidating this cache for every forest
		// section made chunk bursts repeatedly rebuild the complete water/glass list.
		if (section.wcount > 0 || old != null && old.wcount > 0) translucentOrderRevision++;
		if (old != null) freeSection(old);
		if (timeline) {
			com.moneyakshaders.client.ChunkLoadTimeline.mark(result.key(),
					com.moneyakshaders.client.ChunkLoadTimeline.PHASE_UPLOAD_DONE);
		}
	}
	// Phase 3 / frustum culling: 6 planes (a,b,c,d) extracted from proj*view each draw,
	// combined scratch matrix so we don't allocate per frame.
	private static final float[] frustumPlanes = new float[24];
	// Per-plane |normal| cached in updateFrustum so sectionVisible can compare signed distances in
	// world units (blocks) instead of un-normalized plane-space, which is what the rotation-prefetch
	// bloat is expressed in.
	private static final float[] frustumPlaneLen = new float[6];
	// Camera forward vector last frame + smoothed bloat distance (in blocks). Spec §5.2 prefetch:
	// when angular velocity is high, widen every plane by bloatBlocks so sections about to slide
	// into view are drawn immediately. Decays exponentially back to 0 when rotation stops.
	private static float prevFwdX, prevFwdY, prevFwdZ;
	private static boolean havePrevFwd;
	private static float currentBloatBlocks;
	private static final Matrix4f combinedScratch = new Matrix4f();
	// Phase 4.2: BFS visibility. Rebuilt only when the player moves to a new section.
	// Sections NOT in this set are occluded (behind solid terrain) and skipped.
	private static it.unimi.dsi.fastutil.longs.LongOpenHashSet VISIBLE_SECTIONS = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

	/**
	 * Sections whose geometry can cast a shadow onto something the camera can actually see — the only
	 * ones worth rasterising into the shadow maps. Built by sweeping every camera-visible section
	 * towards the sun ({@link #rebuildShadowCasters}): a section the sweep never lands on can only
	 * darken geometry that is itself occluded, so its shadow is unobservable.
	 *
	 * <p>Empty means "unknown" — the shadow pass then falls back to drawing the whole radius, so a
	 * missing graph can never silently delete shadows.
	 */
	private static final it.unimi.dsi.fastutil.longs.LongOpenHashSet SHADOW_CASTERS = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
	/**
	 * Sections that have been emitted into at least one draw list this session. Used to mark
	 * {@link com.moneyakshaders.client.ChunkLoadTimeline#PHASE_FIRST_DRAWN}. Session-scoped;
	 * cleared on world change alongside {@link #SECTIONS}.
	 */
	private static final it.unimi.dsi.fastutil.longs.LongOpenHashSet DRAWN_ONCE = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
	// Rebuild triggers: the sweep only changes when the camera's section, the visible set or the sun
	// direction moves. All three are slow, so this costs ~nothing per frame in the steady state.
	private static long casterSectionKey = Long.MIN_VALUE;
	private static float casterSunX, casterSunY, casterSunZ = Float.NaN;
	private static float casterViewX, casterViewY, casterViewZ = Float.NaN;
	private static boolean shadowCastersKnown;
	private static boolean castersDirty = true;
	private static final boolean[] directionalShadowReady = new boolean[DIRECTIONAL_CASCADE_COUNT];
	private static final boolean[] directionalShadowDirty = { true, true, true };
	private static long sunTerrainRevision;
	private static final long[] directionalTerrainRevision = { Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE };

	private static long lastSunEntityRevision = Long.MIN_VALUE;
	/**
	 * How far a shadow is allowed to travel when sweeping receivers towards the sun. The actual reach
	 * is {@code MIN / sin(elevation)}, clamped to MAX: overhead sun ⇒ short shadows ⇒ short sweep;
	 * sunrise/sunset ⇒ long shadows ⇒ the full range. Overshooting only costs a little extra drawing;
	 * undershooting would visibly clip a real shadow, so both bounds lean generous.
	 */
	private static final float CASTER_SWEEP_BLOCKS_MIN = 128f;
	private static final float CASTER_SWEEP_BLOCKS_MAX = 512f;
	/** Keep receivers just outside the screen so a gentle turn never exposes an unprepared shadow edge. */
	private static final float SHADOW_RECEIVER_FRUSTUM_MARGIN_BLOCKS = 64f;
	/** About 12 degrees: the 64-block receiver margin safely covers the interval at the far cascade. */
	private static final float SHADOW_RECEIVER_VIEW_DOT = 0.9781476f;
	/** Streaming uploads invalidate the graph in bursts; coalesce them instead of sweeping every 4 frames. */
	private static final long SHADOW_CASTER_DIRTY_INTERVAL_NS = 200_000_000L;
	private static long nextShadowCasterDirtyRebuildNs;
	private static long lastVisSectionKey = Long.MIN_VALUE; // cached player section key; avoids per-frame BFS rebuild on rotation
	private static volatile boolean visibilityDirty = false; // a section (re)meshed → connectivity changed, rebuild the graph
	// A stream of uploads can mark the graph dirty every frame. The graph already treats unmeshed
	// sections as transparent, so coalescing connectivity-only updates cannot hide fresh terrain.
	private static final int VISIBILITY_DIRTY_REBUILD_INTERVAL_FRAMES = 4;
	private static long visibilityFrame;
	private static long lastVisibilityRebuildFrame = Long.MIN_VALUE / 2;
	private static final SceneVisibilityGraph VISIBILITY_GRAPH = new SceneVisibilityGraph(350_000);
	private static int visibilitySettings;
	private static boolean visibilityWasUnknown;
	// Phase 4.3: scratch list for back-to-front sorting of the translucent (water) layer.
	private static final ArrayList<Sec> TRANSLUCENT_SCRATCH = new ArrayList<>();
	// Section-level transparency order is independent of camera rotation. Reuse the exact sorted list
	// while the camera position and translucent mesh revision are unchanged; dense water otherwise pays
	// an O(n log n) sort every single frame even when the player only turns their head.
	private static long translucentOrderRevision;
	private static long translucentOrderCachedRevision = Long.MIN_VALUE;
	private static long activeTranslucentCachedRevision =
			Long.MIN_VALUE;

	private static boolean activeTranslucentCached;	
	// Half-block cells prevent sub-pixel camera movement from comparator-sorting a large ocean every
	// frame while still refreshing order well before crossing a section boundary.
	private static long translucentOrderCamCellX, translucentOrderCamCellY, translucentOrderCamCellZ;
	// Opaque sections sorted NEAR→FAR so the GPU's early-Z rejects occluded fragments before running the
	// (expensive) terrain fragment shader — cuts overdraw shading without a per-frame comparison sort.
	private static final OpaqueDrawOrder<Sec> OPAQUE_SCRATCH = new OpaqueDrawOrder<>();
	private static GlArena vertexArena;
	private static GlArena indexArena;
	private static int sharedVao;
	private static int instanceBuffer; // per-frame: section origin + appearance (vec4), attrib divisor 1
	private static int indirectBuffer; // per-frame: DrawElementsIndirectCommand[drawCount]
	private static java.nio.ByteBuffer indirectScratch;
	private static java.nio.FloatBuffer instanceScratch;
	/** Opaque and translucent terrain use the same program in one world frame. */

	// Exact MC world-render state captured at WorldRenderer.render HEAD, so our terrain
	// projects + depth-tests + fogs identically to vanilla (same frame as our draw).
	// WorldRenderer captures and consumes these on the render thread in the same frame. Reuse the
	// matrices instead of allocating two Matrix4f objects for every rendered frame.
	private static final Matrix4f capturedModelView = new Matrix4f();
	private static final Matrix4f capturedProjection = new Matrix4f();
	private static final Matrix4f waterInvProjectionScratch = new Matrix4f();
	private static volatile double capCamX;
	private static volatile double capCamY;
	private static volatile double capCamZ;
	private static volatile float capFogR;
	private static volatile float capFogG;
	private static volatile float capFogB;
	private static volatile boolean haveMatrix;
	private static final Vector4f POST_SUN_CLIP = new Vector4f();

	private ExperimentalSectionRender() {
	}

	/** Called from WorldRenderer.render HEAD with MC's exact terrain modelview, projection, camera + fog. */
	public static void captureWorldState(org.joml.Matrix4fc modelView, org.joml.Matrix4fc projection,
			double camX, double camY, double camZ, org.joml.Vector4f fogColor) {
		capturedModelView.set(modelView);
		capturedProjection.set(projection);
		capCamX = camX;
		capCamY = camY;
		capCamZ = camZ;
		capFogR = fogColor.x;
		capFogG = fogColor.y;
		capFogB = fogColor.z;
		haveMatrix = true;
		renderFrameRevision++;
	}

	/** Shared render-distance atmosphere for terrain and vanilla entity shaders. */
	public static float atmosphericFogEdge() { return effectiveRadiusChunks * 16f; }

	/** OpenGL UV, weather/phase strength and moon flag for the currently visible celestial body. */
	public static boolean postSunScreen(float[] out) {
		if (out == null || out.length < 4 || !haveMatrix || SCENE_LIGHTING.activeDirectStrength() <= 0.001f) return false;
		Vector3f dir = SCENE_LIGHTING.activeDirection();
		POST_SUN_CLIP.set(dir.x * 1024f, dir.y * 1024f, dir.z * 1024f, 0f);
		capturedModelView.transform(POST_SUN_CLIP);
		capturedProjection.transform(POST_SUN_CLIP);
		if (POST_SUN_CLIP.w <= 0.001f) return false;
		float invW = 1f / POST_SUN_CLIP.w;
		out[0] = POST_SUN_CLIP.x * invW * 0.5f + 0.5f;
		out[1] = POST_SUN_CLIP.y * invW * 0.5f + 0.5f;
		out[2] = SCENE_LIGHTING.activeDirectStrength();
		out[3] = SCENE_LIGHTING.moonLighting ? 1f : 0f;
		return out[0] > -0.35f && out[0] < 1.35f && out[1] > -0.35f && out[1] < 1.35f;
	}

	public static void renderOpaque() {
		MinecraftClient client = prepare();
		if (client == null) return;
		GPU_PROFILER.beginFrame();

		var workloadConfig = MoneyakShadersConfig.get();
		updateSceneLightingState(client, workloadConfig);
		double measuredGpuMs = GPU_PROFILER.workloadMs("shadow-near") + GPU_PROFILER.workloadMs("shadow-mid") + GPU_PROFILER.workloadMs("shadow-far")
				+ GPU_PROFILER.workloadMs("shadow-point") + GPU_PROFILER.workloadMs("terrain-opaque")
				+ GPU_PROFILER.workloadMs("terrain-translucent");
		int previousWorkloadLevel = gpuWorkloadLevel;
		gpuWorkloadLevel = GPU_WORKLOAD.update(System.nanoTime() / 1_000_000L, workloadConfig.frameBudgetEnabled,
				measuredGpuMs, Math.max(1.0, workloadConfig.frameBudgetTargetMs * 0.8), 2);
		if (previousWorkloadLevel != gpuWorkloadLevel) {
			MoneyakShaders.LOGGER.info("[Plan C/GL] GPU workload level {} -> {} (terrain/shadow GPU={} ms)",
					previousWorkloadLevel, gpuWorkloadLevel, measuredGpuMs);
		}
		if (executor != null) executor.updateWorkerBudget();
		if (MoneyakShadersConfig.get().debugStats) debugRenderFrames++;

		if (MoneyakShadersConfig.get().debugStats && System.currentTimeMillis() >= nextGpuProfileLogMs) {
			nextGpuProfileLogMs = System.currentTimeMillis() + 15_000L;
			int frames = debugRenderFrames, pointPasses = debugPointShadowPasses, pointSlots = debugPointShadowSlots;
			int pointEntitySlots = debugPointEntitySlots, pointRelevant = debugPointRelevant;
			int sunNearPasses = debugSunNearPasses, sunMidPasses = debugSunMidPasses, sunFarPasses = debugSunFarPasses;
			int sunNearDraws = debugSunNearDraws, sunMidDraws = debugSunMidDraws, sunFarDraws = debugSunFarDraws, casterRebuilds = debugCasterRebuilds;
			long casterRebuildTotalNs = debugCasterRebuildTotalNs, casterRebuildMaxNs = debugCasterRebuildMaxNs;

			debugRenderFrames = debugPointShadowPasses = debugPointShadowSlots = debugPointEntitySlots = 0;
			debugSunNearPasses = debugSunMidPasses = debugSunFarPasses = 0;
			debugSunNearDraws = debugSunMidDraws = debugSunFarDraws = 0;
			debugCasterRebuilds = 0;
			debugCasterRebuildTotalNs = debugCasterRebuildMaxNs = 0L;

			MoneyakShaders.LOGGER.info(
					"[Plan C/GL] frame p50/p95/p99={}/{}/{} ms; GPU shadow(avg/p95)={}/{} point={}/{} near={}/{} mid={}/{} far={}/{}; samples frames={} point pass/static/entity/relevant={}/{}/{}/{} sun n/m/f={}/{}/{} draws avg={}/{}/{} receivers/casters/visible={}/{}/{} caster rebuilds/avg/max={}/{}/{} us; opaque(p95)={} translucent(p95)={}; CPU snapshot/upload/shadow p95={}/{}/{} ms, worker mesh p50/p95/p99={}/{}/{} ms; queues snap={} mesh={} upload={} light={}; quality={}/{}",
					String.format(java.util.Locale.ROOT, "%.2f", com.moneyakshaders.client.FrameProfiler.p50Ms()),
					String.format(java.util.Locale.ROOT, "%.2f", com.moneyakshaders.client.FrameProfiler.p95Ms()),
					String.format(java.util.Locale.ROOT, "%.2f", com.moneyakshaders.client.FrameProfiler.p99Ms()),
					String.format(java.util.Locale.ROOT, "%.2f", GPU_PROFILER.averageMs("shadow-point") + GPU_PROFILER.averageMs("shadow-near") + GPU_PROFILER.averageMs("shadow-mid") + GPU_PROFILER.averageMs("shadow-far")),
					String.format(java.util.Locale.ROOT, "%.2f", GPU_PROFILER.p95Ms("shadow-point") + GPU_PROFILER.p95Ms("shadow-near") + GPU_PROFILER.p95Ms("shadow-mid") + GPU_PROFILER.p95Ms("shadow-far")),
					String.format(java.util.Locale.ROOT, "%.2f", GPU_PROFILER.averageMs("shadow-point")),
					String.format(java.util.Locale.ROOT, "%.2f", GPU_PROFILER.p95Ms("shadow-point")),
					String.format(java.util.Locale.ROOT, "%.2f", GPU_PROFILER.averageMs("shadow-near")),
					String.format(java.util.Locale.ROOT, "%.2f", GPU_PROFILER.p95Ms("shadow-near")),
					String.format(java.util.Locale.ROOT, "%.2f", GPU_PROFILER.averageMs("shadow-mid")),
					String.format(java.util.Locale.ROOT, "%.2f", GPU_PROFILER.p95Ms("shadow-mid")),
					String.format(java.util.Locale.ROOT, "%.2f", GPU_PROFILER.averageMs("shadow-far")),
					String.format(java.util.Locale.ROOT, "%.2f", GPU_PROFILER.p95Ms("shadow-far")),
					frames, pointPasses, pointSlots, pointEntitySlots, pointRelevant, sunNearPasses, sunMidPasses, sunFarPasses,
					sunNearDraws / Math.max(1, sunNearPasses), sunMidDraws / Math.max(1, sunMidPasses), sunFarDraws / Math.max(1, sunFarPasses),
					debugShadowReceivers, SHADOW_CASTERS.size(), VISIBLE_SECTIONS.size(), casterRebuilds,
					casterRebuildTotalNs / Math.max(1, casterRebuilds) / 1_000L, casterRebuildMaxNs / 1_000L,
					String.format(java.util.Locale.ROOT, "%.2f", GPU_PROFILER.p95Ms("terrain-opaque")),
					String.format(java.util.Locale.ROOT, "%.2f", GPU_PROFILER.p95Ms("terrain-translucent")),
					String.format(java.util.Locale.ROOT, "%.2f", com.moneyakshaders.client.FrameWorkBudget.p95Ms(com.moneyakshaders.client.FrameWorkBudget.BUCKET_SNAPSHOT)),
					String.format(java.util.Locale.ROOT, "%.2f", com.moneyakshaders.client.FrameWorkBudget.p95Ms(com.moneyakshaders.client.FrameWorkBudget.BUCKET_MESH_UPLOAD)),
					String.format(java.util.Locale.ROOT, "%.2f", com.moneyakshaders.client.FrameWorkBudget.p95Ms(com.moneyakshaders.client.FrameWorkBudget.BUCKET_SHADOW)),
					String.format(java.util.Locale.ROOT, "%.2f", executor == null ? 0.0 : executor.meshP50Ms()),
					String.format(java.util.Locale.ROOT, "%.2f", executor == null ? 0.0 : executor.meshP95Ms()),
					String.format(java.util.Locale.ROOT, "%.2f", executor == null ? 0.0 : executor.meshP99Ms()),
					pendingSnapshotCount(), meshInFlightCount(), uploadBacklogCount(),
					com.moneyakshaders.client.ClientLightDispatcher.pendingRenderCount(),
					adaptiveShadowLevel, adaptivePointShadowLevel);
			logSnapshotQueueState();
		}

		resolveFluidSprites(client);
		RETIRED_SCENES.drain(256, 500_000L, System::nanoTime, ExperimentalSectionRender::freeSection);
		trimOrphanCache();
		updateSections(client);
		if (SECTIONS.isEmpty()) return;

		int camBlockX = (int)Math.floor(capCamX);
		int camBlockY = (int)Math.floor(capCamY);
		int camBlockZ = (int)Math.floor(capCamZ);
		BlockPos visibilityPos = new BlockPos(camBlockX, camBlockY, camBlockZ);
		long cameraSecKey = ChunkSectionPos.asLong(camBlockX >> 4, camBlockY >> 4, camBlockZ >> 4);

		visibilityFrame++;
		int settings = radiusChunks * 2 + (MoneyakShadersConfig.get().occlusionCulling ? 1 : 0);
		boolean cameraSectionChanged = cameraSecKey != lastVisSectionKey || settings != visibilitySettings;
		boolean dirtyIntervalElapsed = visibilityDirty
				&& visibilityFrame - lastVisibilityRebuildFrame >= VISIBILITY_DIRTY_REBUILD_INTERVAL_FRAMES;

		if (cameraSectionChanged || !VISIBILITY_GRAPH.running() && dirtyIntervalElapsed) {
			computeVisibility(visibilityPos);
			lastVisSectionKey = cameraSecKey;
			visibilitySettings = settings;
			lastVisibilityRebuildFrame = visibilityFrame;
			visibilityDirty = false;
		}

		if (VISIBILITY_GRAPH.step(2048, 600_000L, System::nanoTime,
				ExperimentalSectionRender::sectionVisibility, ExperimentalSectionRender::discoverVisibleSection)) {
			if (!visibilityDirty || VISIBILITY_GRAPH.overflow()) {
				VISIBLE_SECTIONS = VISIBILITY_GRAPH.publish(VISIBLE_SECTIONS);
				castersDirty = true;
				SCENE_RESIDENCY.beginSweep();
			}
		}

		MoneyakShadersConfig cfg = MoneyakShadersConfig.get();
		updateAdaptiveShadowQuality(cfg);
		if (cfg.sunShadows) renderShadowPass(client, cfg);
		updateAdaptivePointShadowQuality(cfg);
		renderPointShadowPass(client, cfg);
		drawPass(client, false);

		if (cfg.entityShadows && (cfg.sunShadows || cfg.pointLightShadows)) {
			EntityShadowCapture.begin(capCamX, capCamY, capCamZ);
			if (client.options.getPerspective().isFirstPerson())
				com.moneyakshaders.client.CaptureRenderQueue.captureSelf(client, capCamX, capCamY, capCamZ);
		}
	}
	/**
	 * Minecraft-style occlusion BFS from the player's section. Travels section-to-section only through
	 * face pairs the per-section connectivity matrix ({@link Sec#visibility}) says are mutually visible,
	 * and never reverses a direction already taken — so sections fully walled off from the camera's
	 * sight line are dropped from {@link #VISIBLE_SECTIONS}. Sections that aren't meshed yet are treated
	 * as fully transparent (all-connected) so the graph never hides something it can't vouch for. When
	 * {@code occlusionCulling} is off, falls back to the old radius flood (no per-face check).
	 */
	private static void computeVisibility(BlockPos ignored) {
		int x = ((int)Math.floor(capCamX)) >> 4;
		int y = ((int)Math.floor(capCamY)) >> 4;
		int z = ((int)Math.floor(capCamZ)) >> 4;
		visibilityWasUnknown = VISIBLE_SECTIONS.isEmpty();
		VISIBILITY_GRAPH.begin(x, y, z, radiusChunks, Math.max(Math.min(y - VERTICAL_SECTIONS, 2), y - 40),
				y + VERTICAL_SECTIONS, MoneyakShadersConfig.get().occlusionCulling);
	}

	private static void discoverVisibleSection(long key) {
		// Empty is the existing conservative "unknown" sentinel. Never replace it with a partial set.
		if (!visibilityWasUnknown) VISIBLE_SECTIONS.add(key);
		SCENE_RESIDENCY.prioritize(key);
		CACHE_RESIDENCY.prioritize(key);
	}
	/** Connectivity matrix of a section; all-connected ({@code -1}) if it isn't meshed yet. */
	private static long sectionVisibility(int cx, int sy, int cz) {
		Sec s = SECTIONS.get(ChunkSectionPos.asLong(cx, sy, cz));
		return s == null ? -1L : s.visibility;
	}

	/** Whether a section survived this frame's occlusion BFS. True (don't cull) until the graph exists. */
	public static boolean isSectionVisible(int cx, int sy, int cz) {
		return isVisibleSectionKey(ChunkSectionPos.asLong(cx, sy, cz));
	}

	/**
	 * Entity shadow capture is optional extra geometry, never a reason to keep work alive for a
	 * sleeping underground section. Unknown sections remain conservative/eligible while streaming.
	 */
	public static boolean isSectionActiveForEntity(int cx, int sy, int cz) {
		Sec section = SECTIONS.get(ChunkSectionPos.asLong(cx, sy, cz));
		if (section != null) return section.residency == Sec.Residency.ACTIVE;
		// A deliberately deferred underground section may have no mesh/tombstone yet. Treating every
		// unknown key as active kept mobs, display entities and dynamic lights alive underneath a fully
		// closed surface. Reuse the same conservative scheduler test; cave/spectator/near-player cases
		// all bypass it, so entering the underground scene restores rendering immediately.
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.world == null || client.player == null) return true;
		int pcx = client.player.getBlockX() >> 4;
		int psy = client.player.getBlockY() >> 4;
		int pcz = client.player.getBlockZ() >> 4;
		return sectionRelevantToViewerDomain(client.world, cx, sy, cz, pcx, psy, pcz,
				undergroundView, section);
	}

	/**
	 * An empty graph is deliberately the conservative no-occlusion state. It occurs before the first
	 * successful BFS and also when the bounded BFS overflows in a very open scene. Never interpret it
	 * as "everything is hidden" in a draw hot path, or streaming terrain disappears until the next BFS.
	 */
	private static boolean isVisibleSectionKey(long key) {
		if (VISIBLE_SECTIONS.isEmpty() || VISIBLE_SECTIONS.contains(key)) return true;
		// The connectivity BFS stays bounded to the normal eye band. A retained deep sea-floor/ravine
		// section outside that band is instead guarded by the real camera frustum plus OCEAN_FLOOR band;
		// interpreting its deliberate absence from BFS as occlusion recreated the white holes at height.
		Sec section = SECTIONS.get(key);
		MinecraftClient client = MinecraftClient.getInstance();
		if (section == null || section.residency != Sec.Residency.ACTIVE
				|| client == null || client.world == null || client.player == null) return false;
		int playerSectionY = client.player.getBlockY() >> 4;
		return !inVerticalRange(section.sy, playerSectionY)
				&& inActiveRange(client.world, section.cx, section.sy, section.cz, playerSectionY);
	}

	/**
	 * TRANSLUCENT renderSection hook. It closes entity capture here, but defers the custom water/glass
	 * draw until the end of {@code WorldRenderer.render}: 1.21.11 schedules particles in a later frame-
	 * graph pass, so drawing here made every particle appear on top of stained glass and water.
	 */
	public static void renderWater() {
		checkGl("renderWater ENTER");

		if (EntityShadowCapture.active) EntityShadowCapture.end();

		if (!initialised || program == 0 || !haveMatrix) {
			lateTranslucentPending = false;
			checkGl("renderWater LEAVE");
			return;
		}

		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null) {
			lateTranslucentPending = false;
			checkGl("renderWater LEAVE");
			return;
		}

		Framebuffer fb = client.getFramebuffer();
		if (fb == null || fb.textureWidth <= 0 || fb.textureHeight <= 0) {
			lateTranslucentPending = false;
			checkGl("renderWater LEAVE");
			return;
		}

		lateTranslucentPending = WaterSurfaceResources.capture(fb);
		checkGl("renderWater LEAVE");
	}

	/**
	 * Composite water and stained glass after vanilla particles/weather. Fragments behind a translucent
	 * surface are now naturally filtered by that surface, while particle depth keeps foreground effects
	 * in front. This also gives particles the existing water/glass colour and distance treatment without
	 * a render-thread block raycast per particle.
	 */
	public static void renderLateTranslucent() {
		checkGl("lateTranslucent ENTER");

		if (!lateTranslucentPending) {
			return;
		}

		lateTranslucentPending = false;

		MinecraftClient client = prepare();

		checkGl("lateTranslucent after prepare");

		if (client == null || SECTIONS.isEmpty()) {
			return;
		}

		drawPass(client, true);

		checkGl("lateTranslucent AFTER drawPass");
	}

	private static MinecraftClient prepare() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.world == null || client.player == null || client.gameRenderer == null) {
			return null;
		}
		if (!initialised) {
			initialised = true;
			init();
		}
		if (program == 0 || !haveMatrix) {
			return null;
		}
		long now = System.currentTimeMillis();
		if (firstWorldMs == 0L) {
			firstWorldMs = now;
		}
		if (now - firstWorldMs < WORLD_STREAM_WARMUP_MS) {
			return null;
		}
		return client;
	}

	private static boolean hasActiveTranslucentSections() {
		long revision = translucentOrderRevision;

		if (activeTranslucentCachedRevision == revision) {
			return activeTranslucentCached;
		}

		boolean found = false;

		for (Sec s : SECTIONS.values()) {
			if (s.residency == Sec.Residency.ACTIVE
					&& s.wcount > 0) {

				found = true;
				break;
			}
		}

		activeTranslucentCached = found;
		activeTranslucentCachedRevision = revision;

		return found;
	}

	/**
	 * Draw one layer (opaque terrain, or translucent water) into MC's real world
	 * framebuffer. We wrap MC's actual colour+depth attachments in our own GL FBO so the
	 * draw lands in the presented frame, use the exact captured camera matrices (no depth
	 * clear -> composites with sky/entities via shared depth), and save/restore all
	 * touched GL state through GlStateManager.
	 */
private static void drawPass(MinecraftClient client, boolean translucent) {
	String gpuPass =
			translucent
					? "terrain-translucent"
					: "terrain-opaque";

	String debugPass =
			translucent
					? "drawPass translucent"
					: "drawPass opaque";

	checkGl(debugPass + " ENTER");

	GPU_PROFILER.begin(gpuPass);

	checkGl(debugPass + " after profiler begin");

	/*
	 * Avoid entering the complete late translucent GL pipeline when the scene
	 * contains no active water/glass/translucent terrain at all.
	 *
	 * translucentOrderRevision is already invalidated when translucent meshes
	 * arrive, leave ACTIVE residency or wake again.
	 */
	if (translucent && !hasActiveTranslucentSections()) {
		checkGl(debugPass + " before empty profiler end");

		GPU_PROFILER.end(gpuPass);

		checkGl(debugPass + " after empty profiler end");
		return;
	}

	Framebuffer fb = client.getFramebuffer();
	GpuTexture colorTex = fb.getColorAttachment();
	GpuTexture depthTex = fb.getDepthAttachment();

	if (!(colorTex instanceof GlTexture) || !(depthTex instanceof GlTexture)) {
		checkGl(debugPass + " before invalid framebuffer profiler end");

		GPU_PROFILER.end(gpuPass);

		checkGl(debugPass + " after invalid framebuffer profiler end");
		return;
	}

	int colorId = ((GlTexture) colorTex).getGlId();
	int depthId = ((GlTexture) depthTex).getGlId();

	checkGl(debugPass + " after framebuffer attachments");

	/*
	 * Only query states which Minecraft does NOT expose/cache reliably.
	 *
	 * Everything maintained by GlStateManager is mirrored in RenderGlState and
	 * therefore costs only ordinary Java field reads here.
	 */
	int prevFbo =
			GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);

	int prevProgram =
			GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);

	int prevVao =
			GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);

	int prevActive =
			RenderGlState.activeTexture();

	int[] prevViewport = DRAW_VIEWPORT_SCRATCH;
	GL11.glGetIntegerv(GL11.GL_VIEWPORT, prevViewport);

	boolean prevDepth =
			RenderGlState.depthTest();

	boolean prevCull =
			RenderGlState.cullEnabled();

	int prevDepthFunc =
			RenderGlState.depthFunc();

	boolean prevDepthMask =
			RenderGlState.depthMask();

	/*
	 * GlStateManager does not cache these.
	 */
	int prevCullMode =
			GL11.glGetInteger(GL11.GL_CULL_FACE_MODE);

	int prevFrontFace =
			GL11.glGetInteger(GL11.GL_FRONT_FACE);

	boolean prevBlend =
			RenderGlState.blendEnabled();

	boolean prevPolygonOffset =
			RenderGlState.polygonOffsetEnabled();

	float prevPolygonFactor =
			RenderGlState.polygonOffsetFactor();

	float prevPolygonUnits =
			RenderGlState.polygonOffsetUnits();

	int prevBlendSrcRgb =
			RenderGlState.blendSrcRgb();

	int prevBlendDstRgb =
			RenderGlState.blendDstRgb();

	int prevBlendSrcAlpha =
			RenderGlState.blendSrcAlpha();

	int prevBlendDstAlpha =
			RenderGlState.blendDstAlpha();

	int prevColorMask =
			colorMaskBits();

	checkGl(debugPass + " after state capture");

	if (mcFbo == 0) {
		mcFbo = GL30.glGenFramebuffers();
	}

	GlStateManager._glBindFramebuffer(
			GL30.GL_FRAMEBUFFER,
			mcFbo);

	GlStateManager._glFramebufferTexture2D(
			GL30.GL_FRAMEBUFFER,
			GL30.GL_COLOR_ATTACHMENT0,
			GL11.GL_TEXTURE_2D,
			colorId,
			0);

	GlStateManager._glFramebufferTexture2D(
			GL30.GL_FRAMEBUFFER,
			GL30.GL_DEPTH_ATTACHMENT,
			GL11.GL_TEXTURE_2D,
			depthId,
			0);

	GlStateManager._viewport(
			0,
			0,
			fb.textureWidth,
			fb.textureHeight);

	checkGl(debugPass + " after framebuffer bind/attach");

	GlStateManager._enableDepthTest();
	GlStateManager._depthFunc(GL11.GL_LEQUAL);

	GlStateManager._disablePolygonOffset();

	int atlas = atlasGlId(client);

	GlStateManager._glUseProgram(program);

	try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
		GL20.glUniformMatrix4fv(
				uProj,
				false,
				capturedProjection.get(stack.mallocFloat(16)));

		GL20.glUniformMatrix4fv(
				uView,
				false,
				capturedModelView.get(stack.mallocFloat(16)));
	}

	GL20.glUniform3f(
			uCamPos,
			(float) capCamX,
			(float) capCamY,
			(float) capCamZ);

	// Distance past which leaves render fully opaque (0 = always fancy). Shader-side.
	MoneyakShadersConfig cfg = MoneyakShadersConfig.get();

	GL20.glUniform1f(
			uLeavesFarDist,
			cfg.leavesOpaqueDistance);

	GL20.glUniform1f(
			uPackLodNear,
			Math.max(0f, cfg.terrainTextureLodNear));

	GL20.glUniform1f(
			uPackLodFar,
			Math.max(
					cfg.terrainTextureLodNear + 1f,
					cfg.terrainTextureLodFar));

	GL20.glUniform1f(
			uPackLodBias,
			cfg.terrainMipmaps
					? Math.max(
							0f,
							Math.min(
									4f,
									cfg.terrainTextureLodBias))
					: 0f);

	// Program uniform storage survives the opaque→translucent hand-off. Dynamic data is camera and
	// frame dependent, but not pass dependent, so avoid duplicating the same upload each frame.
	// Vanilla may legitimately bind other programs and textures between opaque and translucent
	// terrain passes. Re-publish every dynamic uniform for the program we bind here; caching this
	// across passes caused near dynamic lamps to use stale light/shadow state.
	setEffectUniforms(client, cfg);
	setTerrainLights(client, cfg);

	boolean underwater = isCameraInWaterVolume(client);

	GL20.glUniform1i(
			uCameraUnderwater,
			underwater ? 1 : 0);

	GL20.glUniform3f(
			uFogColor,
			capFogR,
			capFogG,
			capFogB);

	if (underwater) {
		// Stable radial volume around the actual camera. A changing eye-adaptation distance made
		// the fog boundary appear to follow the player's height and produced different results
		// depending on how long the camera had been submerged.
		float fogEnd = 96f;

		float ufr =
				capFogR * 0.55f + 0.06f;

		float ufg =
				capFogG * 0.70f + 0.20f;

		float ufb =
				capFogB * 0.80f + 0.30f;

		GL20.glUniform3f(
				uFogColor,
				ufr,
				ufg,
				ufb);

		// The final depth-aware fullscreen fog pass handles the complete world, including shaders
		// used by chests/custom block entities that do not consume vanilla FogData.
		GL20.glUniform1f(
				uFogStart,
				100000f);

		GL20.glUniform1f(
				uFogEnd,
				100001f);

		// Underwater fog is a camera-centred volume: above, below and beside the player
		// use the same attenuation. Do not split the world at the player's eye height.
		GL20.glUniform1f(
				uWaterY,
				-10000f);

		// Publish for vanilla's WaterFogModifier so entities/BEs get the SAME fog as the terrain
		// (vanilla's dense dark fog made nearby mobs/chests look like blue silhouettes).
		com.moneyakshaders.client.UnderwaterFogSync.set(
				0f,
				fogEnd,
				ufr,
				ufg,
				ufb);

	} else {
		com.moneyakshaders.client.UnderwaterFogSync.clear();

		GL20.glUniform1f(
				uWaterY,
				-10000f);

		// Tasteful distance fog: thin band at the view-distance edge to hide chunk pop-in.
		// Starts one chunk before the edge and finishes a couple blocks past it.
		// Follow the PROGRESSIVE radius so the thin fog band sits at the currently-loaded edge and
		// hides the un-meshed ring beyond it while the render distance ramps up after join/teleport.
		float fogEdge =
				effectiveRadiusChunks * 16.0f;

		// A twenty-block linear fade intersected a distant lake as a clearly visible camera-centred
		// colour ring. Spread the transition over three chunks and ease both ends; it still reaches
		// full fog at the streaming edge, but has no single high-contrast contour.
		GL20.glUniform1f(
				uFogStart,
				fogEdge - 48.0f);

		GL20.glUniform1f(
				uFogEnd,
				fogEdge + 4.0f);
	}

	checkGl(debugPass + " after terrain uniforms");

	int lightmap =
			lightmapGlId(client);

	GlStateManager._activeTexture(
			GL13.GL_TEXTURE1);

	int prevTex1 =
			RenderGlState.texture2D(1);

	int prevSampler1 =
			GL11.glGetInteger(
					GL33.GL_SAMPLER_BINDING);

	GL33.glBindSampler(
			1,
			0);

	if (lightmap > 0) {
		GlStateManager._bindTexture(
				lightmap);

		GL20.glUniform1i(
				uLightmap,
				1);
	}

	GlStateManager._activeTexture(
			GL13.GL_TEXTURE0);

	int prevTex0 =
			RenderGlState.texture2D(0);

	int prevSampler0 =
			GL11.glGetInteger(
					GL33.GL_SAMPLER_BINDING);

	if (atlas > 0) {
		GlStateManager._bindTexture(
				atlas);

		GL20.glUniform1i(
				uAtlas,
				0);
	}

	configureAtlasSampler(client);

	GL33.glBindSampler(
			0,
			atlasSampler);

	int prevTex2 =
			RenderGlState.texture2D(2);

	int prevTex3 =
			RenderGlState.texture2D(3);

	int prevTex4 =
			RenderGlState.texture2D(4);

	int prevTex5 =
			RenderGlState.texture2D(5);

	int prevTex6 =
			RenderGlState.texture2D(6);

	int prevTex7 =
			RenderGlState.texture2D(7);
	int prevTex8 = RenderGlState.texture2D(8);
	int prevTex9 = RenderGlState.texture2D(9);
	int prevTex10 = RenderGlState.texture2D(10);
	int prevTex11 = RenderGlState.texture2D(11);
	int prevTex12 = rawTexture2D(12);
	int prevTex13 = rawTexture2D(13);

	GlStateManager._activeTexture(
			GL13.GL_TEXTURE0 + 2);

	int prevSampler2 =
			GL11.glGetInteger(
					GL33.GL_SAMPLER_BINDING);

	GL33.glBindSampler(
			2,
			0);

	GlStateManager._activeTexture(
			GL13.GL_TEXTURE0 + 3);

	int prevSampler3 =
			GL11.glGetInteger(
					GL33.GL_SAMPLER_BINDING);

	GL33.glBindSampler(
			3,
			0);

	GlStateManager._activeTexture(
			GL13.GL_TEXTURE0 + 5);

	int prevSampler5 =
			GL11.glGetInteger(
					GL33.GL_SAMPLER_BINDING);

	GL33.glBindSampler(
			5,
			0);

	GlStateManager._activeTexture(
			GL13.GL_TEXTURE0 + 6);

	int prevSampler6 =
			GL11.glGetInteger(
					GL33.GL_SAMPLER_BINDING);

	GL33.glBindSampler(
			6,
			0);

	GlStateManager._activeTexture(
			GL13.GL_TEXTURE0 + 7);

	int prevSampler7 =
			GL11.glGetInteger(
					GL33.GL_SAMPLER_BINDING);

	GL33.glBindSampler(
			7,
			0);

	GlStateManager._activeTexture(GL13.GL_TEXTURE0 + 8);
	int prevSampler8 = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING); GL33.glBindSampler(8, 0);
	GlStateManager._activeTexture(GL13.GL_TEXTURE0 + 9);
	int prevSampler9 = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING); GL33.glBindSampler(9, 0);
	GlStateManager._activeTexture(GL13.GL_TEXTURE0 + 10);
	int prevSampler10 = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING); GL33.glBindSampler(10, 0);
	GlStateManager._activeTexture(GL13.GL_TEXTURE0 + 11);
	int prevSampler11 = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING); GL33.glBindSampler(11, 0);
	int prevSampler12 = rawSampler(12);
	int prevSampler13 = rawSampler(13);
	GL33.glBindSampler(12, 0);
	GL33.glBindSampler(13, 0);

	checkGl(debugPass + " after sampler setup 0..13");

	bindSceneLightingUniforms();
	bindDirectionalShadowUniforms(cfg);
	GlStateManager._activeTexture(GL13.GL_TEXTURE0);

	checkGl(debugPass + " after directional shadow uniforms");

	// Multi-light point shadow atlas on unit 4 — set independently of the sun (torches shadow at night).
	GlStateManager._activeTexture(
			GL13.GL_TEXTURE0 + 4);

	int prevSampler4 =
			GL11.glGetInteger(
					GL33.GL_SAMPLER_BINDING);

	GL33.glBindSampler(
			4,
			0);

	boolean anyPoint =
			false;

	for (int i = 0; i < POINT_MAX; i++) {
		anyPoint |= pLightValid[i];
	}

	if (cfg.pointLightShadows
			&& anyPoint
			&& pointShadowTex != 0
			&& pointEntityShadowTex != 0) {

		GlStateManager._bindTexture(
				pointShadowTex);

		GL20.glUniform1i(
				uPointShadowMap,
				4);

		GlStateManager._activeTexture(
				GL13.GL_TEXTURE0 + 6);

		GlStateManager._bindTexture(
				pointEntityShadowTex);

		GL20.glUniform1i(
				uPointEntityShadowMap,
				6);

		GlStateManager._activeTexture(
				GL13.GL_TEXTURE0 + 7);

		GlStateManager._bindTexture(
				pointVoxelTex);

		GL20.glUniform1i(
				uPointVoxelMap,
				7);

		try (var stack =
				org.lwjgl.system.MemoryStack.stackPush()) {

			java.nio.FloatBuffer all =
					stack.mallocFloat(
							POINT_MAX * 6 * 16);

			for (int m = 0;
					m < POINT_MAX * 6;
					m++) {

				pointFaceMx[m].get(
						m * 16,
						all);
			}

			GL20.glUniformMatrix4fv(
					uPointLightMVP,
					false,
					all);

			java.nio.FloatBuffer data =
					stack.mallocFloat(
							POINT_MAX * 4);

			java.nio.FloatBuffer colors =
					stack.mallocFloat(
							POINT_MAX * 3);

			java.nio.FloatBuffer dyn =
					stack.mallocFloat(
							POINT_MAX);

			java.nio.FloatBuffer solid =
					stack.mallocFloat(
							POINT_MAX);

			java.nio.FloatBuffer faceMask =
					stack.mallocFloat(
							POINT_MAX);

			java.nio.FloatBuffer voxelOrigin =
					stack.mallocFloat(
							POINT_MAX * 3);

			java.nio.FloatBuffer voxelValid =
					stack.mallocFloat(
							POINT_MAX);

			java.nio.FloatBuffer fade =
					stack.mallocFloat(
							POINT_MAX);

			for (int i = 0;
					i < POINT_MAX;
					i++) {

				boolean live =
						pLightValid[i]
						|| pLightFade[i] > 0.001f;

				data.put(
						(float) (pLightX[i] - capCamX))
						.put(
								(float) (pLightY[i] - capCamY))
						.put(
								(float) (pLightZ[i] - capCamZ))
						.put(
								live
										? pLightRange[i]
										: 0f);

				colors.put(
						pLightR[i])
						.put(
								pLightG[i])
						.put(
								pLightB[i]);

				dyn.put(
						pLightDyn[i]
								? 1f
								: 0f);

				solid.put(
						pLightSolid[i]
								? 1f
								: 0f);

				faceMask.put(
						pLightFaceMask[i]);

				voxelOrigin.put(
						(float) (pVoxelOriginX[i] - capCamX))
						.put(
								(float) (pVoxelOriginY[i] - capCamY))
						.put(
								(float) (pVoxelOriginZ[i] - capCamZ));

				voxelValid.put(
						pVoxelValid[i]
								? 1f
								: 0f);

				fade.put(
						pLightFade[i]);
			}

			data.flip();
			colors.flip();
			dyn.flip();
			solid.flip();
			faceMask.flip();
			voxelOrigin.flip();
			voxelValid.flip();
			fade.flip();

			GL20.glUniform4fv(
					uPointData,
					data);

			GL20.glUniform3fv(
					uPointCols,
					colors);

			GL20.glUniform1fv(
					uPointDynA,
					dyn);

			GL20.glUniform1fv(
					uPointSolidA,
					solid);

			GL20.glUniform1fv(
					uPointFaceMask,
					faceMask);

			GL20.glUniform3fv(
					uPointVoxelOrigin,
					voxelOrigin);

			GL20.glUniform1fv(
					uPointVoxelValid,
					voxelValid);

			GL20.glUniform1fv(
					uPointFade_,
					fade);
		}

		GL20.glUniform1f(
				uPointShadowStr,
				cfg.pointShadowStrength
						/ 100.0f
						* pointSatFactor);
	} else {
		GL20.glUniform1f(
				uPointShadowStr,
				0.0f);
	}

	GL20.glUniform1f(
			uSunUp,
			sunUpFactor);

	GlStateManager._activeTexture(
			GL13.GL_TEXTURE0);

	checkGl(debugPass + " after point shadow setup");

	if (!translucent) {
		// opaque terrain. Force blend OFF (MC may leave an additive/odd blend func from the
		// sky pass; else our solid terrain blends with the framebuffer -> washed out).
		GlStateManager._enableDepthTest();
		GlStateManager._depthFunc(GL11.GL_LEQUAL);
		GlStateManager._depthMask(true);
		GlStateManager._enableCull();

		GL11.glCullFace(
				GL11.GL_BACK);

		GL11.glFrontFace(
				GL11.GL_CCW);

		GlStateManager._disableBlend();

		GL20.glUniform1i(
				uTranslucent,
				0);

		GL20.glUniform1i(
				uOit,
				0);

		GL20.glUniform1f(
				uDepthOnly,
				0f);

		int drawn =
				multidrawLayer(false);

		if (drawn > 0) {
			if (shouldUseDepthPrePass(cfg)) {
				GL20.glUniform1f(
						uDepthOnly,
						1f);

				RenderGlState.colorMask(
						false,
						false,
						false,
						false);

				issueMultidraw(drawn);

				GL20.glUniform1f(
						uDepthOnly,
						0f);

				RenderGlState.colorMask(
						true,
						true,
						true,
						true);

				GlStateManager._depthFunc(
						GL11.GL_LEQUAL);

				issueMultidraw(drawn);

				GlStateManager._depthFunc(
						GL11.GL_LEQUAL);
			} else {
				issueMultidraw(drawn);
			}
		}

		GL15.glBindBuffer(
				GL40.GL_DRAW_INDIRECT_BUFFER,
				0);

		checkGl("drawPass opaque after terrain draw");

		// Procedural fantasy clouds (vanilla clouds cancelled by CloudRendererCancelMixin): drawn
		// after opaque terrain with depth TEST on so mountains occlude them; depth write off.
		if (cfg.fantasyClouds
				&& FantasyClouds.isSupported()
				&& client.world != null) {

			checkGl("BEFORE FantasyClouds");

			float cloudTime =
					(System.currentTimeMillis() - shaderStartMs)
					/ 1000.0f;

			FantasyClouds.render(capturedProjection, capturedModelView, capCamX, capCamY, capCamZ, cloudTime,
					SCENE_LIGHTING.snapshot());

			checkGl("AFTER FantasyClouds");

			GlStateManager._glUseProgram(
					program);

			checkGl("AFTER FantasyClouds program restore");
		}

		if (!captured
				&& System.currentTimeMillis() - firstWorldMs > 7000L) {

			captured = true;

			MoneyakShaders.LOGGER.info(
					"[Plan C/GL] world-pass render: {} sections in map, {} sub-draws in 1 multidraw (r={}), lightmap={}, glError=0x{}",
					SECTIONS.size(),
					drawn,
					radiusChunks,
					lightmap,
					Integer.toHexString(
							GL11.glGetError()));
		}

		checkGl("drawPass opaque after clouds");
	} else {
		checkGl("drawPass translucent before render");

		/* Scene/depth were captured at the vanilla translucent hook, before particles/weather. This keeps
		 * particle colour out of refraction/SSR while the actual water composite remains late. */
		{
			GlStateManager._activeTexture(GL13.GL_TEXTURE0 + 5);
			GlStateManager._bindTexture(WaterSurfaceResources.waveTexture());
			GL33.glBindSampler(5, 0);
			GL20.glUniform1i(uWaterWaveTex, 5);

			GlStateManager._activeTexture(GL13.GL_TEXTURE0 + 10);
			GlStateManager._bindTexture(WaterSurfaceResources.sceneColorTexture());
			GL33.glBindSampler(10, 0);
			GL20.glUniform1i(uWaterSceneColor, 10);

			GlStateManager._activeTexture(GL13.GL_TEXTURE0 + 11);
			GlStateManager._bindTexture(WaterSurfaceResources.sceneDepthTexture());
			GL33.glBindSampler(11, 0);
			GL20.glUniform1i(uWaterSceneDepth, 11);

			waterInvProjectionScratch.set(capturedProjection).invert();
			try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
				GL20.glUniformMatrix4fv(uInvProj, false, waterInvProjectionScratch.get(stack.mallocFloat(16)));
			}
			GL20.glUniform2f(uScreenSize, fb.textureWidth, fb.textureHeight);
			GL20.glUniform1f(uWaterBumpiness, Math.max(0f, Math.min(2.5f, cfg.waterBumpiness / 100.0f)));
			GL20.glUniform1f(uWaterReflection, Math.max(0f, Math.min(1.5f, cfg.waterReflection / 100.0f)));
			GL20.glUniform1f(uWaterRefraction, Math.max(0f, Math.min(2.0f, cfg.waterRefraction / 100.0f)));
			GL20.glUniform1f(uWaterFoam, Math.max(0f, Math.min(1.5f, cfg.waterFoam / 100.0f)));
			GL20.glUniform1f(uWaterTransparency, Math.max(0f, Math.min(1f, cfg.waterTransparency / 100.0f)));
			GL20.glUniform1f(uWaterAbsorption, Math.max(0f, Math.min(1f, cfg.waterAbsorption / 100.0f)));
			GL20.glUniform1f(uWaterSpecular, Math.max(0f, Math.min(1f, cfg.waterSpecular / 100.0f)));
			GL20.glUniform1i(uWaterSsrSteps, Math.max(0, Math.min(28, cfg.waterSsrSteps)));
		}
		GlStateManager._activeTexture(GL13.GL_TEXTURE0);

		// water: blend over the already-drawn entities, keep depth test but DON'T write
		// depth, draw both sides (so the surface is visible from under the water too).
		RenderGlState.colorMask(
				true,
				true,
				true,
				true);

		GlStateManager._enableDepthTest();

		GlStateManager._depthFunc(
				GL11.GL_LEQUAL);

		GL20.glUniform1i(
				uTranslucent,
				1);

		GlStateManager._enableBlend();

		if (cfg.weightedTransparency
				&& !oitDisabledWarningLogged) {

			oitDisabledWarningLogged = true;

			MoneyakShaders.LOGGER.warn(
					"[Plan C/GL] weighted OIT is temporarily disabled for renderer stability; using sorted transparency");
		}

		boolean oit =
				false;

		weightedOitActive =
				oit;

		GL20.glUseProgram(
				program);

		GL20.glUniform1i(
				uOit,
				oit ? 1 : 0);

		if (!oit) {
			RenderGlState.blend(
					GL11.GL_SRC_ALPHA,
					GL11.GL_ONE_MINUS_SRC_ALPHA,
					GL11.GL_ONE,
					GL11.GL_ONE_MINUS_SRC_ALPHA);
		}

		GlStateManager._depthMask(
				false);

		GlStateManager._disableCull();

		checkGl("drawPass translucent before multidraw");

		int nt =
				multidrawLayer(true);

		checkGl("drawPass translucent after multidraw build");

		if (nt > 0) {
			issueMultidraw(nt);
		}

		checkGl("drawPass translucent after multidraw issue");

		GL15.glBindBuffer(
				GL40.GL_DRAW_INDIRECT_BUFFER,
				0);

		if (oit) {
			compositeOit(
					fb.textureWidth,
					fb.textureHeight);

			checkGl("drawPass translucent after OIT composite");

			GlStateManager._glUseProgram(
					program);
		}

		GL20.glUniform1i(
				uTranslucent,
				0);

		GL20.glUniform1i(
				uOit,
				0);

		weightedOitActive =
				false;

		checkGl("drawPass translucent after render");
	}

	// --- restore GL state ---

	GL33.glBindSampler(
			0,
			prevSampler0);

	GlStateManager._glBindVertexArray(
			prevVao);

	GlStateManager._glUseProgram(
			prevProgram);

	GlStateManager._bindTexture(
			prevTex0);

	GlStateManager._activeTexture(
			GL13.GL_TEXTURE1);

	GlStateManager._bindTexture(
			prevTex1);

	GL33.glBindSampler(
			1,
			prevSampler1);

	GlStateManager._activeTexture(
			GL13.GL_TEXTURE0 + 2);

	GlStateManager._bindTexture(
			prevTex2);

	GL33.glBindSampler(
			2,
			prevSampler2);

	GlStateManager._activeTexture(
			GL13.GL_TEXTURE0 + 3);

	GlStateManager._bindTexture(
			prevTex3);

	GL33.glBindSampler(
			3,
			prevSampler3);

	GlStateManager._activeTexture(
			GL13.GL_TEXTURE0 + 4);

	GlStateManager._bindTexture(
			prevTex4);

	GL33.glBindSampler(
			4,
			prevSampler4);

	GlStateManager._activeTexture(
			GL13.GL_TEXTURE0 + 5);

	GlStateManager._bindTexture(
			prevTex5);

	GL33.glBindSampler(
			5,
			prevSampler5);

	GlStateManager._activeTexture(
			GL13.GL_TEXTURE0 + 6);

	GlStateManager._bindTexture(
			prevTex6);

	GL33.glBindSampler(
			6,
			prevSampler6);

	restoreTextureUnit(13, prevTex13, prevSampler13);
	restoreTextureUnit(12, prevTex12, prevSampler12);
	GlStateManager._activeTexture(GL13.GL_TEXTURE0 + 11);
	GlStateManager._bindTexture(prevTex11); GL33.glBindSampler(11, prevSampler11);
	GlStateManager._activeTexture(GL13.GL_TEXTURE0 + 10);
	GlStateManager._bindTexture(prevTex10); GL33.glBindSampler(10, prevSampler10);
	GlStateManager._activeTexture(GL13.GL_TEXTURE0 + 9);
	GlStateManager._bindTexture(prevTex9); GL33.glBindSampler(9, prevSampler9);
	GlStateManager._activeTexture(GL13.GL_TEXTURE0 + 8);
	GlStateManager._bindTexture(prevTex8); GL33.glBindSampler(8, prevSampler8);

	GlStateManager._activeTexture(
			GL13.GL_TEXTURE0 + 7);

	GlStateManager._bindTexture(
			prevTex7);

	GL33.glBindSampler(
			7,
			prevSampler7);

	checkGl(debugPass + " restore samplers");

	GlStateManager._activeTexture(
			prevActive);

	checkGl(debugPass + " restore active texture");

	GL11.glCullFace(
			prevCullMode);

	GL11.glFrontFace(
			prevFrontFace);

	checkGl(debugPass + " restore cull/front");

	RenderGlState.blend(
			prevBlendSrcRgb,
			prevBlendDstRgb,
			prevBlendSrcAlpha,
			prevBlendDstAlpha);

	restoreColorMask(
			prevColorMask);

	GlStateManager._depthFunc(
			prevDepthFunc);

	GlStateManager._depthMask(
			prevDepthMask);

	if (prevDepth) {
		GlStateManager._enableDepthTest();
	} else {
		GlStateManager._disableDepthTest();
	}

	if (prevCull) {
		GlStateManager._enableCull();
	} else {
		GlStateManager._disableCull();
	}

	if (prevBlend) {
		GlStateManager._enableBlend();
	} else {
		GlStateManager._disableBlend();
	}

	if (prevPolygonOffset) {
		GlStateManager._enablePolygonOffset();

		GlStateManager._polygonOffset(
				prevPolygonFactor,
				prevPolygonUnits);
	} else {
		GlStateManager._disablePolygonOffset();
	}

	checkGl(debugPass + " restore blend/color/depth");

	GlStateManager._viewport(
			prevViewport[0],
			prevViewport[1],
			prevViewport[2],
			prevViewport[3]);

	GlStateManager._glBindFramebuffer(
			GL30.GL_FRAMEBUFFER,
			prevFbo);

	checkGl(debugPass + " restore viewport/FBO");

	checkGl(debugPass + " BEFORE profiler end");

	GPU_PROFILER.end(gpuPass);

	checkGl(debugPass + " AFTER profiler end");
}

	private static void restoreColorMask(int mask) {
		RenderGlState.colorMask(
				(mask & 1) != 0,
				(mask & 2) != 0,
				(mask & 4) != 0,
				(mask & 8) != 0);
	}

	private static final String OIT_VS = "#version 330 core\n"
			+ "out vec2 vUv; void main(){ vec2 p=vec2((gl_VertexID==1)?3.0:-1.0,(gl_VertexID==2)?3.0:-1.0); vUv=p*0.5+0.5; gl_Position=vec4(p,0,1); }\n";
	private static final String OIT_FS = "#version 330 core\n"
			+ "in vec2 vUv; out vec4 f; uniform sampler2D uAccum; uniform sampler2D uReveal;\n"
			+ "void main(){ vec4 a=texture(uAccum,vUv); float alpha=clamp(1.0-texture(uReveal,vUv).r,0.0,1.0); if(alpha<0.001) discard;"
			+ " vec3 color=a.rgb/max(a.a,1e-4); float peak=max(max(color.r,color.g),color.b); color/=max(peak,1.0); f=vec4(color,alpha); }\n";

	private static void compositeOit(int w, int h) {
		GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, mcFbo);
		GL20.glDrawBuffers(GL30.GL_COLOR_ATTACHMENT0);
		GlStateManager._viewport(0, 0, w, h);
		GlStateManager._glUseProgram(oitCompositeProg);
		GlStateManager._disableDepthTest();
		GlStateManager._depthMask(false);
		GlStateManager._enableBlend();
		// Do not rely on GlStateManager here: indexed OIT blending bypasses its cache.
		RenderGlState.blend(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
				GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
		GlStateManager._activeTexture(GL13.GL_TEXTURE0); GlStateManager._bindTexture(oitAccumTex); GL20.glUniform1i(oitCompAccum, 0);
		GlStateManager._activeTexture(GL13.GL_TEXTURE1); GlStateManager._bindTexture(oitRevealTex); GL20.glUniform1i(oitCompReveal, 1);
		GL30.glBindVertexArray(oitVao);
		GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
	}

	/**
	 * Maintain the section set around the player.
	 *
	 * <p>Phase 2.3: meshing is now off-thread via {@link ChunkMeshExecutor}.
	 * Each frame we:
	 * <ol>
	 *   <li>Drain completed meshes from the executor and upload them to GL (render thread).</li>
	 *   <li>Drain the dirty queue — evicts stale sections (including any just uploaded above).</li>
	 *   <li>Drop out-of-region sections.</li>
	 *   <li>Submit new sections to the executor (nearest-first, budgeted submissions per frame).</li>
	 * </ol>
	 * Drain-completed BEFORE drain-dirty so that a section made dirty the same frame it
	 * was first meshed is immediately evicted and re-queued correctly.
	 */
	private static void updateSections(MinecraftClient client) {
		// Scheduling asks the same 3x3 surface question for many vertical sections. Keep the answer only
		// for this pass so newly received neighbours are visible on the very next frame.
		NEIGHBOR_SURFACE_FRAME_CACHE.clear();
		BlockPos p = client.player.getBlockPos();
		int pcx = p.getX() >> 4;
		int pcz = p.getZ() >> 4;
		int psy = p.getY() >> 4;
		// Publish the viewer domain before consuming light notifications. A teleport across the surface
		// boundary must not spend one frame applying updates to the world the player just left.
		updateUndergroundResidency(client.world, client.player, p, pcx, psy, pcz);
		// Every notification in the active domain is applied in this drain. The dispatcher performs only
		// lossless deduplication; there is no time interval, cooldown or section quota.
		com.moneyakshaders.client.ClientLightDispatcher.drainLightRenderUpdates(client.worldRenderer, 0);
		long nowMs = System.currentTimeMillis();
		drainBlockEditRelights(nowMs);
		drainProvisionalRelights(nowMs);

		updateStreamingDirection(p);
		// Coalesce packet/color invalidations outside the packet callback.  First meshes do not need
		// this work to start; only already live/in-flight neighbours receive the follow-up refresh.
		drainReceivedColumnRemeshes(psy);
		// Publish the viewer chunk for the mesh workers' biome-tint tier choice (BiomeBlurTint).
		BiomeBlurTint.setViewerChunk(pcx, pcz);
		int viewRadius = Math.min(Math.max(2, client.options.getClampedViewDistance()), RADIUS_CAP);
		int shadowRadius = Math.min(Math.max(2, MoneyakShadersConfig.get().shadowDistanceChunks), RADIUS_CAP);
		// A far shadow cascade only receives tree casters that have a terrain mesh. Stream every
		// column already received by the client up to the requested shadow radius; this remains
		// budgeted and never requests chunks from the server.
		radiusChunks = Math.max(viewRadius, shadowRadius);
		if (client.world != admissionScanWorld || pcx != admissionScanCx || psy != admissionScanCy
				|| pcz != admissionScanCz || radiusChunks != admissionScanRadius) {
			admissionScanDirty = true;
			admissionScanWorld = client.world;
			admissionScanCx = pcx;
			admissionScanCy = psy;
			admissionScanCz = pcz;
			admissionScanRadius = radiusChunks;
		}

		// Progressive render distance: reset to a small ring on world change / teleport, then grow.
		boolean worldChanged = client.world != lastLoadWorld;
		boolean rampReset = worldChanged
				|| (lastPlayerCx != Integer.MIN_VALUE
						&& Math.max(Math.abs(pcx - lastPlayerCx), Math.abs(pcz - lastPlayerCz)) > 4);
		if (worldChanged) {
			// A full disconnect clears lastLoadWorld in tickDiskCache.  Only that cold start may restore
			// the on-disk bucket: proxy backends share one visible address (and often minecraft:overworld),
			// so restoring while a live connection switches backend could briefly draw another server's
			// geometry.  In-session RAM orphans still cover ordinary backtracking without this ambiguity.
			boolean coldConnection = lastLoadWorld == null;
			// A direct world/backend hand-off is allowed to replace ClientWorld without an intervening
			// tick where mc.world is null. Invalidate the disk decoder on every world identity change,
			// not only on the disconnect path, otherwise its old entries can arrive after clearLiveSections
			// and be uploaded into the new world. Entries are generation-tagged as a second guard against
			// the narrow check-then-add race in the loader thread.
			diskLoadGeneration.incrementAndGet();
			diskLoadInFlight = false;
			PENDING_INSTALL.clear();
			clearSnapshotJobs();
			// A dimension change keeps the same connection, so flush the world we're leaving to disk, drop
			// the RAM cache (it belongs to that world), then stream the world we're entering back in.
			flushDiskCache(client, lastLoadWorld, true);
			clearLiveSections();
			executor.cancelAll(); // workers may finish, but cannot publish old-world geometry afterwards
			clearOrphans();
			// Meshes still waiting to upload belong to the world we are leaving. Drop them (freeing
			// their native buffers) so a dimension change can't stamp stale geometry into the new one.
			for (ChunkMeshExecutor.MeshResult stale : UPLOAD_BACKLOG) {
				stale.mesh().free();
				executor.releaseReadyResultSlot(stale.streamPriority());
			}
			UPLOAD_BACKLOG.clear();
			UPLOAD_BACKLOG_KEYS.clear();
			if (coldConnection) {
				loadDiskCache(client, client.world);
			}
		}
		lastLoadWorld = client.world;

		// Install decoded cache sections before promotion below can draw them.  With no fresh worker upload
		// pending, restore a bounded larger slice; otherwise keep the cache work deliberately small.  Both
		// paths are charged to the same upload bucket as ordinary meshes.
		if (!PENDING_INSTALL.isEmpty()) {
			boolean cacheOnly = UPLOAD_BACKLOG.isEmpty() && executor.idle();
			int installLimit = cacheOnly ? CACHE_INSTALL_IDLE_MAX_PER_FRAME : CACHE_INSTALL_BUSY_MAX_PER_FRAME;
			long installDeadline = System.nanoTime() + (cacheOnly
					? CACHE_INSTALL_IDLE_BUDGET_NANOS : CACHE_INSTALL_BUSY_BUDGET_NANOS);
			boolean useFrameBudget = MoneyakShadersConfig.get().frameBudgetEnabled;
			if (useFrameBudget) {
				com.moneyakshaders.client.FrameWorkBudget.startBucket(
						com.moneyakshaders.client.FrameWorkBudget.BUCKET_MESH_UPLOAD);
			}
			int installed = 0;
			try {
				PendingDiskInstall pending;
				while (installed < installLimit
						&& (installed == 0 || (System.nanoTime() < installDeadline
								&& (!useFrameBudget || com.moneyakshaders.client.FrameWorkBudget.hasBudget(
										com.moneyakshaders.client.FrameWorkBudget.BUCKET_MESH_UPLOAD))))
						&& (pending = PENDING_INSTALL.poll()) != null) {
					if (pending.generation() != diskLoadGeneration.get()) {
						installed++;
						continue;
					}
					SectionDiskCache.Entry pe = pending.entry();
					int cacheCx = ChunkSectionPos.unpackX(pe.key);
					int cacheSy = ChunkSectionPos.unpackY(pe.key);
					int cacheCz = ChunkSectionPos.unpackZ(pe.key);
					if (!shouldDeferUnderground(client.world, cacheCx, cacheSy, cacheCz, pcx, psy, pcz)) {
						installEntry(pe);
					}
					installed++;
				}
			} finally {
				if (useFrameBudget) {
					com.moneyakshaders.client.FrameWorkBudget.endBucket(
							com.moneyakshaders.client.FrameWorkBudget.BUCKET_MESH_UPLOAD);
				}
			}
		}
		// Biome-tint tier sweep. Tints are baked into the vertices, so a section only changes tier by
		// being re-meshed. Moving into a new chunk can push a whole ring of sections past the
		// hysteresis band at once, so mark them a few per frame (async, never the sync edit path)
		// instead of dumping hundreds of rebuilds into one frame's budget. The pass keeps running
		// until a full scan finds nothing left to convert.
		if (pcx != lastPlayerCx || pcz != lastPlayerCz) {
			biomeTierSweepPending = true;
		}
		// Never compete with streaming: a tint upgrade is cosmetic and can wait until the mesh queue
		// has drained. Exploring new terrain would otherwise pay for both at once.
		if (biomeTierSweepPending && executor.idle()) {
			int tierBudget = BIOME_TIER_MARKS_PER_FRAME;
			for (Sec s : SECTIONS.values()) {
				if (tierBudget == 0) {
					break;
				}
				if (BiomeBlurTint.wantsRemesh((int) s.bx, (int) s.bz, s.biomeFar)) {
					com.moneyakshaders.client.DebugStats.remeshBiomeTier.incrementAndGet();
					markDirtyAsync(s.cx, s.sy, s.cz);
					tierBudget--;
				}
			}
			biomeTierSweepPending = tierBudget == 0; // budget left over ⇒ the scan reached the end
		}
		lastPlayerCx = pcx;
		lastPlayerCz = pcz;
		if (rampReset) {
			effectiveRadiusChunks = Math.min(MIN_LOAD_RADIUS, radiusChunks);
		}
		effectiveRadiusChunks = Math.min(effectiveRadiusChunks, radiusChunks);
		// Shadow distance is an explicit quality promise. Keep mesh submission budgeted, but do not
		// hold the shadow-caster range behind the old "complete every underground section in this
		// ring first" gate; distant surface/tree sections can now enter the worker queue immediately.
		if (MoneyakShadersConfig.get().sunShadows) {
			effectiveRadiusChunks = radiusChunks;
		}

		// 1. Upload completed async meshes (GL must be on render thread).
		//    Double-buffer swap: if this section already has an uploaded mesh (a re-mesh of a
		//    dirty section), the OLD mesh stayed drawn the whole time — free it only now that
		//    the replacement is ready, so an edit/load never leaves a hole.
		RESULT_SCRATCH.clear();
		int uploadBudget = Math.max(1, MoneyakShadersConfig.get().maxChunkUploadsPerFrame >> gpuWorkloadLevel);
		executor.drainTo(RESULT_SCRATCH, uploadBudget);
		boolean timeline = MoneyakShadersConfig.get().chunkLoadTimelineEnabled;
		if (timeline) {
			for (ChunkMeshExecutor.MeshResult r : RESULT_SCRATCH) {
				com.moneyakshaders.client.ChunkLoadTimeline.mark(r.key(),
						com.moneyakshaders.client.ChunkLoadTimeline.PHASE_UPLOAD_QUEUED);
			}
		}
		UPLOAD_BACKLOG.addAll(RESULT_SCRATCH);
		for (ChunkMeshExecutor.MeshResult result : RESULT_SCRATCH) {
			UPLOAD_BACKLOG_KEYS.add(result.key());
		}
		// Adaptive frame budget (spec §15). Bucket allowance replaces the old hardcoded 2 ms cap when
		// frameBudgetEnabled=true; falls back to the ceiling constant otherwise. Always uploads at
		// least one section per frame so loading can never stall on a single heavy section.
		boolean useFrameBudget = MoneyakShadersConfig.get().frameBudgetEnabled;
		long uploadDeadline = System.nanoTime() + UPLOAD_BUDGET_NANOS;
		if (useFrameBudget) {
			com.moneyakshaders.client.FrameWorkBudget.startBucket(
					com.moneyakshaders.client.FrameWorkBudget.BUCKET_MESH_UPLOAD);
		}
		int uploaded = 0;
		int arenaFailures = 0;
		ChunkMeshExecutor.MeshResult done;
		int uploadByteBudget = Math.max(0, MoneyakShadersConfig.get().maxChunkUploadBytesPerFrame);
		if (gpuWorkloadLevel > 0) uploadByteBudget = Math.max(64 * 1024,
				(uploadByteBudget > 0 ? uploadByteBudget : 2 * 1024 * 1024) >> gpuWorkloadLevel);
		uploadFrameBytesRemaining = uploadByteBudget > 0 ? uploadByteBudget : Long.MAX_VALUE;
		boolean incrementalStillPending = false;
		ARENA_DEFERRED_SCRATCH.clear();
		try {
			// A large first-load mesh may be copied over several frames. Never let it sit in front of
			// a resident block edit: retain its CPU mesh, release only its unfinished arena allocation,
			// and let the urgent replacement publish in this frame.
			if (activeIncrementalUpload != null && hasReadyUrgentUpload()) {
				IncrementalMeshUpload paused = activeIncrementalUpload;
				activeIncrementalUpload = null;
				cancelIncrementalUpload(paused, false);
				UPLOAD_BACKLOG.addLast(paused.result);
				UPLOAD_BACKLOG_KEYS.add(paused.result.key());
			}
			if (activeIncrementalUpload != null) {
				IncrementalMeshUpload active = activeIncrementalUpload;
				if (!uploadResultStillCurrent(active.result)) {
					cancelIncrementalUpload(active, false);
					rejectStaleUploadResult(active.result);
					activeIncrementalUpload = null;
				} else if (stepIncrementalUpload(active, uploadByteBudget, uploadDeadline, useFrameBudget)) {
					publishUploadedMesh(active.result, active.section, timeline);
					activeIncrementalUpload = null;
					uploaded++;
					incrementalStillPending = checkUploadBudget(uploaded, uploadDeadline, useFrameBudget);
				} else {
					incrementalStillPending = true;
				}
			}
		while (!incrementalStillPending && (done = pollPrioritisedUpload(pcx, psy, pcz)) != null) {
				// A fluid/block packet can arrive after this worker result entered the ready queue but
				// before DIRTY is drained below. Never expose that stale intermediate state, even for
				// one frame; keep the previous GPU mesh and rebuild from the newest content revision.
				if (!uploadResultStillCurrent(done)) {
					rejectStaleUploadResult(done);
					continue;
				}
				Sec s;
				long uploadBytes = incrementalUploadBytes(done.mesh());
				if (done.streamPriority() > STREAM_URGENT
						&& uploadByteBudget > 0 && uploadBytes >= MIN_INCREMENTAL_UPLOAD_BYTES) {
					activeIncrementalUpload = beginIncrementalUpload(done);
					if (activeIncrementalUpload == null) {
						s = null;
					} else if (stepIncrementalUpload(activeIncrementalUpload, uploadByteBudget,
							uploadDeadline, useFrameBudget)) {
						s = activeIncrementalUpload.section;
						activeIncrementalUpload = null;
					} else {
						incrementalStillPending = true;
						break;
					}
				} else {
					s = upload(done.mesh(), done.bx(), done.by(), done.bz());
					if (s != null) uploadFrameBytesRemaining -= uploadBytes;
				}
				if (s == null) {
					com.moneyakshaders.client.DebugStats.arenaUploadRetries.incrementAndGet();
					// Arena pressure is transient while the bounded orphan reclaim drains. Keep the exact
					// latest-wins mesh in RAM and retry it next frame instead of freeing it and silently
					// losing the section. The previous GPU mesh (if any) remains visible meanwhile.
					// Keep the key pending, but hide this exact failed mesh from the priority scan for the
					// rest of this frame. Otherwise it is selected again immediately and one large section
					// head-of-line blocks every smaller visible surface mesh that could still fit a free gap.
					ARENA_DEFERRED_SCRATCH.add(done);
					UPLOAD_BACKLOG_KEYS.add(done.key());
					arenaFailures++;
					if (arenaFailures >= MAX_ARENA_FAILURES_PER_FRAME
							|| checkUploadBudget(1, uploadDeadline, useFrameBudget)) break;
					continue;
				}
				publishUploadedMesh(done, s, timeline);
				uploaded++;
				if (checkUploadBudget(uploaded, uploadDeadline, useFrameBudget)) break;
			}
		} finally {
			// Retry exact latest-wins meshes next frame. Their ready-result permits intentionally remain
			// held, keeping producer-side native memory bounded while the arena is under pressure.
			UPLOAD_BACKLOG.addAll(ARENA_DEFERRED_SCRATCH);
			ARENA_DEFERRED_SCRATCH.clear();
			if (useFrameBudget) {
				com.moneyakshaders.client.FrameWorkBudget.endBucket(
						com.moneyakshaders.client.FrameWorkBudget.BUCKET_MESH_UPLOAD);
			}
		}

		// 2. Drain dirty set into REMESH. We do NOT free or remove the section here — the old
		//    mesh keeps drawing until step 1 swaps in the rebuild. A running worker is invalidated
		//    but still occupies its key until it exits, so its stale result cannot publish and a
		//    packet burst cannot start a second expensive mesh for the same section.
		// Coherent primitive batch: every key published before the drain is consumed exactly once;
		// keys arriving during/after it stay queued for the next frame without iterator races or boxing.
		DIRTY.drainTo(DIRTY_SCRATCH, 0);
		for (int i = 0; i < DIRTY_SCRATCH.size(); i++) {
			long key = DIRTY_SCRATCH.getLong(i);
			CONTENT_DIRTY_PENDING.remove(key);
			// Extraction is intentionally spread over several frames.  Do not let a
			// partially captured pre-update view reach a worker after this section
			// changed; keep the previous GPU mesh visible and rebuild from fresh data.
			Sec resident = SECTIONS.get(key);
			SnapshotJob copying = SNAPSHOT_JOBS.get(key);
			// Light revisions are tracked by the builder itself. Keep copied geometry when only
			// light changed; cancelling here repeatedly reset resident snapshots to the blocks stage.
			if (copying != null && copying.contentRevision == contentRevision(key)) {
				snapshotUpdatesPreserved++;
				continue;
			}
			// A received section has no old GPU mesh yet.  Light packets frequently arrive while its
			// immutable snapshot is being copied; cancelling that first snapshot on every packet made a
			// busy multiplayer spawn restart it forever, leaving apparently "forgotten" chunk holes.
			// Let the initial mesh complete, retain this dirty key, and apply all coalesced light changes
			// in one normal remesh afterwards.  Existing meshes still use the strict invalidate/swap path.
			if (resident == null && isMeshPending(key)) {
				REMESH.add(key);
				continue;
			}
			if (copying != null) snapshotUpdatesRestarted++;
			cancelSnapshotJob(key);
			if (LOCAL_BLOCK_EDITS.contains(key)) executor.cancel(key);
			else executor.invalidate(key);
			if (resident == null || resident.residency != Sec.Residency.SLEEPING) {
				REMESH.add(key);
			}
		}
		finishBlockEditBurst();

		// 3. Drop out-of-region. With the section cache on, geometry-bearing sections are STASHED (GPU
		//    regions kept) instead of freed, so a return to these coords can redraw them instantly; empty
		//    air markers are just freed. Either way forget any pending rebuild / in-flight work so a stale
		//    in-flight result can't resurrect a duplicate.
		boolean cacheOn = MoneyakShadersConfig.get().sectionCache;
		boolean regionBoundaryChanged = client.world != lastEvictionWorld || pcx != lastEvictionCx
				|| psy != lastEvictionSy || pcz != lastEvictionCz || radiusChunks != lastEvictionRadius
				|| cacheOn != lastEvictionCacheOn;
		if (regionBoundaryChanged) {
		SCENE_RESIDENCY.beginSweep();
		prioritizeViewerScenes(pcx, psy, pcz);
		// Rebase incomplete FIRST meshes around the new camera section. Without this, fast flight can
		// leave all producer slots occupied by partially-copied sections from the previous position;
		// even the newly visible section then cannot enter the queue until distant work finishes. Keep
		// resident remeshes (live edits retain their old coherent geometry), but retain only the nearest
		// half of first-load snapshots so the next admission pass has room for the new foreground.
		rebaseFirstLoadSnapshots(pcx, psy, pcz);

		CACHE_RESIDENCY.beginSweep();
			lastEvictionWorld = client.world;
			lastEvictionCx = pcx;
			lastEvictionSy = psy;
			lastEvictionCz = pcz;
			lastEvictionRadius = radiusChunks;
			lastEvictionCacheOn = cacheOn;
		}

		if (cacheOn) restoreCachedScenes(client, pcx, psy, pcz);

		// 4. Prioritised section submission.
		//
		//    Priority order:
		//      A. Player's own section — submitted unconditionally (outside budget), so it
		//         is never starved by the ring scan.
		//      B. Sections visible in the camera frustum (FOV) — nearest first, 2 per cycle.
		//      C. Sections behind the camera — nearest first, 1 per cycle.
		//
		//    Pattern within budget: 2 FOV, 1 behind, 2 FOV, 1 behind …
		//    Once the FOV queue is exhausted, behind sections drain without a ratio limit.
		//    This ensures the player always sees terrain building up in front of them first.
		var world = client.world;
		var brm   = client.getBlockRenderManager();
		var cols  = client.getBlockColors();
		advanceSnapshotJobs(world, brm, cols);

		// Adaptive budgets: when the last frame ran long (chunk-load burst, fast turning/moving), shrink
		// this frame's mesh work so loading stretches out instead of stacking more onto an already-slow
		// frame. Recovers instantly once frames are fast again.
		long nowNs = System.nanoTime();
		long frameNs = lastUpdateNs == 0L ? 0L : nowNs - lastUpdateNs;
		lastUpdateNs = nowNs;
		int budget = MESH_BUDGET_PER_FRAME;
		if (frameNs > 50_000_000L) {        // < 20 fps → minimal loading work
			budget = 4;
		} else if (frameNs > 25_000_000L) { // < 40 fps → half
			budget = MESH_BUDGET_PER_FRAME / 2;
		}

		// A: player's own section receives the highest worker priority. It must never synchronously mesh
		// on the render thread: a join or teleport is exactly where multiplayer has its largest packet,
		// light and model burst, and a single heavy section would dominate p99.
		{
			long pk = ChunkSectionPos.asLong(pcx, psy, pcz);
			if (!isMeshPending(pk) && (!SECTIONS.containsKey(pk) || REMESH.contains(pk))) {
				var playerColumn = world.getChunk(pcx, pcz, net.minecraft.world.chunk.ChunkStatus.FULL, false);
				boolean accepted = playerColumn != null
						&& submitSnapshot(pk, world, pcx << 4, psy << 4, pcz << 4, STREAM_URGENT, playerColumn);
				if (accepted) {
					REMESH.remove(pk);
				}
			}
		}

		// Guaranteed local safety net: the section containing the player and its immediate 3x3x3
		// neighbourhood must never wait behind packet-order background work. This also covers cave mouths,
		// overhangs and the section directly below a thin grass cap, which heightmaps cannot prove visible.
		// Existing extraction is promoted in-place; copied cells are never thrown away.
		int[] nearDyOrder = { 0, -1, 1 };
		for (int ring = 0; ring <= 1 && budget > 0; ring++) {
			for (int dx = -ring; dx <= ring && budget > 0; dx++) {
				for (int dz = -ring; dz <= ring && budget > 0; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
					int cx = pcx + dx, cz = pcz + dz;
					var column = world.getChunk(cx, cz, net.minecraft.world.chunk.ChunkStatus.FULL, false);
					if (column == null) continue;
					for (int dy : nearDyOrder) {
						int sy = psy + dy;
						long key = ChunkSectionPos.asLong(cx, sy, cz);
						if (SNAPSHOT_JOBS.containsKey(key)) {
							promoteSnapshotJob(key, STREAM_URGENT);
							continue;
						}
						if (SECTIONS.containsKey(key) || isMeshPending(key) || targetSectionIsEmpty(column, sy)) continue;
						if (submitSnapshot(key, world, cx << 4, sy << 4, cz << 4, STREAM_URGENT, column)) {
							budget--;
						}
					}
				}
			}
		}

		// Refresh frustum so the FOV preference uses this frame's camera.
		if (haveMatrix) {
			updateFrustum();
			reprioritizeAdmissionQueuesForCamera(pcx, pcz);
		}

		// Live block/fluid edits affect geometry the player is already looking at. They must preempt
		// first-time surface streaming; otherwise a flowing-water packet can sit behind a full ring of
		// new chunks and leave the old water mesh visibly suspended for seconds. A light-only refresh in
		// the player's 5x5x5 section neighbourhood is urgent too: keeping all light work behind surface
		// admission let a continuously non-empty streaming ring starve a newly placed lamp forever. The
		// old mesh then changed only after a neighbouring block edit and block light stopped sharply at
		// the next section boundary. Far light refreshes retain the post-surface lane below. Bound this
		// pass so a large geometry packet cannot turn one frame into a submission spike. Light-only
		// refreshes retain the old GPU mesh. During first loading, bound their admissions and leave
		// producer slots for missing terrain instead of filling the window with resident light work.
		int liveRemeshBudget = Math.min(URGENT_SNAPSHOT_RESERVE, budget);
		int liveRemeshSubmitted = 0;
		int lightRemeshSubmitted = 0;
		boolean firstTerrainPending = hasForegroundFirstLoadSnapshots();
		for (it.unimi.dsi.fastutil.longs.LongIterator rit = REMESH.iterator(); rit.hasNext(); ) {
			long key = rit.nextLong();
			int scx = ChunkSectionPos.unpackX(key), ssy = ChunkSectionPos.unpackY(key), scz = ChunkSectionPos.unpackZ(key);
			boolean lightOnly = ASYNC_ONLY.contains(key) && contentRevision(key) == 0L;
			if (lightOnly && firstTerrainPending
					&& (lightRemeshSubmitted >= 2 || !hasSnapshotCapacity(STREAM_BACKGROUND))) continue;
			if (!lightOnly && (budget <= 0 || liveRemeshSubmitted >= liveRemeshBudget)) continue;
			// Light correctness is no longer visibility-gated. Camera/frustum priority was useful for
			// first terrain, but retaining it for resident relights left an off-screen half of a room stale;
			// turning back exposed a chunk-shaped colour boundary. Distance still chooses priority only.
			if (Math.abs(scx - pcx) > radiusChunks || Math.abs(scz - pcz) > radiusChunks
					|| !inActiveRange(world, scx, ssy, scz, psy)) {
				rit.remove();
				ASYNC_ONLY.remove(key);
				continue;
			}
			var column = world.getChunk(scx, scz, net.minecraft.world.chunk.ChunkStatus.FULL, false);
			if (column == null || isMeshPending(key)) continue;
			if (submitSnapshot(key, world, scx << 4, ssy << 4, scz << 4, STREAM_URGENT, column)) {
				ASYNC_ONLY.remove(key);
				rit.remove();
				if (lightOnly) lightRemeshSubmitted++;
				if (!lightOnly) {
					budget--;
					liveRemeshSubmitted++;
				}
			}
		}

		// Submit new sections NEAREST-FIRST in CONCENTRIC RINGS (all directions) out to the CURRENT
		// effective radius, so the area right around the player fills with NO HOLES — even behind the
		// camera — before any farther chunk. Within each ring, in-view (FOV) sections go first so the
		// view leads slightly. All async + budget-limited → gradual streaming, no render-thread flood.
		// When the occlusion graph exists, skip MESHING sections it can't reach (caves/rooms fully hidden
		// behind meshed solid rock): they'd never be drawn anyway, so building their geometry is pure
		// waste. They stream in the moment the graph reaches them (player moves / a wall opens). The graph
		// is conservative — unmeshed sections count as see-through — so this never hides a visible section.
		// The section-face BFS is a useful advisory signal, but it is not a proof that terrain is
		// invisible: vertical camera motion inside one section and server-built portal geometry can make
		// a valid distant receiver unreachable until the player crosses an arbitrary section boundary.
		// Never let it suppress FIRST admission.  Frustum culling still rejects off-screen work.
		boolean occlMesh = false;
		int newSubmits = 0;
		if (admissionScanDirty) {
			rebuildAdmissionQueues(world, pcx, psy, pcz);
		}
		int queuedSubmits = admitQueuedSections(world, pcx, psy, pcz, budget);
		budget -= queuedSubmits;
		newSubmits += queuedSubmits;
		// Fill the visible surface of every nearby received column before deeper vertical sections.
		// The surface lane may use the whole remaining admission budget; if fewer surface holes exist,
		// the untouched remainder immediately falls through to the vertical pass below. This makes the
		// first useful world image deterministic instead of interleaving it with buried section work.
		int surfaceBudget = budget;
		int surfaceSubmitted = 0;
		for (int r = 0; admissionScanDirty && r <= effectiveRadiusChunks && budget > 0
				&& surfaceSubmitted < surfaceBudget && hasSnapshotCapacity(); r++) {
			for (int cx = pcx - r; cx <= pcx + r && budget > 0 && surfaceSubmitted < surfaceBudget && hasSnapshotCapacity(); cx++) {
				for (int cz = pcz - r; cz <= pcz + r && surfaceSubmitted < surfaceBudget && hasSnapshotCapacity(); cz++) {
					if (Math.max(Math.abs(cx - pcx), Math.abs(cz - pcz)) != r) continue;
					var column = world.getChunk(cx, cz, net.minecraft.world.chunk.ChunkStatus.FULL, false);
					if (column == null) continue;
					long surfaceRange = surfaceRange(world, cx, cz, column);
					if (surfaceRange == Long.MIN_VALUE) continue;
					// Heightmaps return the first free Y above the highest matching block.  Treating that
					// value as an occupied coordinate selected the empty section above terrain whenever
					// the surface ended on a 16-block boundary, so the supposedly-fast surface pass
					// visibly filled air first and left the actual ground to the later vertical sweep.
					int highestOccupiedY = ((int) (surfaceRange >> 32)) - 1;
					int sy = Math.floorDiv(highestOccupiedY, 16);
					// This is the column's highest occupied section, so it cannot be closed underground.
					if (!inActiveRange(world, cx, sy, cz, psy)) continue;
					long key = ChunkSectionPos.asLong(cx, sy, cz);
					if (SECTIONS.containsKey(key) || isMeshPending(key)) continue;
					if (submitSnapshot(key, world, cx << 4, sy << 4, cz << 4, STREAM_SURFACE, column)) {
						budget--; newSubmits++; surfaceSubmitted++;
					}
				}
			}
		}
		// Second surface lane: non-empty sections in the visible surface envelope. This includes tall
		// iceberg/cliff walls below their heightmap top, but the cached bit mask excludes empty section
		// gaps so air and water-column voids do not consume snapshot slots.
		for (int r = 0; admissionScanDirty && r <= effectiveRadiusChunks && budget > 0
				&& surfaceSubmitted < surfaceBudget && hasSnapshotCapacity(); r++) {
			for (int cx = pcx - r; cx <= pcx + r && budget > 0 && surfaceSubmitted < surfaceBudget && hasSnapshotCapacity(); cx++) {
				for (int cz = pcz - r; cz <= pcz + r && budget > 0 && surfaceSubmitted < surfaceBudget && hasSnapshotCapacity(); cz++) {
					if (Math.max(Math.abs(cx - pcx), Math.abs(cz - pcz)) != r) continue;
					var column = world.getChunk(cx, cz, net.minecraft.world.chunk.ChunkStatus.FULL, false);
					if (column == null) continue;
					long range = surfaceRange(world, cx, cz, column);
					if (range == Long.MIN_VALUE) continue;
					int bottomSection = world.getBottomY() >> 4;
					int highSy = Math.floorDiv(((int) (range >> 32)) - 1, 16);
					long surfaceMask = surfaceSectionMask(world, cx, cz);
					while (surfaceMask != 0L && budget > 0
							&& surfaceSubmitted < surfaceBudget && hasSnapshotCapacity()) {
						int sectionIndex = Long.numberOfTrailingZeros(surfaceMask);
						surfaceMask &= surfaceMask - 1L;
						int sy = bottomSection + sectionIndex;
						if (sy == highSy) continue; // already handled by the first surface lane
						if (haveMatrix && !sectionVisible((float) ((cx << 4) - capCamX),
								(float) ((sy << 4) - capCamY), (float) ((cz << 4) - capCamZ))) continue;
						if (!inActiveRange(world, cx, sy, cz, psy) || targetSectionIsEmpty(column, sy)) continue;
						long key = ChunkSectionPos.asLong(cx, sy, cz);
						if (SECTIONS.containsKey(key) || isMeshPending(key)) continue;
						if (submitSnapshot(key, world, cx << 4, sy << 4, cz << 4, STREAM_SURFACE, column)) {
							budget--; newSubmits++; surfaceSubmitted++;
						}
					}
				}
			}
		}

		// Cosmetic light refreshes retain their old coherent GPU mesh, and background shadow casters do
		// not fill a visible hole. Admit both only after every currently discoverable surface hole has
		// had first use of this frame's remaining budget. Any overflow live edit stays urgent here too.
		int remeshBudget = Math.max(1, budget / 3);
		int remeshSubmitted = 0;
		for (it.unimi.dsi.fastutil.longs.LongIterator rit = REMESH.iterator();
				rit.hasNext() && budget > 0 && remeshSubmitted < remeshBudget; ) {
			long key = rit.nextLong();
			int scx = ChunkSectionPos.unpackX(key), ssy = ChunkSectionPos.unpackY(key), scz = ChunkSectionPos.unpackZ(key);
			if (Math.abs(scx - pcx) > radiusChunks || Math.abs(scz - pcz) > radiusChunks
					|| !inActiveRange(world, scx, ssy, scz, psy)) {
				rit.remove();
				ASYNC_ONLY.remove(key);
				continue;
			}
			var column = world.getChunk(scx, scz, net.minecraft.world.chunk.ChunkStatus.FULL, false);
			if (column == null || isMeshPending(key)) {
				continue;
			}
			int streamPriority = ASYNC_ONLY.contains(key) ? STREAM_REFRESH : STREAM_URGENT;
			if (submitSnapshot(key, world, scx << 4, ssy << 4, scz << 4, streamPriority, column)) {
				ASYNC_ONLY.remove(key);
				rit.remove();
				budget--;
				remeshSubmitted++;
			}
		}

		if (MoneyakShadersConfig.get().sunShadows && !SHADOW_CASTERS.isEmpty()
				&& budget > 0 && hasSnapshotCapacity(STREAM_BACKGROUND)) {
			int casterBudget = Math.max(1, budget / 3);
			int submittedCasters = 0;
			for (it.unimi.dsi.fastutil.longs.LongIterator cit = SHADOW_CASTERS.iterator();
					cit.hasNext() && budget > 0 && submittedCasters < casterBudget
							&& hasSnapshotCapacity(STREAM_BACKGROUND); ) {
				long key = cit.nextLong();
				int scx = ChunkSectionPos.unpackX(key);
				int ssy = ChunkSectionPos.unpackY(key);
				int scz = ChunkSectionPos.unpackZ(key);
				if (Math.abs(scx - pcx) > radiusChunks || Math.abs(scz - pcz) > radiusChunks
						|| !inActiveRange(world, scx, ssy, scz, psy) || SECTIONS.containsKey(key) || isMeshPending(key)) {
					continue;
				}
				var column = world.getChunk(scx, scz, net.minecraft.world.chunk.ChunkStatus.FULL, false);
				if (column == null) continue;
				if (submitSnapshot(key, world, scx << 4, ssy << 4, scz << 4, STREAM_BACKGROUND, column)) {
					budget--;
					submittedCasters++;
				}
			}
		}

		// Once the surface is queued, fill sky/interior sections with the normal frustum priority.
		for (int r = 0; admissionScanDirty && r <= effectiveRadiusChunks && budget > 0 && hasSnapshotCapacity(); r++) {
			for (int pass = 0; pass < 2 && budget > 0 && hasSnapshotCapacity(); pass++) { // pass 0 = FOV, pass 1 = behind
				for (int cx = pcx - r; cx <= pcx + r && budget > 0 && hasSnapshotCapacity(); cx++) {
					for (int cz = pcz - r; cz <= pcz + r && budget > 0 && hasSnapshotCapacity(); cz++) {
						if (Math.max(Math.abs(cx - pcx), Math.abs(cz - pcz)) != r) continue;
						// Never spend worker time meshing a column the client has not received. Besides
						// producing empty meshes, this starved the already-loaded forest surface sections
						// needed by the far shadow cascade and caused catch-up spikes when data arrived.
						var column = world.getChunk(cx, cz, net.minecraft.world.chunk.ChunkStatus.FULL, false);
						if (column == null) continue;
						int minSy = Math.max(psy - 40, Math.min(psy - VERTICAL_SECTIONS, 2));
						int maxSy = psy + VERTICAL_SECTIONS;
						// Surface/eye-height sections cast the visible tree shadows. Submit those first,
						// then work outward vertically; the previous bottom-to-top walk delayed forests
						// behind a long queue of underground sections.
						for (int delta = 0; delta <= Math.max(psy - minSy, maxSy - psy) && budget > 0 && hasSnapshotCapacity(); delta++) {
							for (int sign = 0; sign < (delta == 0 ? 1 : 2) && budget > 0 && hasSnapshotCapacity(); sign++) {
							int sy = sign == 0 ? psy - delta : psy + delta;
								if (sy < minSy || sy > maxSy) continue;
								long key = ChunkSectionPos.asLong(cx, sy, cz);
								if (SECTIONS.containsKey(key) || isMeshPending(key)) continue;
								if (shouldDeferUnderground(world, cx, sy, cz, pcx, psy, pcz)) continue;
								// A section with no mesh has no connectivity data, therefore cannot be in
								// VISIBLE_SECTIONS yet. Treating that absence as occlusion made the first
								// few completed sections permanently starve all later server chunks. Once a
								// mesh exists, normal visibility/occlusion controls its subsequent rebuilds.
								if (occlMesh && SECTIONS.containsKey(key) && !VISIBLE_SECTIONS.contains(key)) continue;
							boolean inFov = !haveMatrix || sectionVisible(
									(float) ((cx << 4) - capCamX),
									(float) ((sy << 4) - capCamY),
									(float) ((cz << 4) - capCamZ));
							if ((pass == 0) != inFov) continue; // pass 0 takes FOV, pass 1 takes behind
							int streamPriority = streamPriorityForSection(world, column, cx, sy, cz, inFov, pcx, psy, pcz);
							if (submitSnapshot(key, world, cx << 4, sy << 4, cz << 4, streamPriority, column)) {
								budget--; newSubmits++;
							}
						}
					}
				}
			}
		}
	}

		// Grow the admission radius as soon as the current ring has been fully ADMITTED, not only
		// after every worker and GPU upload for that ring went idle.  The old idle gate serialised
		// radius 4 → 6 → 8 → …: a dense inner ring (or one slow upload) held back already-received
		// outer chunks for seconds/minutes even while workers had room for more work.  Admission is
		// still bounded by snapshot capacity, worker queue/ready permits and per-frame time buckets;
		// this overlaps stages without creating a render-thread burst or sacrificing near-first order.
		if (effectiveRadiusChunks < radiusChunks && newSubmits == 0 && budget > 0
				&& SURFACE_ADMISSION_QUEUE.isEmpty() && ENVELOPE_ADMISSION_QUEUE.isEmpty()
				&& DETAIL_ADMISSION_QUEUE.isEmpty()
				&& hasSnapshotCapacity(STREAM_BACKGROUND)) {
			effectiveRadiusChunks = Math.min(radiusChunks, effectiveRadiusChunks + RADIUS_RAMP_STEP);
			admissionScanDirty = true;
		}
	}

	/** Advance bounded immutable extraction, then release complete inputs to the mesh workers. */
	private static void advanceSnapshotJobs(net.minecraft.client.world.ClientWorld world,
			BlockRenderManager brm, BlockColors colors) {
		if (SNAPSHOT_JOBS.isEmpty()) return;
		MinecraftClient client = MinecraftClient.getInstance();
		int playerX = client != null && client.player != null ? client.player.getBlockX() >> 4 : 0;
		int playerY = client != null && client.player != null ? client.player.getBlockY() >> 4 : 0;
		int playerZ = client != null && client.player != null ? client.player.getBlockZ() >> 4 : 0;
		refreshSnapshotPriorityQueues(playerX, playerY, playerZ);
		MoneyakShadersConfig cfg = MoneyakShadersConfig.get();
		boolean tracked = cfg.frameBudgetEnabled;
		if (!EDIT_SNAPSHOT_QUEUE.isEmpty()) {
			if (tracked) com.moneyakshaders.client.FrameWorkBudget.startBucket("block-edit");
			try {
				advanceSnapshotPass(world, brm, colors, 16_384, true,
						System.nanoTime() + BlockEditLane.PREPARATION_NS, true);
			} finally {
				if (tracked) com.moneyakshaders.client.FrameWorkBudget.endBucket("block-edit");
			}
		}
		if (tracked) com.moneyakshaders.client.FrameWorkBudget.startBucket(
				com.moneyakshaders.client.FrameWorkBudget.BUCKET_SNAPSHOT);
		try {
			long passDeadlineNs = SNAPSHOT_JOBS.size() >= SNAPSHOT_BURST_JOB_THRESHOLD
					? System.nanoTime() + SnapshotBurstBudget.nanos(cfg.frameBudgetTargetMs,
							com.moneyakshaders.client.FrameProfiler.latestMs(),
							com.moneyakshaders.client.FrameProfiler.p95Ms())
					: Long.MAX_VALUE;
			// The visible surface envelope (cliffs, icebergs and tall tree-bearing sections) is foreground
			// too. Treating only the single highest section as foreground dropped extraction to the 16k-cell
			// background cap as soon as flat tops completed, leaving visible vertical terrain loading at
			// roughly one section per frame while the render thread had several milliseconds of spare budget.
			boolean foregroundPending = !SURFACE_ADMISSION_QUEUE.isEmpty()
					|| !ENVELOPE_ADMISSION_QUEUE.isEmpty();
			if (!foregroundPending) {
				for (SnapshotJob job : SNAPSHOT_JOBS.values()) {
					if (job.streamPriority <= STREAM_VISIBLE) {
						foregroundPending = true;
						break;
					}
				}
			}
			int laneCellCap = foregroundPending
					? FOREGROUND_SNAPSHOT_CELLS_PER_FRAME : BACKGROUND_SNAPSHOT_CELLS_PER_FRAME;
			int remaining = Math.max(256, Math.min(cfg.sectionSnapshotCellsPerFrame, laneCellCap));
			boolean urgentFirst = StreamingOrder.urgentFirst(snapshotServiceFrame++,
					!HIGH_SNAPSHOT_QUEUE.isEmpty(), !STREAM_SNAPSHOT_QUEUE.isEmpty());
			remaining = advanceSnapshotPass(world, brm, colors, remaining, urgentFirst, passDeadlineNs, false);
			if (remaining > 0 && System.nanoTime() < passDeadlineNs) {
				advanceSnapshotPass(world, brm, colors, remaining, !urgentFirst, passDeadlineNs, false);
			}
		} finally {
			if (tracked) com.moneyakshaders.client.FrameWorkBudget.endBucket(
					com.moneyakshaders.client.FrameWorkBudget.BUCKET_SNAPSHOT);
		}
	}

	private static int advanceSnapshotPass(net.minecraft.client.world.ClientWorld world, BlockRenderManager brm,
			BlockColors colors, int remaining, boolean highPriority, long passDeadlineNs, boolean editsOnly) {
		java.util.PriorityQueue<SnapshotQueueEntry> queue = editsOnly ? EDIT_SNAPSHOT_QUEUE : highPriority
				? HIGH_SNAPSHOT_QUEUE : STREAM_SNAPSHOT_QUEUE;
		DEFERRED_SNAPSHOT_ENTRIES.clear();
		try {
			while (remaining > 0 && System.nanoTime() < passDeadlineNs
					&& (editsOnly || !MoneyakShadersConfig.get().frameBudgetEnabled
							|| com.moneyakshaders.client.FrameWorkBudget.hasBudget(
									com.moneyakshaders.client.FrameWorkBudget.BUCKET_SNAPSHOT))) {
				SnapshotQueueEntry entry = editsOnly ? EDIT_SNAPSHOT_QUEUE.poll() : pollSnapshotJob(highPriority);
				if (entry == null) break;
				long key = entry.key();
				SnapshotJob job = SNAPSHOT_JOBS.get(key);
				if (job == null) continue; // cancelled after queue construction; lazily discarded
				int currentTier = currentSnapshotTier(job);
				if (currentTier < job.streamPriority) {
					job = new SnapshotJob(job.builder, job.bx, job.by, job.bz, currentTier,
							job.contentRevision, job.snapshotRecorded, job.enqueuedNs);
					SNAPSHOT_JOBS.put(key, job);
				}
				int slice = Math.min(remaining, SNAPSHOT_SLICE_CELLS);
				int consumed = job.builder.step(slice);
				remaining -= consumed;
				SectionInputSnapshot snapshot = job.builder.snapshot();
				if (snapshot != null) {
					if (!job.snapshotRecorded) {
						com.moneyakshaders.client.ChunkLoadTimeline.mark(key,
								com.moneyakshaders.client.ChunkLoadTimeline.PHASE_SNAPSHOT_DONE);
						job = new SnapshotJob(job.builder, job.bx, job.by, job.bz, job.streamPriority,
								job.contentRevision, true, job.enqueuedNs);
						SNAPSHOT_JOBS.put(key, job);
					}
					if (executor.submit(key, snapshot, brm, colors, job.bx, job.by, job.bz, job.streamPriority,
							job.contentRevision)) {
						SNAPSHOT_JOBS.remove(key);
					} else {
						// A complete immutable input cannot usefully consume more snapshot time until a worker slot opens.
						queue.add(entry);
						break;
					}
				}
				if (SNAPSHOT_JOBS.containsKey(key)) {
					if (consumed == 0) {
						// One section may wait for its local light halo to remain stable for 75 ms. Re-inserting
						// it immediately at the head made it block every other surface snapshot in the same
						// lane. Hold it aside only for this pass and continue with independent columns.
						DEFERRED_SNAPSHOT_ENTRIES.add(entry);
					} else {
						// Several slices may be consumed in one pass while time/cell budget remains.
						queue.add(entry);
					}
				}
			}
		} finally {
			queue.addAll(DEFERRED_SNAPSHOT_ENTRIES);
			DEFERRED_SNAPSHOT_ENTRIES.clear();
		}
		return remaining;
	}

	private static SnapshotQueueEntry pollSnapshotJob(boolean highPriority) {
		return SnapshotQueueRouting.poll(HIGH_SNAPSHOT_QUEUE, STREAM_SNAPSHOT_QUEUE, highPriority, entry -> {
			SnapshotJob job = SNAPSHOT_JOBS.get(entry.key());
			return job == null ? -1 : job.streamPriority == STREAM_URGENT ? 0 : 1;
		});
	}

	private static void refreshSnapshotPriorityQueues(int playerX, int playerY, int playerZ) {
		int headingSignature = (Math.round(streamDirectionX * 8f) & 0xFF)
				| ((Math.round(streamDirectionZ * 8f) & 0xFF) << 8);
		// Raw float bits changed for every tiny mouse movement and rebuilt both O(n log n) queues at
		// exactly the worst time (join/teleport). Visibility is only a distance-ring tie-breaker, so a
		// quantized 3-D view direction retains useful FOV priority without camera-jitter work.
		long viewSignature = !haveMatrix ? 0L
				: ((long) (Math.round(capturedModelView.m02() * 16f) & 0x3F) << 12)
						| ((long) (Math.round(capturedModelView.m12() * 16f) & 0x3F) << 6)
						| (Math.round(capturedModelView.m22() * 16f) & 0x3F);
		if (!snapshotQueuesDirty && playerX == snapshotQueuePlayerX && playerY == snapshotQueuePlayerY
				&& playerZ == snapshotQueuePlayerZ && headingSignature == snapshotQueueHeadingSignature
				&& viewSignature == snapshotQueueViewSignature) return;
		HIGH_SNAPSHOT_QUEUE.clear();
		STREAM_SNAPSHOT_QUEUE.clear();
		EDIT_SNAPSHOT_QUEUE.clear();
		long nowNs = System.nanoTime();
		for (var mapEntry : SNAPSHOT_JOBS.long2ObjectEntrySet()) {
			SnapshotJob job = mapEntry.getValue();
			long priority = snapshotPriority(job, playerX, playerY, playerZ, nowNs);
			SnapshotQueueEntry entry = new SnapshotQueueEntry(mapEntry.getLongKey(), priority, job.enqueuedNs);
			(job.streamPriority < 0 ? EDIT_SNAPSHOT_QUEUE : job.streamPriority == STREAM_URGENT
					? HIGH_SNAPSHOT_QUEUE : STREAM_SNAPSHOT_QUEUE).add(entry);
		}
		snapshotQueuesDirty = false;
		snapshotQueuePlayerX = playerX;
		snapshotQueuePlayerY = playerY;
		snapshotQueuePlayerZ = playerZ;
		snapshotQueueHeadingSignature = headingSignature;
		snapshotQueueViewSignature = viewSignature;
	}

	private static long snapshotPriority(SnapshotJob job, int playerX, int playerY, int playerZ, long nowNs) {
		long dx = (job.bx >> 4) - playerX;
		long dy = (job.by >> 4) - playerY;
		long dz = (job.bz >> 4) - playerZ;
		long ring = Math.max(Math.abs(dx), Math.abs(dz));
		// Keep first-load completion spatially coherent.  The former fixed -1024 frustum bonus reduced
		// every visible section inside the normal render radius to priority zero, so server packet order
		// became the effective scheduler and left random 16x16 holes among otherwise distant terrain.
		// A whole horizontal ring now outweighs every view/heading tie-breaker. Vertical distance is kept
		// deliberately small: from a high camera, the ground directly below must finish before a farther
		// section merely because that farther section happens to be closer to eye height.
		long priority = ring * 4096L + (dx * dx + dz * dz) * 16L + Math.abs(dy) * 4L;
		int currentTier = currentSnapshotTier(job, playerX, playerY, playerZ);
		if (currentTier != STREAM_URGENT) {
			float ahead = dx * streamDirectionX + dz * streamDirectionZ;
			if (ahead > 0f) priority = Math.max(0L, priority - Math.min(128L, (long) (ahead * 8f)));
			if (haveMatrix && sectionVisible((float) (job.bx - capCamX), (float) (job.by - capCamY),
					(float) (job.bz - capCamZ))) priority = Math.max(0L, priority - 256L);
		}
		// Aging may move an old section within/into the preceding ring, but cannot flatten the complete
		// active radius into one priority bucket again.
		long ageBonus = Math.min(2048L, Math.max(0L, nowNs - job.enqueuedNs) / 5_000_000L);
		long locality = Math.max(0L, priority - ageBonus);
		return currentTier <= STREAM_URGENT ? currentTier * STREAM_TIER_SCORE + locality
				: STREAM_TIER_SCORE + locality * 4 + currentTier;
	}

	/** Re-evaluate queued work against the camera now, not the order in which server packets arrived. */
	private static int currentSnapshotTier(SnapshotJob job) {
		MinecraftClient client = MinecraftClient.getInstance();
		int playerX = client != null && client.player != null ? client.player.getBlockX() >> 4 : 0;
		int playerY = client != null && client.player != null ? client.player.getBlockY() >> 4 : 0;
		int playerZ = client != null && client.player != null ? client.player.getBlockZ() >> 4 : 0;
		return currentSnapshotTier(job, playerX, playerY, playerZ);
	}

	private static int currentSnapshotTier(SnapshotJob job, int playerX, int playerY, int playerZ) {
		if (job.streamPriority <= STREAM_URGENT) return job.streamPriority;
		int cx = job.bx >> 4, sy = job.by >> 4, cz = job.bz >> 4;
		int tier = job.streamPriority;
		if (Math.abs(cx - playerX) <= 1 && Math.abs(sy - playerY) <= 1
				&& Math.abs(cz - playerZ) <= 1) tier = Math.min(tier, STREAM_SURFACE);
		MinecraftClient client = MinecraftClient.getInstance();
		if (client != null && client.world != null && isSurfaceBandSection(client.world, cx, sy, cz)) {
			tier = Math.min(tier, STREAM_SURFACE);
		}
		if (haveMatrix && sectionVisible((float) (job.bx - capCamX), (float) (job.by - capCamY),
				(float) (job.bz - capCamZ))) {
			tier = Math.min(tier, STREAM_VISIBLE);
		}
		return tier;
	}

	
	private static void updateStreamingDirection(BlockPos playerPos) {
		int x = playerPos.getX(), z = playerPos.getZ();
		if (lastStreamPlayerX == Integer.MIN_VALUE) {
			lastStreamPlayerX = x;
			lastStreamPlayerZ = z;
			return;
		}
		int dx = x - lastStreamPlayerX, dz = z - lastStreamPlayerZ;
		lastStreamPlayerX = x;
		lastStreamPlayerZ = z;
		if (Math.abs(dx) > 64 || Math.abs(dz) > 64) {
			streamDirectionX = streamDirectionZ = 0f;
			streamDirectionIdleFrames = 0;
			return;
		}
		float length = (float) Math.sqrt(dx * dx + dz * dz);
		if (length > 0.001f) {
			float targetX = dx / length, targetZ = dz / length;
			streamDirectionX = streamDirectionX * 0.65f + targetX * 0.35f;
			streamDirectionZ = streamDirectionZ * 0.65f + targetZ * 0.35f;
			float smoothLength = (float) Math.sqrt(streamDirectionX * streamDirectionX + streamDirectionZ * streamDirectionZ);
			if (smoothLength > 0.001f) {
				streamDirectionX /= smoothLength;
				streamDirectionZ /= smoothLength;
			}
			streamDirectionIdleFrames = 0;
		} else if (++streamDirectionIdleFrames > 20) {
			streamDirectionX *= 0.85f;
			streamDirectionZ *= 0.85f;
		}
	}

	private static boolean isMeshPending(long key) {
		return hasPendingSectionMesh(key);
	}

	/** Keep fast camera movement from pinning the bounded producer queue to the old viewpoint. */
	private static void rebaseFirstLoadSnapshots(int playerX, int playerY, int playerZ) {
		int limit = Math.max(1, MoneyakShadersConfig.get().maxPendingSectionSnapshots);
		int target = Math.max(1, limit / 2);
		long protectedKey = ChunkSectionPos.asLong(playerX, playerY, playerZ);
		boolean changed = false;
		while (SNAPSHOT_JOBS.size() > target) {
			long worstKey = Long.MIN_VALUE;
			long worstDistance = Long.MIN_VALUE;
			for (var entry : SNAPSHOT_JOBS.long2ObjectEntrySet()) {
				long key = entry.getLongKey();
				if (key == protectedKey || SECTIONS.containsKey(key)) continue;
				SnapshotJob job = entry.getValue();
				long dx = (job.bx >> 4) - playerX;
				long dy = (job.by >> 4) - playerY;
				long dz = (job.bz >> 4) - playerZ;
				long distance = dx * dx + dz * dz + (dy * dy << 1);
				if (distance > worstDistance) {
					worstDistance = distance;
					worstKey = key;
				}
			}
			if (worstKey == Long.MIN_VALUE) break;
			SnapshotJob removed = SNAPSHOT_JOBS.remove(worstKey);
			if (removed != null) {
				removed.builder.cancel();
				changed = true;
			}
		}
		if (changed) {
			snapshotQueuesDirty = true;
			admissionScanDirty = true;
		}
	}

	private static void cancelSnapshotJob(long key) {
		SnapshotJob job = SNAPSHOT_JOBS.remove(key);
		if (job != null) job.builder.cancel();
		snapshotQueuesDirty = true;
	}

	/**
	 * A neighbouring packet can extend the visible surface envelope after extraction already began.
	 * Preserve copied cells, but move that job ahead of detail work immediately.
	 */
	private static void promoteSnapshotJob(long key, int streamPriority) {
		SnapshotJob job = SNAPSHOT_JOBS.get(key);
		if (job == null || job.streamPriority <= streamPriority) return;
		SNAPSHOT_JOBS.put(key, new SnapshotJob(job.builder, job.bx, job.by, job.bz,
				streamPriority, job.contentRevision, job.snapshotRecorded, job.enqueuedNs));
		snapshotQueuesDirty = true;
	}

	private static void clearSnapshotJobs() {
		EDIT_SNAPSHOT_QUEUE.clear();
		for (SnapshotJob job : SNAPSHOT_JOBS.values()) job.builder.cancel();
		SNAPSHOT_JOBS.clear();
		HIGH_SNAPSHOT_QUEUE.clear();
		STREAM_SNAPSHOT_QUEUE.clear();
		SectionSnapshotBuilder.clearLightSourceCache();
		snapshotQueuesDirty = true;
	}

	private static boolean submitSnapshot(long key, net.minecraft.client.world.ClientWorld world,
			int bx, int by, int bz, int streamPriority) {
		return submitSnapshot(key, world, bx, by, bz, streamPriority, null);
	}

	private static boolean submitSnapshot(long key, net.minecraft.client.world.ClientWorld world,
			int bx, int by, int bz, int streamPriority, net.minecraft.world.chunk.Chunk targetChunk) {
		MinecraftClient viewer = MinecraftClient.getInstance();
		if (LOCAL_BLOCK_EDITS.contains(key) && viewer != null && viewer.player != null
				&& Math.abs((bx >> 4) - (viewer.player.getBlockX() >> 4)) <= 2
				&& Math.abs((by >> 4) - (viewer.player.getBlockY() >> 4)) <= 2
				&& Math.abs((bz >> 4) - (viewer.player.getBlockZ() >> 4)) <= 2) {
			streamPriority = BlockEditLane.PRIORITY;
		}
		// Every render-thread caller already checked this key immediately before submission. Repeating
		// the boxed-map/primitive-set membership test here was the hottest sampled Java method during
		// flight (roughly one fifth of execution samples) because the admission scan invokes it millions
		// of times. The render thread cannot race itself between the guard and this insertion.
		if (!hasSnapshotCapacity(streamPriority)) return false;
		if (!SECTIONS.containsKey(key) && (targetChunk != null
				? targetSectionIsEmpty(targetChunk, by >> 4)
				: targetSectionIsEmpty(world, bx >> 4, by >> 4, bz >> 4))) {
			// An empty target section emits no faces; only its neighbours use it as halo data.  Building
			// a full immutable snapshot for it copied tens of thousands of block/light cells and made
			// the loader appear to work through sky and underground voids before visible terrain.
			// Existing meshes are deliberately excluded: a section that became empty still needs one
			// final mesh/upload to remove its old geometry.
			return false;
		}
		boolean collectLightSources = MoneyakShadersConfig.get().terrainPointLights;
		boolean firstMesh = !SECTIONS.containsKey(key);

		boolean allowProvisionalLight =
				firstMesh
				|| streamPriority < 0
				|| ASYNC_ONLY.contains(key) && contentRevision(key) == 0L;

		long revision = contentRevision(key);
		TerrainEditDebug.snapshot(key, revision, streamPriority, allowProvisionalLight);
		SNAPSHOT_JOBS.put(key, new SnapshotJob(new SectionSnapshotBuilder(
				world, bx, by, bz, collectLightSources, allowProvisionalLight), bx, by, bz,
				streamPriority, revision, false, System.nanoTime()));
		snapshotQueuesDirty = true;
		com.moneyakshaders.client.ChunkLoadTimeline.mark(key,
				com.moneyakshaders.client.ChunkLoadTimeline.PHASE_RECEIVED);
		return true;
	}

	/** Fast render-thread admission check; avoids allocating a snapshot for a section with no blocks. */
	private static boolean targetSectionIsEmpty(net.minecraft.client.world.ClientWorld world,
			int chunkX, int sectionY, int chunkZ) {
		var chunk = world.getChunk(chunkX, chunkZ, net.minecraft.world.chunk.ChunkStatus.FULL, false);
		if (chunk == null) return true;
		return targetSectionIsEmpty(chunk, sectionY);
	}

	private static boolean targetSectionIsEmpty(net.minecraft.world.chunk.Chunk chunk, int sectionY) {
		int index = chunk.getSectionIndex(sectionY << 4);
		var sections = chunk.getSectionArray();
		if (index < 0 || index >= sections.length) return true;
		var section = sections[index];
		if (!section.isEmpty()) return false;
		// Do not trust the transmitted/non-empty counter as the final authority. Bulk server writers
		// (FAWE-style palette replacement, schematic placers and raw Paper section writes) can expose a
		// new palette to the client before the section counter/heightmaps describe it. Treating that
		// transient mismatch as air permanently consumed the admission cursor, so sparse tree tops,
		// signs or even a single block never received a mesh. PalettedContainer#hasAny checks the actual
		// palette and is cheap for the overwhelmingly common single-value air section.
		return !section.hasAny(state -> !state.isAir());
	}

	/** A full snapshot queue has no admission capacity, so scanning more columns this frame is pure cost. */
	private static boolean hasSnapshotCapacity() {
		return hasSnapshotCapacity(STREAM_SURFACE);
	}

	/** Keep bounded producer capacity available for resident edits, then surface work. */
	private static boolean hasSnapshotCapacity(int streamPriority) {
		int limit = Math.max(1, MoneyakShadersConfig.get().maxPendingSectionSnapshots);
		if (streamPriority < 0) {
			int edits = 0;
			for (SnapshotJob job : SNAPSHOT_JOBS.values()) if (job.streamPriority < 0) edits++;
			return edits < BlockEditLane.QUEUED && SNAPSHOT_JOBS.size() < limit + BlockEditLane.QUEUED;
		}
		if (SNAPSHOT_JOBS.size() >= limit) return false;
		if (streamPriority == STREAM_URGENT) return true;
		if (streamPriority == STREAM_SURFACE) {
			return SNAPSHOT_JOBS.size() < Math.max(1, limit - Math.min(URGENT_SNAPSHOT_RESERVE, limit / 4));
		}
		return SNAPSHOT_JOBS.size() < Math.max(1, limit * 3 / 4);
	}

	/**
	 * Rebuild the small admission heaps when the viewer crosses a chunk/section boundary or the active
	 * radius changes. This is the only bounded coordinate scan left in first-load discovery: it checks
	 * each column once, not every vertical section three times on every rendered frame.
	 */
	private static void rebuildAdmissionQueues(net.minecraft.client.world.ClientWorld world,
			int playerChunkX, int playerSectionY, int playerChunkZ) {
		SURFACE_ADMISSION_QUEUE.clear();
		ENVELOPE_ADMISSION_QUEUE.clear();
		DETAIL_ADMISSION_QUEUE.clear();
		COLUMN_ADMISSIONS.clear();

		// Packet events are the normal source. The bounded supplement covers renderer enable/world-entry
		// ordering where a column was already resident before the packet callback began feeding us.
		for (int cx = playerChunkX - effectiveRadiusChunks; cx <= playerChunkX + effectiveRadiusChunks; cx++) {
			for (int cz = playerChunkZ - effectiveRadiusChunks; cz <= playerChunkZ + effectiveRadiusChunks; cz++) {
				if (world.getChunk(cx, cz, net.minecraft.world.chunk.ChunkStatus.FULL, false) != null) {
					KNOWN_ADMISSION_COLUMNS.add(columnKey(cx, cz));
				}
			}
		}

		for (it.unimi.dsi.fastutil.longs.LongIterator it = KNOWN_ADMISSION_COLUMNS.iterator(); it.hasNext(); ) {
			long key = it.nextLong();
			int cx = (int) key;
			int cz = (int) (key >> 32);
			int distance = Math.max(Math.abs(cx - playerChunkX), Math.abs(cz - playerChunkZ));
			if (distance > radiusChunks + 2) {
				it.remove();
				continue;
			}
			var chunk = world.getChunk(cx, cz, net.minecraft.world.chunk.ChunkStatus.FULL, false);
			if (chunk == null) {
				it.remove();
				continue;
			}
			if (distance <= effectiveRadiusChunks) {
				queueAdmissionColumn(world, chunk, cx, cz, playerChunkX, playerSectionY, playerChunkZ);
			}
		}
		admissionScanDirty = false;
	}

	private static boolean shadowTransformDirty(int idx, float half) {
		if (Float.isNaN(shadowRenderSunX[idx]) || Double.isNaN(shadowRenderCamX[idx]) || Float.isNaN(shadowRenderHalf[idx])) return true;
		double texel = 2.0 * half / Math.max(1, shadowRes[idx]);
		double threshold = Math.max(0.0005, texel * 0.5);
		double dx = capCamX - shadowRenderCamX[idx], dy = capCamY - shadowRenderCamY[idx], dz = capCamZ - shadowRenderCamZ[idx];
		if (dx * dx + dy * dy + dz * dz > threshold * threshold) return true;
		float sunThreshold = Math.max(0.00001f, 0.5f / Math.max(1, shadowRes[idx]));
		float sunDelta = Math.abs(sunDirX - shadowRenderSunX[idx]) + Math.abs(sunDirY - shadowRenderSunY[idx]) + Math.abs(sunDirZ - shadowRenderSunZ[idx]);
		return sunDelta > sunThreshold || Math.abs(half - shadowRenderHalf[idx]) > 0.0001f;
	}

	/** Add/replace one received column in the surface-first admission heaps. Render thread only. */
	private static void queueAdmissionColumn(net.minecraft.client.world.ClientWorld world,
			net.minecraft.world.chunk.Chunk chunk, int chunkX, int chunkZ,
			int playerChunkX, int playerSectionY, int playerChunkZ) {
		long key = columnKey(chunkX, chunkZ);
		KNOWN_ADMISSION_COLUMNS.add(key);
		if (Math.max(Math.abs(chunkX - playerChunkX), Math.abs(chunkZ - playerChunkZ))
				> effectiveRadiusChunks) return;

		long range = surfaceRange(world, chunkX, chunkZ, chunk);
		if (range == Long.MIN_VALUE) return;
		long mask = surfaceSectionMask(world, chunkX, chunkZ);
		OrderedSurfaceSections surface = orderedSurfaceSections(world, chunk, range, mask);
		int[] detail = orderedDetailSections(world, chunk, mask, chunkX, chunkZ,
				playerChunkX, playerSectionY, playerChunkZ);
		ColumnAdmission state = new ColumnAdmission(key, chunkX, chunkZ, surface.sections(),
				surface.primaryCount(), detail, ++admissionRevision);
		COLUMN_ADMISSIONS.put(key, state);
		offerColumnAdmission(state, playerChunkX, playerChunkZ);
	}

	/**
	 * Per-column visual order. Highest foliage/water closes the silhouette first, the ocean floor or
	 * ground follows immediately, then cliff/water-column sections descend toward it. A single local
	 * support tail (needed when a tall tree and its ground straddle Y=16 boundaries) is always last.
	 */
	private record OrderedSurfaceSections(int[] sections, int primaryCount) { }

	private static OrderedSurfaceSections orderedSurfaceSections(net.minecraft.client.world.ClientWorld world,
			net.minecraft.world.chunk.Chunk chunk, long range, long mask) {
		int[] ordered = new int[Long.SIZE];
		int count = 0;
		int bottomSection = world.getBottomY() >> 4;
		int highSection = Math.floorDiv(((int) (range >> 32)) - 1, 16);
		int floorSection = Math.floorDiv(((int) range) - 1, 16);
		count = appendSurfaceSection(ordered, count, highSection, bottomSection, mask, chunk);
		count = appendSurfaceSection(ordered, count, floorSection, bottomSection, mask, chunk);
		// A chunk can contain a low river/ravine and high forest ground at once. The single minimum
		// OCEAN_FLOOR section is therefore not enough to close the terrain below the highest foliage.
		// Promote every surface-envelope section containing dry block geometry; water-only layers remain
		// in the lower envelope lane and cannot occupy the reserved top/ground snapshot capacity.
		for (int index = Long.SIZE - 1; index >= 0; index--) {
			if ((mask & (1L << index)) == 0L) continue;
			int sectionY = bottomSection + index;
			if (sectionY < floorSection || !sectionContainsDryGeometry(chunk, sectionY)) continue;
			count = appendSurfaceSection(ordered, count, sectionY, bottomSection, mask, chunk);
		}
		int primaryCount = count;

		// Visible envelope above the floor: high-to-low keeps cliffs, iceberg walls and shallow water
		// coherent while never selecting unrelated underground sections.
		for (int index = Long.SIZE - 1; index >= 0; index--) {
			if ((mask & (1L << index)) == 0L) continue;
			int sectionY = bottomSection + index;
			if (sectionY <= floorSection || sectionY == highSection) continue;
			count = appendSurfaceSection(ordered, count, sectionY, bottomSection, mask, chunk);
		}
		// Only locally requested supports can lie below the chunk-wide minimum floor. Keep those behind
		// all true surface/water work; unlike the old global two-section tail this cannot wake a deep
		// column merely because another (x,z) cell contains a ravine.
		for (int index = Math.min(Long.SIZE - 1, floorSection - bottomSection - 1); index >= 0; index--) {
			if ((mask & (1L << index)) == 0L) continue;
			count = appendSurfaceSection(ordered, count, bottomSection + index, bottomSection, mask, chunk);
		}
		return new OrderedSurfaceSections(java.util.Arrays.copyOf(ordered, count), primaryCount);
	}

	private static boolean sectionContainsDryGeometry(net.minecraft.world.chunk.Chunk chunk, int sectionY) {
		int index = chunk.getSectionIndex(sectionY << 4);
		var sections = chunk.getSectionArray();
		return index >= 0 && index < sections.length && sections[index].hasAny(
				state -> !state.isAir() && state.getFluidState().isEmpty());
	}

	private static int appendSurfaceSection(int[] ordered, int count, int sectionY, int bottomSection,
			long mask, net.minecraft.world.chunk.Chunk chunk) {
		int index = sectionY - bottomSection;
		if (index < 0 || index >= Long.SIZE || (mask & (1L << index)) == 0L
				|| targetSectionIsEmpty(chunk, sectionY)) return count;
		for (int i = 0; i < count; i++) if (ordered[i] == sectionY) return count;
		ordered[count++] = sectionY;
		return count;
	}

	/** Non-surface sections around the viewer, ordered vertically outward and deferred underground. */
	private static int[] orderedDetailSections(net.minecraft.client.world.ClientWorld world,
			net.minecraft.world.chunk.Chunk chunk, long surfaceMask, int chunkX, int chunkZ,
			int playerChunkX, int playerSectionY, int playerChunkZ) {
		int minSection = Math.max(playerSectionY - 40, Math.min(playerSectionY - VERTICAL_SECTIONS, 2));
		int maxSection = playerSectionY + VERTICAL_SECTIONS;
		int[] ordered = new int[Math.max(1, maxSection - minSection + 1)];
		int count = 0;
		int bottomSection = world.getBottomY() >> 4;
		int maxDelta = Math.max(playerSectionY - minSection, maxSection - playerSectionY);
		for (int delta = 0; delta <= maxDelta; delta++) {
			for (int sign = 0; sign < (delta == 0 ? 1 : 2); sign++) {
				int sectionY = sign == 0 ? playerSectionY - delta : playerSectionY + delta;
				if (sectionY < minSection || sectionY > maxSection || targetSectionIsEmpty(chunk, sectionY)) continue;
				int index = sectionY - bottomSection;
				if (index >= 0 && index < Long.SIZE && (surfaceMask & (1L << index)) != 0L) continue;
				if (shouldDeferUnderground(world, chunkX, sectionY, chunkZ,
						playerChunkX, playerSectionY, playerChunkZ)) continue;
				ordered[count++] = sectionY;
			}
		}
		return java.util.Arrays.copyOf(ordered, count);
	}

	private static void offerColumnAdmission(ColumnAdmission state, int playerChunkX, int playerChunkZ) {
		long dx = state.chunkX - playerChunkX;
		long dz = state.chunkZ - playerChunkZ;
		long ring = Math.max(Math.abs(dx), Math.abs(dz));
		int candidateSectionY = state.surfaceCursor < state.surfaceSections.length
				? state.surfaceSections[state.surfaceCursor]
				: state.detailCursor < state.detailSections.length ? state.detailSections[state.detailCursor] : 0;
		boolean inFov = !haveMatrix || sectionVisible((float) ((state.chunkX << 4) - capCamX),
				(float) ((candidateSectionY << 4) - capCamY), (float) ((state.chunkZ << 4) - capCamZ));
		/*
		 * Strict tuple order: nearest Chebyshev ring, then camera-visible columns, then Euclidean
		 * distance inside that ring. The former 1,000,000 ring stride was smaller than the squared
		 * distance term at large render distances, so a farther cardinal column could overtake a
		 * nearer corner. Packet arrival sequence is now only the final deterministic tie-breaker.
		 */
		long basePriority = ring * 1_000_000_000L + (inFov ? 0L : 100_000_000L)
				+ (dx * dx + dz * dz) * 10_000L;
		if (state.surfaceCursor < state.primarySurfaceCount) {
			SURFACE_ADMISSION_QUEUE.add(new ColumnAdmissionEntry(state.key, state.revision,
					basePriority + state.surfaceCursor * 16L, admissionSequence++));
		} else if (state.surfaceCursor < state.surfaceSections.length) {
			ENVELOPE_ADMISSION_QUEUE.add(new ColumnAdmissionEntry(state.key, state.revision,
					basePriority + state.surfaceCursor * 16L, admissionSequence++));
		} else if (state.detailCursor < state.detailSections.length) {
			DETAIL_ADMISSION_QUEUE.add(new ColumnAdmissionEntry(state.key, state.revision,
					basePriority + state.detailCursor * 16L, admissionSequence++));
		}
	}

	/** Reheap pending columns when the horizontal camera direction turns by roughly ten degrees. */
	private static void reprioritizeAdmissionQueuesForCamera(int playerChunkX, int playerChunkZ) {
		float fx = prevFwdX;
		float fz = prevFwdZ;
		float horizontalLength = (float) Math.sqrt(fx * fx + fz * fz);
		if (horizontalLength < 0.15f) return; // Looking nearly straight up/down has no stable XZ order.
		fx /= horizontalLength;
		fz /= horizontalLength;
		if (haveAdmissionPriorityFwd) {
			float dot = fx * admissionPriorityFwdX + fz * admissionPriorityFwdZ;
			if (dot >= 0.9848f) return; // cos(10 degrees)
		}
		admissionPriorityFwdX = fx;
		admissionPriorityFwdZ = fz;
		haveAdmissionPriorityFwd = true;
		if (COLUMN_ADMISSIONS.isEmpty()) return;
		SURFACE_ADMISSION_QUEUE.clear();
		ENVELOPE_ADMISSION_QUEUE.clear();
		DETAIL_ADMISSION_QUEUE.clear();
		for (ColumnAdmission state : COLUMN_ADMISSIONS.values()) {
			offerColumnAdmission(state, playerChunkX, playerChunkZ);
		}
	}

	private static ColumnAdmission pollColumnAdmission(int lane) {
		java.util.PriorityQueue<ColumnAdmissionEntry> queue = lane == 0 ? SURFACE_ADMISSION_QUEUE
				: lane == 1 ? ENVELOPE_ADMISSION_QUEUE : DETAIL_ADMISSION_QUEUE;
		while (!queue.isEmpty()) {
			ColumnAdmissionEntry entry = queue.poll();
			ColumnAdmission state = COLUMN_ADMISSIONS.get(entry.key());
			if (state == null || state.revision != entry.revision()) continue;
			if (lane == 0 ? state.surfaceCursor < state.primarySurfaceCount
					: lane == 1 ? state.surfaceCursor >= state.primarySurfaceCount
							&& state.surfaceCursor < state.surfaceSections.length
						: state.surfaceCursor >= state.surfaceSections.length
							&& state.detailCursor < state.detailSections.length) return state;
		}
		return null;
	}

	/** Drain event-built first-load work. Surface work globally precedes non-surface detail. */
	private static int admitQueuedSections(net.minecraft.client.world.ClientWorld world,
			int playerChunkX, int playerSectionY, int playerChunkZ, int budget) {
		int submitted = 0;
		int attempts = 0;
		while (budget > 0 && attempts++ < SUBMIT_SCRATCH) {
			int lane = 0;
			ColumnAdmission state = pollColumnAdmission(0);
			ColumnAdmission envelope = pollColumnAdmission(1);
			if (envelope != null && (state == null || StreamingOrder.locality(envelope.chunkX, envelope.chunkZ,
					playerChunkX, playerChunkZ) < StreamingOrder.locality(state.chunkX, state.chunkZ, playerChunkX, playerChunkZ))) {
				if (state != null) offerColumnAdmission(state, playerChunkX, playerChunkZ);
				state = envelope;
				lane = 1;
			} else if (envelope != null) offerColumnAdmission(envelope, playerChunkX, playerChunkZ);
			if (state == null) {
				lane = 2;
				state = pollColumnAdmission(2);
			}
			if (state == null) break;
			int lanePriority = lane < 2 ? STREAM_SURFACE : STREAM_BACKGROUND;
			if (!hasSnapshotCapacity(lanePriority)) {
				offerColumnAdmission(state, playerChunkX, playerChunkZ);
				break;
			}

			boolean surfaceGeometry = lane < 2;
			// Do not advance this cursor until the section is either already satisfied or its immutable
			// snapshot was actually accepted. submitSnapshot can temporarily reject work while a real block
			// edit waits for light propagation or a producer lane is full. Consuming the cursor first silently
			// skipped that surface section and let buried detail overtake it until a later generic remesh.
			int sectionY = surfaceGeometry ? state.surfaceSections[state.surfaceCursor]
					: state.detailSections[state.detailCursor];
			var chunk = world.getChunk(state.chunkX, state.chunkZ,
					net.minecraft.world.chunk.ChunkStatus.FULL, false);
			if (chunk == null) {
				COLUMN_ADMISSIONS.remove(state.key);
				KNOWN_ADMISSION_COLUMNS.remove(state.key);
				continue;
			}
			long sectionKey = ChunkSectionPos.asLong(state.chunkX, sectionY, state.chunkZ);
			if (surfaceGeometry) {
				// The same section may have entered as ordinary detail before a neighbouring
				// heightmap revealed its cliff/water exposure. Do not leave its partially copied
				// snapshot behind the background lane for tens of seconds.
				promoteSnapshotJob(sectionKey, STREAM_SURFACE);
			}
			boolean consumed = SECTIONS.containsKey(sectionKey) || isMeshPending(sectionKey)
					|| targetSectionIsEmpty(chunk, sectionY)
					|| !surfaceGeometry && shouldDeferUnderground(world, state.chunkX, sectionY, state.chunkZ,
							playerChunkX, playerSectionY, playerChunkZ);
			if (!consumed) {
				boolean inFov = !haveMatrix || sectionVisible(
						(float) ((state.chunkX << 4) - capCamX),
						(float) ((sectionY << 4) - capCamY),
						(float) ((state.chunkZ << 4) - capCamZ));
				// Both primary tops and the surface envelope are foreground geometry. Downgrading lane 1
				// after admission filled the 96-slot background cap with forest ground/support sections,
				// especially directly below or behind a high camera, and produced permanent white squares.
				int streamPriority = lane < 2 ? STREAM_SURFACE : (inFov ? STREAM_VISIBLE : STREAM_BACKGROUND);
				if (submitSnapshot(sectionKey, world, state.chunkX << 4, sectionY << 4,
						state.chunkZ << 4, streamPriority, chunk)) {
					consumed = true;
					budget--;
					submitted++;
				} else {
					// Temporary refusal: retain this exact lane head and retry next frame. Requeue once, then
					// stop this drain so the same blocked column cannot spin for SUBMIT_SCRATCH attempts.
					offerColumnAdmission(state, playerChunkX, playerChunkZ);
					break;
				}
			}
			if (consumed) {
				if (surfaceGeometry) state.surfaceCursor++;
				else state.detailCursor++;
			}
			offerColumnAdmission(state, playerChunkX, playerChunkZ);
		}
		return submitted;
	}

	private static long columnKey(int chunkX, int chunkZ) {
		return (chunkX & 0xFFFF_FFFFL) | ((long) chunkZ << 32);
	}

	/** Packed low OCEAN_FLOOR / high MOTION_BLOCKING_NO_LEAVES height for one received column. */
	private static long surfaceRange(net.minecraft.client.world.ClientWorld world, int chunkX, int chunkZ) {
		return surfaceRange(world, chunkX, chunkZ, null);
	}

	private static long surfaceRange(net.minecraft.client.world.ClientWorld world, int chunkX, int chunkZ,
			net.minecraft.world.chunk.Chunk knownChunk) {
		if (world != surfaceRangeWorld) {
			SURFACE_RANGE_CACHE.clear();
			SURFACE_SECTION_MASK_CACHE.clear();
			surfaceRangeWorld = world;
		}
		long key = columnKey(chunkX, chunkZ);
		long cached = SURFACE_RANGE_CACHE.getOrDefault(key, Long.MIN_VALUE);
		if (cached != Long.MIN_VALUE) return cached;
		var chunk = knownChunk != null ? knownChunk
				: world.getChunk(chunkX, chunkZ, net.minecraft.world.chunk.ChunkStatus.FULL, false);
		if (chunk == null) return Long.MIN_VALUE;
		int low = Integer.MAX_VALUE;
		int high = Integer.MIN_VALUE;
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				int visibleTop = chunk.sampleHeightmap(
						net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
				// Water and ice are not an opaque roof. OCEAN_FLOOR keeps the sea bed and flooded
				// ravines in the surface-priority band instead of treating the waterline as solid terrain.
				int opaqueFloor = chunk.sampleHeightmap(net.minecraft.world.Heightmap.Type.OCEAN_FLOOR, x, z);
				low = Math.min(low, opaqueFloor);
				high = Math.max(high, visibleTop);
			}
		}
		long packed = (low & 0xFFFF_FFFFL) | ((long) high << 32);
		SURFACE_RANGE_CACHE.put(key, packed);
		return packed;
	}

	/**
	 * Non-empty sections which can contribute to the visible exterior of this column. The lower bound
	 * must include adjacent terrain: a mountain chunk may have every local heightmap cell at Y=140,
	 * while its boundary wall is exposed down to a neighbouring valley at Y=60. An own-column-only
	 * range classifies that entire cliff as underground and renders white 16x16 holes until the player
	 * descends. One loaded-neighbour ring is sufficient because a block face can only be exposed across
	 * its immediate chunk boundary. Two support sections cover exact section boundaries and overhangs.
	 */
	private static long surfaceSectionMask(net.minecraft.client.world.ClientWorld world, int chunkX, int chunkZ) {
		long key = columnKey(chunkX, chunkZ);
		if (SURFACE_SECTION_MASK_CACHE.containsKey(key)) {
			return SURFACE_SECTION_MASK_CACHE.get(key);
		}
		long ownRange = surfaceRange(world, chunkX, chunkZ);
		var chunk = world.getChunk(chunkX, chunkZ, net.minecraft.world.chunk.ChunkStatus.FULL, false);
		if (ownRange == Long.MIN_VALUE || chunk == null) return 0L;
		int exposedFloor = (int) ownRange;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (dx == 0 && dz == 0) continue;
				long neighbourRange = surfaceRange(world, chunkX + dx, chunkZ + dz);
				if (neighbourRange != Long.MIN_VALUE) exposedFloor = Math.min(exposedFloor, (int) neighbourRange);
			}
		}
		int high = (int) (ownRange >> 32);
		int firstSection = chunk.getSectionIndex(exposedFloor - 1);
		int lastSection = chunk.getSectionIndex(high - 1);
		int bottomSection = world.getBottomY() >> 4;
		long mask = 0L;
		for (int sectionIndex = Math.max(0, firstSection - 2);
				sectionIndex <= lastSection && sectionIndex < Long.SIZE; sectionIndex++) {
			if (!targetSectionIsEmpty(chunk, bottomSection + sectionIndex)) mask |= 1L << sectionIndex;
		}
		// Heightmaps are scheduling hints, not an authoritative inventory of received geometry. A
		// server can send a full section palette produced by an async schematic/tree writer while its
		// MOTION_BLOCKING heightmap still describes the pre-edit terrain. Every actual non-air section
		// above that reported ceiling is exterior-capable by definition and must enter the surface lane;
		// otherwise high tree crowns and floating/sparse custom structures are silently classified as
		// empty underground detail and can remain absent forever at a high camera altitude.
		int sectionCount = Math.min(chunk.getSectionArray().length, Long.SIZE);
		for (int sectionIndex = Math.max(0, lastSection + 1); sectionIndex < sectionCount; sectionIndex++) {
			if (!targetSectionIsEmpty(chunk, bottomSection + sectionIndex)) mask |= 1L << sectionIndex;
		}
		SURFACE_SECTION_MASK_CACHE.put(key, mask);
		return mask;
	}

	/**
	 * Water/ice is not an opaque roof. Sections touched by a local ocean-floor-to-visible-top interval
	 * can contain a sea bed, flooded ravine or tall iceberg wall visible from above and must survive a
	 * high-altitude camera after leaving the ordinary +/-6-section eye band.
	 */
	private static boolean isSurfaceBandSection(net.minecraft.client.world.ClientWorld world,
			int chunkX, int sectionY, int chunkZ) {
		int index = sectionY - (world.getBottomY() >> 4);
		return index >= 0 && index < Long.SIZE
				&& (surfaceSectionMask(world, chunkX, chunkZ) & (1L << index)) != 0L;
	}

	/** Ordinary eye band plus retained water/surface sections below it. Render thread only. */
	private static boolean inActiveRange(net.minecraft.client.world.ClientWorld world,
			int chunkX, int sectionY, int chunkZ, int playerSectionY) {
		if (inVerticalRange(sectionY, playerSectionY)) return true;
		if (sectionY > playerSectionY) return false;
		Sec resident = SECTIONS.get(ChunkSectionPos.asLong(chunkX, sectionY, chunkZ));
		return isSurfaceBandSection(world, chunkX, sectionY, chunkZ)
				|| (resident != null && resident.wcount > 0);
	}

	private static int neighborhoodSurfaceFloor(net.minecraft.client.world.ClientWorld world, int chunkX, int chunkZ) {
		long key = columnKey(chunkX, chunkZ);
		int cached = NEIGHBOR_SURFACE_FRAME_CACHE.getOrDefault(key, Integer.MAX_VALUE);
		if (cached != Integer.MAX_VALUE) return cached;
		int floor = Integer.MAX_VALUE;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				long range = surfaceRange(world, chunkX + dx, chunkZ + dz);
				if (range != Long.MIN_VALUE) floor = Math.min(floor, (int) range);
			}
		}
		int result = floor == Integer.MAX_VALUE ? Integer.MIN_VALUE : floor;
		NEIGHBOR_SURFACE_FRAME_CACHE.put(key, result);
		return result;
	}

	/** Classify a first mesh by what it can reveal, not merely by its 3-D distance to the player. */
	private static int streamPriorityForSection(net.minecraft.client.world.ClientWorld world,
			net.minecraft.world.chunk.Chunk column,
			int chunkX, int sectionY, int chunkZ, boolean inFov, int playerX, int playerY, int playerZ) {
		if (Math.abs(chunkX - playerX) <= 1 && Math.abs(sectionY - playerY) <= 1
				&& Math.abs(chunkZ - playerZ) <= 1) return STREAM_SURFACE;
		long ownRange = surfaceRange(world, chunkX, chunkZ, column);
		if (ownRange == Long.MIN_VALUE) return inFov ? STREAM_VISIBLE : STREAM_BACKGROUND;
		if (isSurfaceBandSection(world, chunkX, sectionY, chunkZ)) return STREAM_SURFACE;
		int ownCeiling = (int) (ownRange >> 32);
		int neighbourhoodFloor = neighborhoodSurfaceFloor(world, chunkX, chunkZ);
		int sectionBottom = sectionY << 4;
		int sectionTop = (sectionY + 1) << 4;
		// This band contains top faces, ravine walls and cliff skirts down to the lowest adjacent terrain.
		if (sectionTop >= neighbourhoodFloor - 16 && sectionBottom <= ownCeiling + 16) {
			return STREAM_SURFACE;
		}
		return inFov ? STREAM_VISIBLE : STREAM_BACKGROUND;
	}

	/**
	 * Capture a blockstate change: vanilla flags this section for re-render
	 * (block placed/broken, etc.). Just enqueue it (thread-safe) - the render
	 * thread drops our cached mesh in updateSections, which then re-meshes it
	 * (budgeted), keeping our terrain live on edits.
	 */
	public static void markDirty(int sectionX, int sectionY, int sectionZ) {
		// A normal dirty mark can be a placed/broken lamp.  Drop only that source-section index;
		// lighting propagation uses markDirtyAsync and keeps the hot source cache intact.
		SectionSnapshotBuilder.invalidateLightSourceSection(sectionX, sectionY, sectionZ);
		// Coalesced render-thread invalidation: bulk server edits can change both heightmaps and which
		// formerly-empty sections belong to the visible surface without flooding the admission queues.
		RECEIVED_COLUMN_REMESH.offer(ChunkSectionPos.asLong(sectionX, 2, sectionZ));
		long key = ChunkSectionPos.asLong(sectionX, sectionY, sectionZ);
		ASYNC_ONLY.remove(key); // a real block edit promotes any coalesced light refresh to urgent
		markContentDirty(key);
	}

	/**
	 * Compatibility invalidation for vanilla's render scheduling callbacks. Multiplayer bulk-section
	 * packets do not necessarily invoke {@code WorldRenderer.updateBlock} once per changed block; they
	 * may only schedule the affected section/region. With vanilla builds skipped, ignoring that signal
	 * left an already resident custom mesh permanently stale. Unknown first-load sections are ignored
	 * because their admission snapshot already reads the received chunk state.
	 */
	public static void markScheduledDirty(int sectionX, int sectionY, int sectionZ) {
		long key = ChunkSectionPos.asLong(sectionX, sectionY, sectionZ);
		if (!SECTIONS.containsKey(key) && !hasPendingSectionMesh(key)) return;
		markDirty(sectionX, sectionY, sectionZ);
	}

	/**
	 * Canonical live block edit. Every section whose one-cell geometry halo reads the changed block is
	 * invalidated, so an edit on x/y/z=0 or 15 also updates the face stored by its neighbour. Unlike
	 * general vanilla render scheduling, this method is reached only for an actual old/new block state.
	 */
	public static void markBlockDirty(BlockPos pos, BlockState oldState, BlockState newState) {
		com.moneyakshaders.client.DebugStats.blockUpdates.incrementAndGet();
		beginBlockEditBurst();

		int x = pos.getX(), y = pos.getY(), z = pos.getZ();
		int sx = x >> 4, sy = y >> 4, sz = z >> 4;
		SectionSnapshotBuilder.invalidateLightSourceSection(sx, sy, sz);
		RECEIVED_COLUMN_REMESH.offer(ChunkSectionPos.asLong(sx, 2, sz));
		markGeometryHaloDirty(x, y, z, sx, sy, sz, true);

		long primaryKey = ChunkSectionPos.asLong(sx, sy, sz);
		TerrainEditDebug.block(primaryKey, x, y, z, contentRevision(primaryKey));

		boolean removal = oldState != null && newState != null && !oldState.isAir() && newState.isAir();
		boolean placement = oldState != null && newState != null && oldState.isAir() && !newState.isAir();
		if (!bulkBlockEditBurst && (removal || placement)) {
			int minSx = (x & 15) == 0 ? sx - 1 : sx;
			int maxSx = (x & 15) == 15 ? sx + 1 : sx;
			int minSy = (y & 15) == 0 ? sy - 1 : sy;
			int maxSy = (y & 15) == 15 ? sy + 1 : sy;
			int minSz = (z & 15) == 0 ? sz - 1 : sz;
			int maxSz = (z & 15) == 15 ? sz + 1 : sz;
			for (int sectionX = minSx; sectionX <= maxSx; sectionX++)
				for (int sectionY = minSy; sectionY <= maxSy; sectionY++)
					for (int sectionZ = minSz; sectionZ <= maxSz; sectionZ++) {
						long neighbourKey = ChunkSectionPos.asLong(sectionX, sectionY, sectionZ);
						Sec neighbour = SECTIONS.get(neighbourKey);
						if (neighbourKey == primaryKey || neighbour == null || neighbour.residency != Sec.Residency.ACTIVE) continue;
						if (removal) addUploadDependency(primaryKey, neighbourKey);
						else addUploadDependency(neighbourKey, primaryKey);
					}
		}

		if (BLOCK_EDIT_LIGHT_PENDING.add(primaryKey)) {
			com.moneyakshaders.client.ClientLightDispatcher.noteBlockEditLightChange(primaryKey);
			BLOCK_EDIT_RELIGHT_SOURCES.offer(primaryKey);
		}
	}

	/** Render thread: publish edit relights only after vanilla's block-light propagation settled. */
	private static void drainBlockEditRelights(long nowMs) {
		BLOCK_EDIT_RELIGHT_SOURCES.drainTo(BLOCK_EDIT_RELIGHT_SOURCE_SCRATCH, 0);
		if (!BLOCK_EDIT_RELIGHT_SOURCE_SCRATCH.isEmpty()) {
			long targetLightPass = COMPLETED_LIGHT_PASSES.get() + 1L;
			long fallbackDueMs = nowMs + BLOCK_EDIT_RELIGHT_FALLBACK_MS;
			for (int sourceIndex = 0; sourceIndex < BLOCK_EDIT_RELIGHT_SOURCE_SCRATCH.size(); sourceIndex++) {
				long sourceKey = BLOCK_EDIT_RELIGHT_SOURCE_SCRATCH.getLong(sourceIndex);
				BLOCK_EDIT_LIGHT_PENDING.remove(sourceKey);
				int sx = ChunkSectionPos.unpackX(sourceKey);
				int sy = ChunkSectionPos.unpackY(sourceKey);
				int sz = ChunkSectionPos.unpackZ(sourceKey);
				for (int dx = -1; dx <= 1; dx++) {
					for (int dy = -1; dy <= 1; dy++) {
						for (int dz = -1; dz <= 1; dz++) {
							long consumerKey = ChunkSectionPos.asLong(sx + dx, sy + dy, sz + dz);
							BlockEditRelight wait = BLOCK_EDIT_RELIGHT_DUE_MS.get(consumerKey);
							if (wait == null) {
								BLOCK_EDIT_RELIGHT_DUE_MS.put(consumerKey,
										new BlockEditRelight(targetLightPass, fallbackDueMs));
							} else {
								wait.targetLightPass = Math.max(wait.targetLightPass, targetLightPass);
								wait.fallbackDueMs = Math.max(wait.fallbackDueMs, fallbackDueMs);
							}
						}
					}
				}
			}
		}
		long completedPass = COMPLETED_LIGHT_PASSES.get();
		var iterator = BLOCK_EDIT_RELIGHT_DUE_MS.long2ObjectEntrySet().fastIterator();
		while (iterator.hasNext()) {
			var entry = iterator.next();
			BlockEditRelight wait = entry.getValue();
			if (completedPass < wait.targetLightPass && nowMs < wait.fallbackDueMs) continue;
			long key = entry.getLongKey();
			iterator.remove();
			Sec resident = SECTIONS.get(key);
			if ((resident != null && resident.residency == Sec.Residency.ACTIVE)
					|| hasPendingSectionMesh(key)) {
				com.moneyakshaders.client.DebugStats.remeshBlockEditRelight.incrementAndGet();
				markDirtyAsync(ChunkSectionPos.unpackX(key), ChunkSectionPos.unpackY(key),
						ChunkSectionPos.unpackZ(key));
			}
		}
	}

	/** Render thread: promote provisional first meshes only after their local light halo is stable. */
	private static void drainProvisionalRelights(long nowMs) {
		if (PROVISIONAL_RELIGHTS.isEmpty()) return;

		drainPlayerProvisionalRelights(nowMs);

		var iterator = PROVISIONAL_RELIGHTS.long2ObjectEntrySet().fastIterator();

		while (iterator.hasNext()) {
			var entry = iterator.next();

			long key = entry.getLongKey();
			Sec resident = SECTIONS.get(key);

			if (resident == null || resident.residency != Sec.Residency.ACTIVE) {
				iterator.remove();
				continue;
			}

			ProvisionalRelight pending = entry.getValue();

			long revision =
					com.moneyakshaders.client.ClientLightDispatcher.renderLightRevisionSignature(
							resident.cx, resident.sy, resident.cz);

			if (revision != pending.revision) {
				pending.revision = revision;
				pending.quietSinceMs = nowMs;
			}

			iterator.remove();
			PROVISIONAL_RETRY_REVISIONS.put(key, revision);

			com.moneyakshaders.client.DebugStats
					.remeshProvisionalRelight
					.incrementAndGet();

			markDirtyAsync(
					resident.cx,
					resident.sy,
					resident.cz);
		}
	}
	/** Player-room correctness bypasses the cosmetic second-pass gate. Nearby provisional meshes are
	 * already visible and must not wait behind the complete surface stream before receiving final light. */
	private static int drainPlayerProvisionalRelights(long nowMs) {
		MinecraftClient client = MinecraftClient.getInstance();

		if (client == null || client.player == null) return 0;

		int pcx = client.player.getBlockX() >> 4;
		int psy = client.player.getBlockY() >> 4;
		int pcz = client.player.getBlockZ() >> 4;

		int submitted = 0;

		for (int dy : PROVISIONAL_NEAR_ORDER)
			for (int dx : PROVISIONAL_NEAR_ORDER)
				for (int dz : PROVISIONAL_NEAR_ORDER) {

					if (submitted >= PROVISIONAL_URGENT_SUBMITS_PER_FRAME)
						return submitted;

					long key =
							ChunkSectionPos.asLong(
									pcx + dx,
									psy + dy,
									pcz + dz);

					ProvisionalRelight pending =
							PROVISIONAL_RELIGHTS.get(key);

					Sec resident =
							SECTIONS.get(key);

					if (pending == null
							|| resident == null
							|| resident.residency != Sec.Residency.ACTIVE) {
						continue;
					}

					long revision =
							com.moneyakshaders.client.ClientLightDispatcher
									.renderLightRevisionSignature(
											resident.cx,
											resident.sy,
											resident.cz);

					if (revision != pending.revision) {
						pending.revision = revision;
						pending.quietSinceMs = nowMs;
						continue;
					}

					PROVISIONAL_RELIGHTS.remove(key);
					PROVISIONAL_RETRY_REVISIONS.put(key, revision);

					com.moneyakshaders.client.DebugStats
							.remeshProvisionalRelight
							.incrementAndGet();

					markDirtyAsync(
							resident.cx,
							resident.sy,
							resident.cz);

					submitted++;
				}

		return submitted;
	}
	/** True while immutable foreground inputs still represent sections with no live GPU mesh. */
	private static boolean hasForegroundFirstLoadSnapshots() {
		if (admissionScanDirty || !SURFACE_ADMISSION_QUEUE.isEmpty()
				|| !ENVELOPE_ADMISSION_QUEUE.isEmpty()) {
			return true;
		}
		for (var entry : SNAPSHOT_JOBS.long2ObjectEntrySet()) {
			SnapshotJob job = entry.getValue();
			if (job.streamPriority <= STREAM_SURFACE && !SECTIONS.containsKey(entry.getLongKey())) {
				return true;
			}
		}
		for (ChunkMeshExecutor.MeshResult result : UPLOAD_BACKLOG) {
			if (result.streamPriority() <= STREAM_SURFACE && !SECTIONS.containsKey(result.key())) {
				return true;
			}
		}
		return false;
	}

	/** Called by the WorldRenderer light-pass redirect immediately after vanilla finished propagation. */
	public static void onClientLightPassCompleted() {
		COMPLETED_LIGHT_PASSES.incrementAndGet();
	}

	/**
	 * Marks a fluid block and every section whose one-cell geometry halo reads that block. A boundary
	 * change therefore cannot leave the neighbouring section's water face stale. The render thread
	 * coalesces the packet burst through DIRTY and immediately rebuilds a bounded number of resident
	 * fluid sections; overflow remains on the normal async replacement path.
	 */
	public static void markFluidDirty(BlockPos pos, BlockState oldState, BlockState newState) {
		if (oldState.getFluidState().isEmpty() && newState.getFluidState().isEmpty()) return;
		int x = pos.getX(), y = pos.getY(), z = pos.getZ();
		int sx = x >> 4, sy = y >> 4, sz = z >> 4;
		SectionSnapshotBuilder.invalidateLightSourceSection(sx, sy, sz);
		markGeometryHaloDirty(x, y, z, sx, sy, sz, false);
	}

	private static void markGeometryHaloDirty(int x, int y, int z, int sx, int sy, int sz, boolean blockChange) {
		int minSx = (x & 15) == 0 ? sx - 1 : sx;
		int maxSx = (x & 15) == 15 ? sx + 1 : sx;
		int minSy = (y & 15) == 0 ? sy - 1 : sy;
		int maxSy = (y & 15) == 15 ? sy + 1 : sy;
		int minSz = (z & 15) == 0 ? sz - 1 : sz;
		int maxSz = (z & 15) == 15 ? sz + 1 : sz;
		for (int sectionX = minSx; sectionX <= maxSx; sectionX++)
			for (int sectionY = minSy; sectionY <= maxSy; sectionY++)
				for (int sectionZ = minSz; sectionZ <= maxSz; sectionZ++) {
					long key = ChunkSectionPos.asLong(sectionX, sectionY, sectionZ);
					if (blockChange) {
						BLOCK_EDIT_BURST_SECTIONS.add(key);
						if (SECTIONS.containsKey(key)) LOCAL_BLOCK_EDITS.add(key);
						if (bulkBlockEditBurst) clearUploadDependencies(key);
					}
					ASYNC_ONLY.remove(key);
					markContentDirty(key);
				}
	}

	private static void markContentDirty(long key) {
		Sec resident = SECTIONS.get(key);
		if (resident != null && resident.residency != Sec.Residency.ACTIVE) resident.sleepingLightDirty = true;
		if (CONTENT_DIRTY_PENDING.add(key)) {
			long revision = CONTENT_REVISIONS.merge(key, 1L, Long::sum);
			TerrainEditDebug.dirty(key, revision);
		}
		DIRTY.offer(key);
	}

	private static void beginBlockEditBurst() {
		int count = BLOCK_EDIT_BURST_COUNT.incrementAndGet();
		if (count != BULK_EDIT_DEPENDENCY_THRESHOLD || bulkBlockEditBurst) return;
		bulkBlockEditBurst = true;
		for (Long key : BLOCK_EDIT_BURST_SECTIONS) clearUploadDependencies(key.longValue());
	}

	private static void clearUploadDependencies(long key) {
		UPLOAD_DEPENDENCIES.remove(key);
		UPLOAD_DEPENDENCIES.published(key);
	}

	private static void finishBlockEditBurst() {
		BLOCK_EDIT_BURST_COUNT.set(0);
		bulkBlockEditBurst = false;
		BLOCK_EDIT_BURST_SECTIONS.clear();
	}

	private static long contentRevision(long key) {
		Long revision = CONTENT_REVISIONS.get(key);
		return revision == null ? 0L : revision.longValue();
	}

	/**
	 * Light-driven dirty: the section only needs its BAKED LIGHT refreshed, not instant edit feedback.
	 * These never take the synchronous render-thread mesh path (that's what made chunk-load light
	 * bursts spike frames) — they re-mesh asynchronously only.
	 */
	public static void markDirtyAsync(int sectionX, int sectionY, int sectionZ) {
		long key = ChunkSectionPos.asLong(sectionX, sectionY, sectionZ);
		// A provisional resident already records that this local halo still moves. Coalesce further
		// provider notifications there instead of recreating the strict 96-slot snapshot deadlock.
		ProvisionalRelight provisional = PROVISIONAL_RELIGHTS.get(key);
		if (provisional != null) {
			long revision = com.moneyakshaders.client.ClientLightDispatcher.renderLightRevisionSignature(
					sectionX, sectionY, sectionZ);
			if (revision != provisional.revision) {
				provisional.revision = revision;
				provisional.quietSinceMs = System.currentTimeMillis();
			}
			return;
		}
		ASYNC_ONLY.add(key);
		markLightDirty(key);
	}

	private static void markLightDirty(long key) {
		Sec resident = SECTIONS.get(key);
		if (resident != null && resident.residency != Sec.Residency.ACTIVE) resident.sleepingLightDirty = true;
		// Geometry revisions must not advance on a provider-only light update. The snapshot builder
		// and upload validation already check the independent local render-light signature.
		DIRTY.offer(key);
	}

	/**
	 * A provider notification identifies one LIGHT section. Terrain vertices sample block/sky light
	 * through a one-block neighbourhood, so every adjacent TERRAIN section can consume that data.
	 * Refreshing only the identically keyed mesh produced hard dark cuts at section/chunk boundaries;
	 * underwater it commonly refreshed the water/light section while leaving the visible sea floor stale.
	 * The queue is deduplicating and the urgent lane is bounded, so this conservative 3x3x3 fan-out does
	 * not multiply actual mesh submissions during propagation bursts.
	 */
	public static void markLightDirtyAsync(int lightSectionX, int lightSectionY, int lightSectionZ) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -1; dz <= 1; dz++) {
					int sx = lightSectionX + dx, sy = lightSectionY + dy, sz = lightSectionZ + dz;
					long consumerKey = ChunkSectionPos.asLong(sx, sy, sz);
					Sec resident = SECTIONS.get(consumerKey);
					if (resident != null && resident.residency != Sec.Residency.ACTIVE) {
						// Preserve every notification even for retained underground geometry. It will rebuild
						// before its first active frame instead of exposing a stale light boundary on wake.
						resident.sleepingLightDirty = true;
					}
					if (resident != null && resident.residency == Sec.Residency.ACTIVE
							|| hasPendingSectionMesh(consumerKey)) {
						com.moneyakshaders.client.DebugStats.remeshLightFanout.incrementAndGet();
						markDirtyAsync(sx, sy, sz);
					}
				}
			}
		}
	}

	/** Whether any terrain mesh in the 3x3x3 sampling neighbourhood consumes this light section. */
	public static boolean hasResidentLightConsumer(long lightSectionKey) {
		int lx = ChunkSectionPos.unpackX(lightSectionKey);
		int ly = ChunkSectionPos.unpackY(lightSectionKey);
		int lz = ChunkSectionPos.unpackZ(lightSectionKey);
		for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
			if (SECTIONS.containsKey(ChunkSectionPos.asLong(lx + dx, ly + dy, lz + dz))) return true;
		}
		return false;
	}

	/** Whether an in-flight first/replacement mesh can consume this light section. */
	public static boolean hasPendingLightConsumer(long lightSectionKey) {
		int lx = ChunkSectionPos.unpackX(lightSectionKey);
		int ly = ChunkSectionPos.unpackY(lightSectionKey);
		int lz = ChunkSectionPos.unpackZ(lightSectionKey);
		for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
			if (hasPendingSectionMesh(ChunkSectionPos.asLong(lx + dx, ly + dy, lz + dz))) return true;
		}
		return false;
	}

	/**
	 * Current render importance of one provider light section. Light data fans out into a 3x3x3
	 * terrain halo, so the best active consumer determines its lane: on-screen first, retained surface
	 * second, and hidden/background last. This is intentionally camera-current; packet order must never
	 * decide which server light levels become visually coherent first after a teleport.
	 */
	public static int lightSectionPriorityTier(long lightSectionKey) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.world == null || client.player == null) return 2;
		int lx = ChunkSectionPos.unpackX(lightSectionKey);
		int ly = ChunkSectionPos.unpackY(lightSectionKey);
		int lz = ChunkSectionPos.unpackZ(lightSectionKey);
		boolean surface = false;
		for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
			Sec consumer = SECTIONS.get(ChunkSectionPos.asLong(lx + dx, ly + dy, lz + dz));
			if (consumer == null || consumer.residency != Sec.Residency.ACTIVE) continue;
			float relX = (float) (consumer.bx - capCamX);
			float relY = (float) (consumer.by - capCamY);
			float relZ = (float) (consumer.bz - capCamZ);
			boolean near = Math.abs(relX + 8f) < 24f && Math.abs(relY + 8f) < 24f
					&& Math.abs(relZ + 8f) < 24f;
			if (near || !haveMatrix || sectionVisible(relX, relY, relZ)) return 0;
			if (isSurfaceBandSection(client.world, consumer.cx, consumer.sy, consumer.cz)) surface = true;
		}
		return surface ? 1 : 2;
	}

	/**
	 * Returns true when a section is beneath closed surface terrain while the player is above ground.
	 * It is a scheduling decision only: no server chunk is discarded and spectator/cave players bypass
	 * it, so the section becomes eligible as soon as it can matter to the current view.
	 */
	private static boolean shouldDeferUnderground(net.minecraft.client.world.ClientWorld world,
			int cx, int sy, int cz, int playerCx, int playerSy, int playerCz) {
		MoneyakShadersConfig cfg = MoneyakShadersConfig.get();
		int radius = Math.max(1, cfg.undergroundActiveRadiusSections);
		MinecraftClient client = MinecraftClient.getInstance();
		if (!cfg.deferUndergroundSections || client == null || client.player == null
				|| client.player.isSpectator()
				|| (Math.abs(cx - playerCx) <= radius && Math.abs(sy - playerSy) <= radius
						&& Math.abs(cz - playerCz) <= radius)) {
			return false;
		}
		if (!playerAtOrAboveSurface(world, client, playerCx, playerCz)) {
			return false; // any terrain cover means a cave/mine may be visible: never defer its geometry
		}
		// Above ground, the per-column mask is the complete admission contract: local top/floor
		// intervals preserve cliffs, ravines and water, plus one shallow support for tree/section
		// boundaries. Everything else is closed underground data and must stay on-demand.
		return !isSurfaceBandSection(world, cx, sy, cz);
	}

	/** One chunk/heightmap lookup per exact player block position instead of once per candidate section. */
	private static boolean playerAtOrAboveSurface(net.minecraft.client.world.ClientWorld world,
			MinecraftClient client, int playerCx, int playerCz) {
		int x = client.player.getBlockX();
		int y = client.player.getBlockY();
		int z = client.player.getBlockZ();
		// High flight must not be classified as a cave merely because a giant/custom trunk raises the
		// NO_LEAVES heightmap above the camera. This also makes teleport admission deterministic before
		// the player column arrives. Ordinary near-surface/cave positions still use the exact heightmap.
		if (y >= world.getSeaLevel() + 64) {
			playerSurfaceWorld = world;
			playerSurfaceX = x;
			playerSurfaceY = y;
			playerSurfaceZ = z;
			playerAtOrAboveSurface = true;
			return true;
		}
		if (world == playerSurfaceWorld && x == playerSurfaceX && y == playerSurfaceY && z == playerSurfaceZ) {
			return playerAtOrAboveSurface;
		}
		var playerChunk = world.getChunk(playerCx, playerCz, net.minecraft.world.chunk.ChunkStatus.FULL, false);
		if (playerChunk == null) {
			// A teleport can receive surrounding columns before the one under the player. Never cache
			// that transient absence as "underground" for the unchanged position: it previously opened
			// the whole detail scan until the player moved. High flight is safe to classify immediately;
			// near terrain we stay conservative and retry on the next candidate/frame.
			return y >= world.getSeaLevel() + 32;
		}
		playerSurfaceWorld = world;
		playerSurfaceX = x;
		playerSurfaceY = y;
		playerSurfaceZ = z;
		int surface = playerChunk.sampleHeightmap(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
				x & 15, z & 15);
		playerAtOrAboveSurface = y >= surface;
		return playerAtOrAboveSurface;
	}

	/** Update residency only when it can change, plus a bounded timer sweep for delayed eviction. */
	private static void updateUndergroundResidency(net.minecraft.client.world.ClientWorld world,
			net.minecraft.client.network.ClientPlayerEntity player, BlockPos playerPos,
			int pcx, int psy, int pcz) {
		MoneyakShadersConfig cfg = MoneyakShadersConfig.get();
		int configSignature = 17;
		configSignature = 31 * configSignature + (cfg.deferUndergroundSections ? 1 : 0);
		configSignature = 31 * configSignature + String.valueOf(cfg.undergroundSleepMode).hashCode();
		configSignature = 31 * configSignature + cfg.undergroundActiveRadiusSections;
		configSignature = 31 * configSignature + cfg.undergroundEvictionDelayMs;
		configSignature = 31 * configSignature + cfg.undergroundSleepGpuBudgetMb;
		boolean spectator = player.isSpectator();
		long now = System.currentTimeMillis();
		boolean moved = world != residencyWorld || pcx != lastResidencyCx || psy != lastResidencySy
				|| pcz != lastResidencyCz;
		boolean due = now >= nextUndergroundResidencySweepMs;
		if (moved || due) {
			undergroundView = isUndergroundViewer(world, player);
		}
		// Re-evaluate on section movement: the preparation halo must wake before the camera reaches
		// its geometry, rather than waiting up to half a second for the eviction timer.
		boolean changed = moved || world != residencyWorld || spectator != lastResidencySpectator
				|| undergroundView != lastResidencyUnderground
				|| configSignature != lastResidencyConfigSignature;
		if (changed || due) {
			SCENE_RESIDENCY.beginSweep();
			if (changed) prioritizeViewerScenes(pcx, psy, pcz);
			residencyWorld = world;
			lastResidencyCx = pcx;
			lastResidencySy = psy;
			lastResidencyCz = pcz;
			lastResidencyUnderground = undergroundView;
			lastResidencySpectator = spectator;
			lastResidencyConfigSignature = configSignature;
			nextUndergroundResidencySweepMs = now + UNDERGROUND_RESIDENCY_SWEEP_MS;
		}
		refreshUndergroundResidency(world, pcx, psy, pcz, undergroundView, spectator);
	}

	private static void prioritizeViewerScenes(int cx, int sy, int cz) {
		for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++)
			for (int dz = -1; dz <= 1; dz++) {
				long key = ChunkSectionPos.asLong(cx + dx, sy + dy, cz + dz);
				SCENE_RESIDENCY.prioritizeNear(key);
				CACHE_RESIDENCY.prioritizeNear(key);
			}
	}

	/**
	 * Turns the previous admission-only underground defer into real residency control. Sleeping meshes
	 * are never drawn, lit or shadowed; MAXIMUM_FPS releases their arena ranges after a short grace
	 * period. Entering a cave/spectator mode wakes the live set and lets normal near-first admission
	 * rebuild evicted sections without retaining stale world data.
	 */
	private static void refreshUndergroundResidency(net.minecraft.client.world.ClientWorld world,
			int pcx, int psy, int pcz, boolean playerUnderground, boolean spectator) {
		MoneyakShadersConfig cfg = MoneyakShadersConfig.get();
		boolean enabled = cfg.deferUndergroundSections && "MAXIMUM_FPS".equalsIgnoreCase(cfg.undergroundSleepMode)
				&& !spectator;
		long now = System.nanoTime() / 1_000_000L;
		long budgetBytes = Math.max(0L, cfg.undergroundSleepGpuBudgetMb) * 1024L * 1024L;
		sceneEvictions = 0;
		MinecraftClient client = MinecraftClient.getInstance();
		int activeRadius = Math.max(Math.min(Math.max(2, client.options.getClampedViewDistance()), RADIUS_CAP),
				Math.min(Math.max(2, cfg.shadowDistanceChunks), RADIUS_CAP));
		SCENE_RESIDENCY.process(128, 500_000L, System::nanoTime, key -> {
			Sec s = SECTIONS.get(key);
			if (s == null) { SCENE_RESIDENCY.remove(key); return; }
			if (Math.abs(s.cx - pcx) > activeRadius || Math.abs(s.cz - pcz) > activeRadius
					|| !inActiveRange(world, s.cx, s.sy, s.cz, psy)) {
				if (sceneEvictions < 16) {
					sceneEvictions++;
					retireSceneSection(key, s, cfg.sectionCache);
				}
				return;
			}
			boolean relevant = !enabled || sectionRelevantToViewerDomain(world, s.cx, s.sy, s.cz,
					pcx, psy, pcz, playerUnderground, s);
			boolean sleep = SCENE_RESIDENCY.shouldSleep(key, relevant, now, 750L);
			if (!sleep) {
				if (relevant) wakeSceneSection(key, s);
				return;
			}
			if (s.residency != Sec.Residency.SLEEPING) {
				s.residency = Sec.Residency.SLEEPING;
				s.sleepingSinceMs = now;
				if (s.wcount > 0) translucentOrderRevision++;
				invalidateStaticPointMapsForSection(s.cx, s.sy, s.cz);
			}
			SCENE_RESIDENCY.sleep(key, sectionVertexBytes(s));
			if (sceneEvictions < 16 && SCENE_RESIDENCY.sleepingBytes() > budgetBytes
					&& now - s.sleepingSinceMs >= Math.max(0, cfg.undergroundEvictionDelayMs)) {
				sceneEvictions++;
				evictedUndergroundBytes += sectionVertexBytes(s);
				freeSection(s);
				SECTIONS.remove(key);
				SCENE_RESIDENCY.remove(key);
				EVICTED_UNDERGROUND.add(key);
			}
		});
		if (!enabled) EVICTED_UNDERGROUND.clear();
	}

	/** Retire one live section without a full-registry scan when the player crosses a chunk edge. */
	private static void retireSceneSection(
			long key,
			Sec s,
			boolean cache) {

		if (s.wcount > 0)
			translucentOrderRevision++;

		if (cache && (s.count > 0 || s.wcount > 0)) {
			orphanSection(key, s);
		} else {
			freeSection(s);
		}

		REMESH.remove(key);

		executor.cancel(key);
		cancelSnapshotJob(key);

		CONTENT_REVISIONS.remove(key);
		LOCAL_BLOCK_EDITS.remove(key);

		UPLOAD_DEPENDENCIES.remove(key);
		UPLOAD_DEPENDENCIES.published(key);

		/*
		* Clear lifecycle-local provisional state.
		* It must not survive an unload/reload of this coordinate.
		*/
		PROVISIONAL_RELIGHTS.remove(key);
		PROVISIONAL_RETRY_REVISIONS.remove(key);

		SECTIONS.remove(key);
		SCENE_RESIDENCY.remove(key);

		/*
		* IMPORTANT: call only AFTER SECTIONS.remove().
		* forgetRenderSection() can now tell whether another neighbouring
		* terrain section still consumes this light section.
		*/
		com.moneyakshaders.client.ClientLightDispatcher
				.forgetRenderSection(key);
	}

	private static void wakeSceneSection(long key, Sec s) {
		SCENE_RESIDENCY.awake(key);
		if (s.residency != Sec.Residency.SLEEPING) return;
		invalidateStaticPointMapsForSection(s.cx, s.sy, s.cz);
		s.sleepingSinceMs = 0;
		if (s.sleepingLightDirty) {
			s.sleepingLightDirty = false;
			s.residency = Sec.Residency.WAKING;
			com.moneyakshaders.client.DebugStats.remeshUndergroundWake.incrementAndGet();
			markDirtyAsync(s.cx, s.sy, s.cz);
		} else {
			s.residency = Sec.Residency.ACTIVE;
			if (s.wcount > 0) translucentOrderRevision++;
		}
	}
	/**
	 * Viewer-domain gate shared by terrain light refreshes and entity-shadow capture. The heightmap
	 * domain is the cheap broad classifier; the camera-independent connectivity graph is a conservative
	 * exception for cave mouths, ravines and other openings where both domains can genuinely meet.
	 */
	private static boolean sectionRelevantToViewerDomain(net.minecraft.client.world.ClientWorld world,
			int cx, int sy, int cz, int pcx, int psy, int pcz, boolean playerUnderground, Sec resident) {
		int radius = Math.max(1, MoneyakShadersConfig.get().undergroundActiveRadiusSections);
		if (Math.abs(cx - pcx) <= radius && Math.abs(sy - psy) <= radius
				&& Math.abs(cz - pcz) <= radius) return true;
		boolean sectionSurface = isSurfaceDomainSection(world, cx, sy, cz);
		if (sectionSurface != playerUnderground) return true;
		if (resident == null) return false;
		long key = ChunkSectionPos.asLong(cx, sy, cz);
		// Residency must never depend on where the camera points. The former final frustum test moved
		// thousands of otherwise unchanged sections ACTIVE <-> SLEEPING on a turn, filled the snapshot
		// queue and produced the reported spike exactly when a waterfall left the screen. Connectivity
		// still keeps genuinely reachable opposite-domain openings awake without view-angle churn.
		return VISIBLE_SECTIONS.isEmpty() || VISIBLE_SECTIONS.contains(key);
	}

	/** Surface terrain envelope plus the air/support section immediately around its highest block. */
	private static boolean isSurfaceDomainSection(net.minecraft.client.world.ClientWorld world,
			int cx, int sy, int cz) {
		if (isSurfaceBandSection(world, cx, sy, cz)) return true;
		long range = surfaceRange(world, cx, cz);
		if (range == Long.MIN_VALUE) return true; // unknown columns stay conservatively in the visible domain
		int highestOccupiedY = ((int) (range >> 32)) - 1;
		return sy >= Math.floorDiv(highestOccupiedY, 16) - 1;
	}

	/** A conservative sky-access test shared by terrain scheduling and shadow work. */
	private static boolean isUndergroundViewer(net.minecraft.client.world.ClientWorld world,
			net.minecraft.client.network.ClientPlayerEntity player) {
		if (world == null || player == null || player.isSpectator()) return false;
		int cx = player.getBlockX() >> 4, cz = player.getBlockZ() >> 4;
		var chunk = world.getChunk(cx, cz, net.minecraft.world.chunk.ChunkStatus.FULL, false);
		if (chunk == null) return false;
		// A roof/bridge raises the heightmap without isolating the player from the surface.
		// Positive propagated skylight is evidence of an opening: retain both visible domains
		// conservatively instead of waking/evicting the world merely by walking under an overhang.
		if (world.getLightLevel(net.minecraft.world.LightType.SKY, player.getBlockPos()) > 0) return false;
		int surface = chunk.sampleHeightmap(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
				player.getBlockX() & 15, player.getBlockZ() & 15);
		// A shallow cave, mineshaft or custom underground hall can be just one block below terrain.
		// The old 8-block hysteresis kept its visible neighbouring sections asleep until the player
		// descended further, which looked exactly like skipped chunks. Prefer correct cave streaming.
		return player.getBlockY() < surface;
	}

	/**
	 * Defers only a complete invisible vertical domain. Every consumer in the 3x3x3 light halo is
	 * examined together, so a provider section can never update one side of a visible chunk boundary
	 * while its neighbour is classified away. Deferred residents retain a dirty bit for atomic wake-up.
	 */
	public static boolean shouldDeferLightRefresh(long sectionKey) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.world == null || client.player == null || client.player.isSpectator()) return false;
		int lx = ChunkSectionPos.unpackX(sectionKey);
		int ly = ChunkSectionPos.unpackY(sectionKey);
		int lz = ChunkSectionPos.unpackZ(sectionKey);
		int pcx = client.player.getBlockX() >> 4;
		int psy = client.player.getBlockY() >> 4;
		int pcz = client.player.getBlockZ() >> 4;
		boolean relevant = false;
		for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
			int cx = lx + dx, sy = ly + dy, cz = lz + dz;
			long consumerKey = ChunkSectionPos.asLong(cx, sy, cz);
			Sec consumer = SECTIONS.get(consumerKey);
			if (sectionRelevantToViewerDomain(client.world, cx, sy, cz, pcx, psy, pcz,
					undergroundView, consumer)) {
				if (consumer != null || hasPendingSectionMesh(consumerKey)) relevant = true;
			} else if (consumer != null) {
				consumer.sleepingLightDirty = true;
			}
		}
		return !relevant;
	}

	/**
	 * A chunk packet arrived.  The initial snapshot may have been built before this column existed;
	 * only those snapshots need a follow-up mesh.  Rebuilding every surrounding first mesh here
	 * doubles the work during a normal streaming burst.
	 */
	public static void remeshReceivedColumnAround(int chunkX, int chunkZ) {
		// A newly received/replaced column may reuse a chunk coordinate with different block states.
		// Invalidate its compact lamp index before nearby snapshots consume its halo.
		SectionSnapshotBuilder.invalidateLightSourceColumn(chunkX, chunkZ);
		RECEIVED_COLUMN_REMESH.offer(ChunkSectionPos.asLong(chunkX, 0, chunkZ));
	}

	/**
	 * A biome/color invalidation affects even snapshots which already had all nine input columns.
	 * The marker's unused Y component distinguishes this forced refresh from a chunk-packet arrival.
	 */
	public static void remeshColumnAround(int chunkX, int chunkZ) {
		SectionSnapshotBuilder.invalidateLightSourceColumn(chunkX, chunkZ);
		RECEIVED_COLUMN_REMESH.offer(ChunkSectionPos.asLong(chunkX, 1, chunkZ));
	}

	/** Render-thread expansion of coalesced packet and color invalidations. */
	private static void drainReceivedColumnRemeshes(int playerSectionY) {
		RECEIVED_COLUMN_REMESH.drainTo(RECEIVED_COLUMN_REMESH_SCRATCH, RECEIVED_COLUMN_REMESH_PER_FRAME);
		if (RECEIVED_COLUMN_REMESH_SCRATCH.isEmpty()) return;
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.world == null || client.player == null) return;
		int playerChunkX = client.player.getBlockX() >> 4;
		int playerChunkZ = client.player.getBlockZ() >> 4;
		int minSectionY = client.world.getBottomY() >> 4;
		int maxSectionY = (client.world.getBottomY() + client.world.getHeight() - 1) >> 4;
		for (int i = 0; i < RECEIVED_COLUMN_REMESH_SCRATCH.size(); i++) {
			long columnKey = RECEIVED_COLUMN_REMESH_SCRATCH.getLong(i);
			int chunkX = ChunkSectionPos.unpackX(columnKey);
			int chunkZ = ChunkSectionPos.unpackZ(columnKey);
			int markerType = ChunkSectionPos.unpackY(columnKey);
			SURFACE_RANGE_CACHE.remove(columnKey(chunkX, chunkZ));
			// This column's height can expose (or cover) a vertical cliff in any of its eight neighbours.
			// Invalidate all affected masks before rebuilding any admission state, otherwise the first
			// neighbour rebuilt here can recache the old own-column-only lower bound for the rest of load.
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					SURFACE_SECTION_MASK_CACHE.remove(columnKey(chunkX + dx, chunkZ + dz));
				}
			}
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					int affectedX = chunkX + dx, affectedZ = chunkZ + dz;
					var affectedChunk = client.world.getChunk(affectedX, affectedZ,
							net.minecraft.world.chunk.ChunkStatus.FULL, false);
					if (affectedChunk != null) {
						queueAdmissionColumn(client.world, affectedChunk, affectedX, affectedZ,
								playerChunkX, playerSectionY, playerChunkZ);
					}
				}
			}
			// Type 2 comes from an already-dirtied block/section. Rebuild its scheduling metadata and
			// admissions only; geometry/light invalidation was published by the edit path itself.
			if (markerType == 2) continue;
			boolean forceRefresh = markerType == 1;
			for (int cx = chunkX - 1; cx <= chunkX + 1; cx++) {
				for (int cz = chunkZ - 1; cz <= chunkZ + 1; cz++) {
					for (int sy = minSectionY; sy <= maxSectionY; sy++) {
						// No active mesh can survive outside this same visible vertical band.  Skipping it
						// avoids requeueing deep/unreachable data during a join burst.
						if (!inActiveRange(client.world, cx, sy, cz, playerSectionY)) continue;
						long key = ChunkSectionPos.asLong(cx, sy, cz);
						SnapshotJob pendingSnapshot = SNAPSHOT_JOBS.get(key);
						// A full column packet may replace the target ChunkSection object itself. A snapshot
						// that already had this column cached is therefore not necessarily current: the old
						// missing-halo-only test let partial target meshes survive until an unrelated light
						// rebuild, which presented as random white holes filling tens of seconds later.
						boolean targetColumnReplaced = cx == chunkX && cz == chunkZ;
						boolean queuedNeedsRefresh = pendingSnapshot != null && (forceRefresh
								|| targetColumnReplaced
								|| pendingSnapshot.builder.needsHaloRefreshForColumn(chunkX, chunkZ));
						boolean workerNeedsRefresh = executor != null && (forceRefresh
								|| targetColumnReplaced
								? executor.isInFlight(key)
								: executor.needsHaloRefresh(key, chunkX, chunkZ));
						Sec resident = SECTIONS.get(key);
						int haloDx = chunkX - cx + 1;
						int haloDz = chunkZ - cz + 1;
						boolean residentNeedsRefresh = resident != null && (forceRefresh
								|| targetColumnReplaced
								|| haloDx >= 0 && haloDx <= 2 && haloDz >= 0 && haloDz <= 2
										&& (resident.missingColumnMask & (1 << (haloDx * 3 + haloDz))) != 0);
						if (residentNeedsRefresh || queuedNeedsRefresh || workerNeedsRefresh) {
							// A newly available block/biome halo can change exposed faces and baked colour, not only
							// lighting. Give that finite correction the live-edit lane so it catches the first mesh
							// during streaming instead of appearing as a slow colour wave afterwards.
							ASYNC_ONLY.remove(key);
							if (resident == null && (queuedNeedsRefresh || workerNeedsRefresh)) {
								// Preserve the first coherent result as a provisional mesh. Incrementing its content
								// revision here contradicted the dirty-drain first-mesh guard below: upload rejected
								// the very mesh that guard intentionally kept alive, leaving white surface holes while
								// neighbouring server chunks continued to arrive. The immutable input captured a missing
								// column as air, so it is safe to publish briefly; the retained REMESH replaces its exposed
								// boundary faces immediately after that first upload. Real block edits still use
								// markContentDirty and retain strict stale-result rejection.
								REMESH.add(key);
							} else {
								markContentDirty(key);
							}
						}
					}
				}
			}
		}
	}

	/** Dirty keys that must NOT be meshed synchronously (light-driven refreshes). */
	private static final java.util.Set<Long> ASYNC_ONLY = java.util.concurrent.ConcurrentHashMap.newKeySet();
	private static long lastUpdateNs; // previous updateSections timestamp — drives the adaptive mesh budget

	private static void freeSection(Sec s) {
		if (s.count > 0) {
			vertexArena.free(s.vOff, (long) (s.count / 6 * 4) * TerrainVertex.STRIDE);
			if (s.iOff != sharedQuadIndexOffset) {
				indexArena.free(s.iOff, (long) s.count * Integer.BYTES);
			}
		}
		if (s.wcount > 0) {
			vertexArena.free(s.wvOff, (long) (s.wcount / 6 * 4) * TerrainVertex.STRIDE);
			indexArena.free(s.wiOff, (long) s.wcount * Integer.BYTES);
		}
	}

	/** Vertex-arena bytes a section occupies (both layers) — the budget metric for the section cache. */
	private static long sectionVertexBytes(Sec s) {
		long b = 0;
		if (s.count > 0) b += (long) (s.count / 6 * 4) * TerrainVertex.STRIDE;
		if (s.wcount > 0) b += (long) (s.wcount / 6 * 4) * TerrainVertex.STRIDE;
		return b;
	}

	private static Sec removeOrphanEntry(long key) {
		CACHE_RESIDENCY.remove(key);
		return ORPHANS.remove(key);
	}

	/** Restore a bounded slice of already uploaded geometry, then validate against the live world. */
	private static void restoreCachedScenes(MinecraftClient client, int pcx, int psy, int pcz) {
		CACHE_RESIDENCY.process(64, 350_000L, System::nanoTime, key -> {
			Sec s = ORPHANS.get(key);
			if (s == null) { CACHE_RESIDENCY.remove(key); return; }
			if (Math.abs(s.cx - pcx) > radiusChunks || Math.abs(s.cz - pcz) > radiusChunks
					|| !inActiveRange(client.world, s.cx, s.sy, s.cz, psy) || SECTIONS.containsKey(key)
					|| shouldDeferUnderground(client.world, s.cx, s.sy, s.cz, pcx, psy, pcz)) return;
			removeOrphanEntry(key);
			orphanVertexBytes -= sectionVertexBytes(s);
			SECTIONS.put(key, s);
			SCENE_RESIDENCY.register(key);
			// Cancelled WAKING work cannot survive an orphan round trip. Validate it again below.
			if (s.residency == Sec.Residency.WAKING) {
				s.residency = Sec.Residency.SLEEPING;
				s.sleepingLightDirty = true;
			}
			wakeSceneSection(key, s);
			invalidateStaticPointMapsForSection(s.cx, s.sy, s.cz);
			if (s.wcount > 0) translucentOrderRevision++;
			visibilityDirty = true;
			if (isSurfaceBandSection(client.world, s.cx, s.sy, s.cz)
					|| Math.abs(s.cx - pcx) <= 1 && Math.abs(s.sy - psy) <= 1 && Math.abs(s.cz - pcz) <= 1) {
				ASYNC_ONLY.remove(key);
				markContentDirty(key);
			} else {
				com.moneyakshaders.client.DebugStats.remeshCacheValidation.incrementAndGet();
				markDirtyAsync(s.cx, s.sy, s.cz);
			}
		});
	}

	/** Stash an out-of-range section in the cache (keep its GPU regions). Evicts eldest over budget. */
	private static void orphanSection(long key, Sec s) {
		Sec prev = removeOrphanEntry(key);
		if (prev != null) { // key should have been in SECTIONS, not ORPHANS — defend against a double stash
			orphanVertexBytes -= sectionVertexBytes(prev);
			freeSection(prev);
		}
		ORPHANS.put(key, s);
		CACHE_RESIDENCY.register(key);
		orphanVertexBytes += sectionVertexBytes(s);
		}

	private static void trimOrphanCache() {
		long start = System.nanoTime();
		for (int i = 0; i < 16 && orphanVertexBytes > ORPHAN_VERTEX_BUDGET && ORPHANS.size() > 1; i++) {
			long eldest = ORPHANS.firstLongKey();
			Sec s = removeOrphanEntry(eldest);
			orphanVertexBytes -= sectionVertexBytes(s);
			freeSection(s);
			if (System.nanoTime() - start >= 300_000L) break;
		}
	}
	/** Free + forget a cached section for this key (a fresh mesh supersedes it). No-op if not cached. */
	private static void dropOrphan(long key) {
		Sec o = removeOrphanEntry(key);
		if (o != null) {
			orphanVertexBytes -= sectionVertexBytes(o);
			freeSection(o);
		}
	}

	/** Release every live GPU section when its ClientWorld is replaced. Render thread only. */
	private static void clearLiveSections() {
		abandonActiveIncrementalUpload(false);
		RETIRED_SCENES.detach(SECTIONS.values());
		SECTIONS = new Long2ObjectOpenHashMap<>();
		// Tombstones are only a residency diagnostic for the current ClientWorld.  Retaining packed
		// coordinates across dimensions makes the counters lie and can interfere with future wake-up
		// diagnostics for the same coordinate.
		EVICTED_UNDERGROUND.clear();
		SCENE_RESIDENCY.clear();
		evictedUndergroundBytes = 0L;
		translucentOrderRevision++;
		REMESH.clear();
		BLOCK_EDIT_RELIGHT_SOURCES.clear();
		BLOCK_EDIT_RELIGHT_DUE_MS.clear();
		PROVISIONAL_RELIGHTS.clear();
		PROVISIONAL_RETRY_REVISIONS.clear();
		COMPLETED_LIGHT_PASSES.set(0L);
		CONTENT_REVISIONS.clear();
		CONTENT_DIRTY_PENDING.clear();
		BLOCK_EDIT_BURST_SECTIONS.clear();
		BLOCK_EDIT_LIGHT_PENDING.clear();
		BLOCK_EDIT_BURST_COUNT.set(0);
		bulkBlockEditBurst = false;
		LOCAL_BLOCK_EDITS.clear();
		UPLOAD_DEPENDENCIES.clear();
		RECEIVED_COLUMN_REMESH.clear();
		VISIBLE_SECTIONS.clear();
		SHADOW_CASTERS.clear();
		shadowCastersKnown = false;
		casterViewX = casterViewY = casterViewZ = Float.NaN;
		nextShadowCasterDirtyRebuildNs = 0L;
		SURFACE_RANGE_CACHE.clear();
		SURFACE_SECTION_MASK_CACHE.clear();
		NEIGHBOR_SURFACE_FRAME_CACHE.clear();
		KNOWN_ADMISSION_COLUMNS.clear();
		COLUMN_ADMISSIONS.clear();
		SURFACE_ADMISSION_QUEUE.clear();
		ENVELOPE_ADMISSION_QUEUE.clear();
		DETAIL_ADMISSION_QUEUE.clear();
		haveAdmissionPriorityFwd = false;
		surfaceRangeWorld = null;
		playerSurfaceWorld = null;
		admissionScanDirty = true;
		admissionScanWorld = null;
		DRAWN_ONCE.clear();
		visibilityDirty = true;
		castersDirty = true;
		resetDirectionalShadowState();
		sunTerrainRevision = 0L;
		GPU_PROFILER.resetMeasurements();
		GPU_WORKLOAD.reset();
		gpuWorkloadLevel = 0;
		SUN_QUALITY.reset();
		POINT_QUALITY.reset();
		adaptiveShadowLevel = adaptivePointShadowLevel = 0;
		lastVisSectionKey = Long.MIN_VALUE;
		VISIBILITY_GRAPH.reset();
		java.util.Arrays.fill(pLightValid, false);
		java.util.Arrays.fill(pLightFade, 0f);
		java.util.Arrays.fill(pRenderX, Float.NaN);
		java.util.Arrays.fill(pMapDirty, false);
		java.util.Arrays.fill(pMapRevision, 0L);
		java.util.Arrays.fill(pMapMotionDirty, false);
		java.util.Arrays.fill(pEntityMotionFingerprint, Long.MIN_VALUE);
		java.util.Arrays.fill(pMapDirtyReadyNs, 0L);
		java.util.Arrays.fill(pMapLastRenderNs, 0L);
		java.util.Arrays.fill(pEntityMapValid, false);
		scanPN = 0;
		scanSatCount = 0;
		heroScanCooldown = 0;
		scanOriginX = scanOriginY = scanOriginZ = Integer.MIN_VALUE;
		pointSelectionLastNs = 0L;
		pointSatFactor = 1f;
		pointTerrainBuildSlot = -1;
		pointTerrainBuildNextFace = 0;
		pointTerrainBuildRevision = 0L;
	}

	/** Drop the whole cache (world / dimension change → the cached geometry belongs to a different world). */
	private static void clearOrphans() {
		RETIRED_SCENES.detach(ORPHANS.values());
		ORPHANS = new it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<>();
		CACHE_RESIDENCY.clear();
		orphanVertexBytes = 0;
	}

	/**
	 * Resource packs may relocate atlas sprites while keeping the multiplayer world alive. Keep the
	 * last good live mesh visible and schedule its replacement through the ordinary bounded mesh/upload
	 * budgets; freeing all sections here would turn a pack reload into a multi-second terrain hole.
	 * Cached/orphaned geometry is never displayed during reload, because it can no longer be trusted to
	 * match the new atlas. This method is render-thread only and is called at WorldRenderer.reload.
	 */
	public static void invalidateResourceDependentData() {
		com.moneyakshaders.client.AnimatedTextureScheduler.clear();
		cachedAtlasGlId = -1;
		cachedItemsAtlasGlId = -1;
		diskLoadGeneration.incrementAndGet();
		diskLoadInFlight = false;
		PENDING_INSTALL.clear();
		clearOrphans();
		DIRTY.clear();
		BLOCK_EDIT_RELIGHT_SOURCES.clear();
		BLOCK_EDIT_RELIGHT_DUE_MS.clear();
		PROVISIONAL_RELIGHTS.clear();
		PROVISIONAL_RETRY_REVISIONS.clear();
		CONTENT_REVISIONS.clear();
		CONTENT_DIRTY_PENDING.clear();
		BLOCK_EDIT_BURST_SECTIONS.clear();
		BLOCK_EDIT_LIGHT_PENDING.clear();
		BLOCK_EDIT_BURST_COUNT.set(0);
		bulkBlockEditBurst = false;
		LOCAL_BLOCK_EDITS.clear();
		UPLOAD_DEPENDENCIES.clear();
		RECEIVED_COLUMN_REMESH.clear();
		REMESH.addAll(SECTIONS.keySet());
		abandonActiveIncrementalUpload(true);
		clearSnapshotJobs();
		visibilityDirty = true;
		castersDirty = true;
		if (executor != null) {
			executor.cancelAll();
		}
		for (ChunkMeshExecutor.MeshResult stale : UPLOAD_BACKLOG) {
			stale.mesh().free();
			if (executor != null) {
				executor.releaseReadyResultSlot(stale.streamPriority());
			}
		}
		UPLOAD_BACKLOG.clear();
		UPLOAD_BACKLOG_KEYS.clear();
	}

	// ---- v1 disk section cache ---------------------------------------------------------------------

	/** Read a live section's meshed vertex bytes back from the GPU arenas into a serialisable entry. */
	private static SectionDiskCache.Entry readbackEntry(long key, Sec s) {
		SectionDiskCache.Entry e = new SectionDiskCache.Entry();
		e.key = key;
		e.openFaces = s.openFaces;
		e.visibility = s.visibility;
		if (s.count > 0) {
			int verts = s.count / 6 * 4;
			int bytes = verts * TerrainVertex.STRIDE;
			java.nio.ByteBuffer bb = MemoryUtil.memAlloc(bytes);
			vertexArena.read(s.vOff, bb);
			byte[] arr = new byte[bytes];
			bb.get(arr);
			MemoryUtil.memFree(bb);
			e.solid = arr;
			e.solidVerts = verts;
		}
		if (s.wcount > 0) {
			int verts = s.wcount / 6 * 4;
			int bytes = verts * TerrainVertex.STRIDE;
			java.nio.ByteBuffer bb = MemoryUtil.memAlloc(bytes);
			vertexArena.read(s.wvOff, bb);
			byte[] arr = new byte[bytes];
			bb.get(arr);
			MemoryUtil.memFree(bb);
			e.water = arr;
			e.waterVerts = verts;
		}
		return e;
	}

	/** Upload a decoded cache entry into the arenas and stash it as an orphan (so promotion serves it). */
	private static void installEntry(SectionDiskCache.Entry e) {
		// Byte length must match the vertex count × stride, or uploadLayerToArena would over/under-fill the
		// arena allocation. A mismatch means a corrupt/foreign file slipped past the fingerprint — drop it.
		if (e.solid.length != e.solidVerts * TerrainVertex.STRIDE
				|| e.water.length != e.waterVerts * TerrainVertex.STRIDE) {
			return;
		}
		int cx = ChunkSectionPos.unpackX(e.key);
		int sy = ChunkSectionPos.unpackY(e.key);
		int cz = ChunkSectionPos.unpackZ(e.key);
		Sec s = new Sec();
		s.cx = cx; s.sy = sy; s.cz = cz;
		s.bx = cx << 4; s.by = sy << 4; s.bz = cz << 4;
		s.openFaces = e.openFaces;
		s.visibility = e.visibility;
		if (e.solidVerts > 0) {
			java.nio.ByteBuffer bb = MemoryUtil.memAlloc(e.solid.length);
			bb.put(e.solid).flip();
			long[] r = uploadLayerToArena(bb, e.solidVerts, true);
			MemoryUtil.memFree(bb);
			if (r == null) return; // arena full — skip this cached section
			s.vOff = r[0]; s.iOff = r[1]; s.count = (int) r[2];
		}
		if (e.waterVerts > 0) {
			java.nio.ByteBuffer bb = MemoryUtil.memAlloc(e.water.length);
			bb.put(e.water).flip();
			long[] r = uploadLayerToArena(bb, e.waterVerts, false);
			MemoryUtil.memFree(bb);
			if (r == null) { freeSection(s); return; }
			s.wvOff = r[0]; s.wiOff = r[1]; s.wcount = (int) r[2];
			s.wCentroids = e.translucentQuadCentroids;
			s.wMixedWater = e.translucentMixedWater;
			s.wOnlyWater = e.translucentOnlyWater;
		}
		if (s.count == 0 && s.wcount == 0) return;
		orphanSection(e.key, s);
	}

	/** Serialise the current cache (nearest-first, bounded) for the world being left → background write. */
	private static void flushDiskCache(net.minecraft.client.MinecraftClient mc, net.minecraft.client.world.ClientWorld world, boolean async) {
		MoneyakShadersConfig cfg = MoneyakShadersConfig.get();
		if (!cfg.sectionCache || !cfg.sectionCacheDisk || world == null) {
			return;
		}
		// GPU buffer readback is inherently render-context bound and synchronous. The old "async" path
		// only moved file I/O off-thread: it still copied up to 48 MB from the GPU and allocated every
		// cache entry on the render thread exactly when a teleport/world hand-off was busiest. Persist on
		// the clean game-quit path instead; in-session worlds retain their RAM orphan cache, and an older
		// valid disk cache is preferable to a guaranteed multi-frame stall on every backend switch.
		if (async) {
			return;
		}
		String serverKey;
		try {
			serverKey = SectionDiskCache.serverKey(mc);
		} catch (Throwable t) {
			serverKey = null; // integrated server mid-shutdown, etc.
		}
		if (serverKey == null) {
			serverKey = cachedServerKey; // disconnect: the server entry may already be cleared
		}
		if (serverKey == null) {
			return;
		}
		java.util.ArrayList<Sec> cand = new java.util.ArrayList<>(SECTIONS.size() + ORPHANS.size());
		for (Sec s : SECTIONS.values()) {
			if (s.count > 0 || s.wcount > 0) cand.add(s);
		}
		for (Sec s : ORPHANS.values()) {
			if (s.count > 0 || s.wcount > 0) cand.add(s);
		}
		if (cand.isEmpty()) {
			return;
		}
		if (mc.player != null) {
			net.minecraft.util.math.BlockPos pp = mc.player.getBlockPos();
			final int fx = pp.getX() >> 4, fz = pp.getZ() >> 4;
			cand.sort((a, b) -> Integer.compare(
					Math.max(Math.abs(a.cx - fx), Math.abs(a.cz - fz)),
					Math.max(Math.abs(b.cx - fx), Math.abs(b.cz - fz))));
		}
		// A coloured-light tint is baked from the section plus its 1-section halo. Besides becoming stale
		// when the server changes a lamp, persisting these vertices would force the loader to discard them
		// after decoding. Exclude the complete source halo before any synchronous GPU readback instead.
		it.unimi.dsi.fastutil.longs.LongOpenHashSet colouredLightHalo =
				new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
		for (Sec source : cand) {
			if (source.placedLightCount <= 0) continue;
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -1; dz <= 1; dz++) {
					for (int dx = -1; dx <= 1; dx++) {
						colouredLightHalo.add(ChunkSectionPos.asLong(source.cx + dx, source.sy + dy, source.cz + dz));
					}
				}
			}
		}
		java.util.ArrayList<SectionDiskCache.Entry> out = new java.util.ArrayList<>();
		long bytes = 0;
		for (Sec s : cand) { // nearest first; persistent cache is intentionally smaller than the RAM cache
			long key = ChunkSectionPos.asLong(s.cx, s.sy, s.cz);
			if (colouredLightHalo.contains(key)) continue;
			long vb = sectionVertexBytes(s);
			if (bytes + vb > DISK_CACHE_WRITE_BUDGET && !out.isEmpty()) {
				break;
			}
			out.add(readbackEntry(key, s));
			bytes += vb;
		}
		final String sk = serverKey;
		final String dim = SectionDiskCache.dimKey(world);
		final int fp = SectionDiskCache.packFingerprint(mc);
		if (async) {
			Thread t = new Thread(() -> SectionDiskCache.save(sk, dim, fp, out), "opl-section-cache-save");
			t.setDaemon(true);
			t.start();
		} else {
			// game quit: a daemon thread would be killed mid-write by the exiting JVM — write inline
			SectionDiskCache.save(sk, dim, fp, out);
		}
		MoneyakShaders.LOGGER.info("[Plan C/GL] section cache: flushing {} sections ({} KB) → {}/{}",
				out.size(), bytes / 1024, sk, dim);
	}

	/**
	 * Game-quit flush (MinecraftClient.stop — window closed / Quit Game while still in a world). Without
	 * this, only a clean disconnect (world → null tick) saved the cache, so closing the game in-world
	 * lost it every time — "the disk cache never survives a restart". Runs on the render thread with GL
	 * still alive; the file write itself is synchronous here so JVM exit can't truncate it.
	 */
	public static void flushDiskCacheOnQuit(net.minecraft.client.MinecraftClient mc) {
		try {
			if (mc != null && mc.world != null) {
				flushDiskCache(mc, mc.world, false);
			}
		} catch (Throwable t) {
			MoneyakShaders.LOGGER.warn("[Optimized Loading] section cache flush on quit failed", t);
		}
	}

	/** Kick off a background decode of the world being joined; entries install budgeted on the render thread. */
	private static void loadDiskCache(net.minecraft.client.MinecraftClient mc, net.minecraft.client.world.ClientWorld world) {
		MoneyakShadersConfig cfg = MoneyakShadersConfig.get();
		long generation = diskLoadGeneration.incrementAndGet();
		diskLoadInFlight = false;
		PENDING_INSTALL.clear();
		if (!cfg.sectionCache || !cfg.sectionCacheDisk || world == null) {
			return;
		}
		final String serverKey;
		final String dim;
		final int fp;
		// The disk file is saved around the old position, while a proxy/reconnect can place the player
		// elsewhere.  Never spend GL upload time restoring those irrelevant entries before the terrain
		// surrounding the current camera.  The server will provide uncached terrain through the normal
		// streaming path, so dropping out-of-range cache entries cannot create a hole.
		final int loadCx = mc.player == null ? Integer.MIN_VALUE : mc.player.getBlockX() >> 4;
		final int loadCy = mc.player == null ? Integer.MIN_VALUE : mc.player.getBlockY() >> 4;
		final int loadCz = mc.player == null ? Integer.MIN_VALUE : mc.player.getBlockZ() >> 4;
		final int loadRadius = radiusChunks;
		try {
			serverKey = SectionDiskCache.serverKey(mc);
			dim = SectionDiskCache.dimKey(world);
			fp = SectionDiskCache.packFingerprint(mc);
		} catch (Throwable t) {
			MoneyakShaders.LOGGER.warn("[Optimized Loading] section cache load skipped (key resolve failed)", t);
			return;
		}
		if (serverKey == null) {
			return;
		}
		diskLoadInFlight = true;
		Thread t = new Thread(() -> {
			try {
				java.util.List<SectionDiskCache.Entry> es = SectionDiskCache.load(serverKey, dim, fp);
				if (es != null && generation == diskLoadGeneration.get()) {
					int decoded = es.size();
					if (loadCx != Integer.MIN_VALUE) {
						es.removeIf(e -> Math.abs(ChunkSectionPos.unpackX(e.key) - loadCx) > loadRadius
								|| Math.abs(ChunkSectionPos.unpackZ(e.key) - loadCz) > loadRadius
								|| !inVerticalRange(ChunkSectionPos.unpackY(e.key), loadCy));
						// A saved mesh has no live heightmap, but its highest non-empty section is a
						// reliable surface-envelope proxy. The old player-Y heuristic restored caves
						// first whenever the saved player was flying or underground.
						it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap columnTops =
								new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
						columnTops.defaultReturnValue(Integer.MIN_VALUE);
						for (SectionDiskCache.Entry entry : es) {
							if (entry.solidVerts <= 0 && entry.waterVerts <= 0) continue;
							int cx = ChunkSectionPos.unpackX(entry.key);
							int cz = ChunkSectionPos.unpackZ(entry.key);
							long column = columnKey(cx, cz);
							int sy = ChunkSectionPos.unpackY(entry.key);
							if (sy > columnTops.get(column)) columnTops.put(column, sy);
						}
						es.sort(java.util.Comparator.comparingLong(e -> {
							int cx = ChunkSectionPos.unpackX(e.key);
							int cz = ChunkSectionPos.unpackZ(e.key);
							int dx = cx - loadCx;
							int dy = ChunkSectionPos.unpackY(e.key) - loadCy;
							int dz = cz - loadCz;
							int sy = ChunkSectionPos.unpackY(e.key);
							int top = columnTops.get(columnKey(cx, cz));
							boolean surfaceEnvelope = top != Integer.MIN_VALUE && sy >= top - 2;
							int tier = surfaceEnvelope ? STREAM_SURFACE
									: (dy >= -1 && dy <= 2 ? STREAM_VISIBLE : STREAM_BACKGROUND);
							long ring = Math.max(Math.abs(dx), Math.abs(dz));
							long verticalOrder = surfaceEnvelope ? Math.max(0, top - sy) : Math.abs(dy);
							return tier * STREAM_TIER_SCORE + ring * 16_384L
									+ (long) (dx * dx + dz * dz) * 16L + verticalOrder;
						}));
					}
					for (SectionDiskCache.Entry entry : es) {
						PENDING_INSTALL.offer(new PendingDiskInstall(generation, entry));
					}
					MoneyakShaders.LOGGER.info("[Plan C/GL] section cache: {} relevant sections queued ({} decoded) from {}/{}",
							es.size(), decoded, serverKey, dim);
				}
			} finally {
				if (generation == diskLoadGeneration.get()) diskLoadInFlight = false;
			}
		}, "opl-section-cache-load");
		t.setDaemon(true);
		t.start();
	}

	/**
	 * Client-tick hook (from {@link com.moneyakshaders.mixin.client.DynamicLightTickMixin}). Remembers
	 * the connection's cache key while connected and flushes on a FULL disconnect (world → null), which
	 * {@link #updateSections} — the dimension-change flush point — can never see because it stops running.
	 */
	public static void tickDiskCache(net.minecraft.client.MinecraftClient mc) {
		RETIRED_SCENES.drain(128, 300_000L, System::nanoTime, ExperimentalSectionRender::freeSection);
		MoneyakShadersConfig cfg = MoneyakShadersConfig.get();
		net.minecraft.client.world.ClientWorld w = mc.world;
		if (w != null && cfg.sectionCache && cfg.sectionCacheDisk && cachedServerKey == null) {
			try {
				cachedServerKey = SectionDiskCache.serverKey(mc); // capture while the server entry is still live
			} catch (Throwable ignored) {
				cachedServerKey = null;
			}
		}
		if (diskLastWorld != null && w == null) {
			diskLoadGeneration.incrementAndGet();
			diskLoadInFlight = false;
			PENDING_INSTALL.clear();
			try {
				flushDiskCache(mc, diskLastWorld, true); // GL readback — may be mid-teardown on a hard Quit Game
			} catch (Throwable t) {
				MoneyakShaders.LOGGER.warn("[Optimized Loading] section cache flush on disconnect failed", t);
			}
			clearOrphans();
			cachedServerKey = null;
			lastLoadWorld = null; // next join re-detects a world change and reloads the cache
		}
		diskLastWorld = w;
	}

	/**
	 * Upload a meshed section into the arenas. Returns a Sec (count/wcount == 0 = a genuinely
	 * empty layer, cached so we don't re-mesh air), or NULL if a non-empty layer could not be
	 * allocated (arena full). NULL must NOT be cached — otherwise a transient arena-full would
	 * mark the section permanently "empty" and leave a hole you can never reload (the reported
	 * white gaps in oceans/terrain). The caller skips null so the section is re-submitted later.
	 */
	private static Sec upload(SectionMeshData mesh, int bx, int by, int bz) {
		Sec uploaded;
		int reclaimed = 0;
		while ((uploaded = tryUpload(mesh, bx, by, bz)) == null) {
			// The return cache is optional; terrain in the player's current working set is not. Reclaim
			// cached GPU regions one by one and retry so fast travel cannot leave permanent holes merely
			// because the 192 MB orphan allowance happened to fill before the live radius did.
			if (ORPHANS.isEmpty() || reclaimed++ >= 32) {
				if (!arenaFullWarned) {
					arenaFullWarned = true;
					long mib = 1024L * 1024L;
					MoneyakShaders.LOGGER.warn(
							"[Plan C/GL] arena allocation deferred after bounded cache reclaim: "
									+ "vertex free={} MiB largest={} MiB / {} MiB, "
									+ "index free={} MiB largest={} MiB / {} MiB",
							vertexArena.freeBytes() / mib, vertexArena.largestFreeBlock() / mib,
							vertexArena.capacity() / mib, indexArena.freeBytes() / mib,
							indexArena.largestFreeBlock() / mib, indexArena.capacity() / mib);
				}
				return null;
			}
			long eldest = ORPHANS.firstLongKey();
			Sec cached = removeOrphanEntry(eldest);
			orphanVertexBytes -= sectionVertexBytes(cached);
			freeSection(cached);
		}
		return uploaded;
	}

	private static Sec tryUpload(SectionMeshData mesh, int bx, int by, int bz) {
		mesh.prepareUploadMetadata();
		Sec s = new Sec();
		s.bx = bx;
		s.by = by;
		s.bz = bz;
		s.openFaces = mesh.openFaces;
		s.visibility = mesh.visibility;
		s.placedLights = mesh.placedLights();
		s.placedLightCount = mesh.placedLightCount();
		s.placedLightTotal = mesh.placedLightTotal();
		s.placedLightSolidMask = mesh.placedLightSolidMask();
		s.placedLightFaceMasks = mesh.placedLightFaceMasks();
		s.opaqueVoxels = mesh.opaqueVoxels();
		// Which biome-tint tier the mesher just baked in. The worker read the same published viewer
		// chunk, so this agrees with what BiomeBlurTint actually used for these vertices.
		s.biomeFar = BiomeBlurTint.isFarSection(bx, bz);
		// Connectivity invalidation belongs to atomic publication, not an unfinished GPU allocation.
		boolean failed = false;
		int sv = mesh.vertexCount(SectionMeshData.LAYER_SOLID);
		if (sv > 0) {
			long[] r = uploadLayerToArena(mesh.buffer(SectionMeshData.LAYER_SOLID), sv, true);
			if (r != null) {
				s.vOff = r[0];
				s.iOff = r[1];
				s.count = (int) r[2];
			} else {
				failed = true;
			}
		}
		int tv = mesh.vertexCount(SectionMeshData.LAYER_TRANSLUCENT);
		if (!failed && tv > 0) {
			ByteBuffer tb = mesh.buffer(SectionMeshData.LAYER_TRANSLUCENT);
			mesh.prepareUploadMetadata();
			boolean mixedWater = mesh.translucentMixedWater();
			boolean onlyWater = mesh.translucentOnlyWater();
			/* Legacy render-thread centroid scan retained below only as documentation.
			float[] cent = quadCentroids(tb, tv); // BEFORE upload — the arena copy advances the buffer
			*/
			float[] cent = mesh.translucentQuadCentroids();
			long[] r = uploadLayerToArena(tb, tv, false);
			if (r != null) {
				s.wvOff = r[0];
				s.wiOff = r[1];
				s.wcount = (int) r[2];
				s.wCentroids = cent;
				s.wMixedWater = mixedWater;
				s.wOnlyWater = onlyWater;
			} else {
				failed = true;
			}
		}
		if (failed) {
			freeSection(s); // release whichever layer did allocate
			return null;
		}
		return s;
	}

	private static boolean arenaFullWarned;

	/** Section-local quad centroids from the packed vertex stream (u16 fixed-point positions). */
	private static float[] quadCentroids(ByteBuffer vb, int verts) {
		int quads = verts / 4;
		float[] out = new float[quads * 3];
		int base = vb.position();
		for (int q = 0; q < quads; q++) {
			float sx = 0, sy = 0, sz = 0;
			for (int k = 0; k < 4; k++) {
				int o = base + (q * 4 + k) * TerrainVertex.STRIDE;
				sx += TerrainVertex.decodePos(vb.getShort(o) & 0xFFFF);
				sy += TerrainVertex.decodePos(vb.getShort(o + 2) & 0xFFFF);
				sz += TerrainVertex.decodePos(vb.getShort(o + 4) & 0xFFFF);
			}
			out[q * 3] = sx * 0.25f;
			out[q * 3 + 1] = sy * 0.25f;
			out[q * 3 + 2] = sz * 0.25f;
		}
		return out;
	}

	/** Detect alpha-order-critical water plus glass/translucent sections without moving the buffer. */
	private static boolean hasMixedWaterMaterials(ByteBuffer vb, int verts) {
		boolean water = false;
		boolean nonWater = false;
		int base = vb.position();
		for (int v = 0; v < verts; v += 4) {
			int material = vb.get(base + v * TerrainVertex.STRIDE + TerrainVertex.OFF_MATERIAL) & 0xFF;
			if (McSectionMesher.isWaterMaterial(material)) {
				water = true;
			} else {
				nonWater = true;
			}
			if (water && nonWater) {
				return true;
			}
		}
		return false;
	}

	private static long[] quadSortScratch = new long[1024];
	private static java.nio.IntBuffer quadIndexScratch;

	/**
	 * Re-sort a translucent section's quads back-to-front from the camera and re-upload its slice
	 * of the index arena. Skipped until the camera or view direction has changed perceptibly.
	 * Sorting key packs float distance bits with the quad id into one long so Arrays.sort is
	 * allocation-free (distances are ≥0 → IEEE bits are monotonic).
	 */
	private static boolean maybeResortQuads(Sec s) {
		// Resort on ~any camera movement: between resorts the intra-section alpha order is stale. Water
		// and ice are both blended, so drawing the nearer water before the ice behind it produces the
		// high-altitude flashing grid even though neither face writes depth.
		float dx = (float) capCamX - s.wSortX, dy = (float) capCamY - s.wSortY, dz = (float) capCamZ - s.wSortZ;
		float vx = capturedModelView.m02(), vy = capturedModelView.m12(), vz = capturedModelView.m22();
		float viewDot = vx * s.wSortViewX + vy * s.wSortViewY + vz * s.wSortViewZ;
		if (!Float.isNaN(s.wSortX) && dx * dx + dy * dy + dz * dz < 0.0025f && viewDot > 0.99985f) {
			return false;
		}
		s.wSortX = (float) capCamX;
		s.wSortY = (float) capCamY;
		s.wSortZ = (float) capCamZ;
		s.wSortViewX = vx;
		s.wSortViewY = vy;
		s.wSortViewZ = vz;
		int quads = s.wcount / 6;
		if (quadSortScratch.length < quads) {
			quadSortScratch = new long[Integer.highestOneBit(quads - 1) << 1];
		}
		float[] c = s.wCentroids;
		for (int q = 0; q < quads; q++) {
			float qx = (float) ((s.bx + (double) c[q * 3]) - capCamX);
			float qy = (float) ((s.by + (double) c[q * 3 + 1]) - capCamY);
			float qz = (float) ((s.bz + (double) c[q * 3 + 2]) - capCamZ);
			float depth = vx * qx + vy * qy + vz * qz;
			// Map signed IEEE float bits to unsigned numeric order; front-facing depth is negative.
			int raw = Float.floatToRawIntBits(depth);
			int ordered = raw < 0 ? ~raw : raw ^ 0x80000000;
			quadSortScratch[q] = ((long) ordered << 32) | (q & 0xFFFFFFFFL);
		}
		java.util.Arrays.sort(quadSortScratch, 0, quads);
		if (quadIndexScratch == null || quadIndexScratch.capacity() < quads * 6) {
			if (quadIndexScratch != null) MemoryUtil.memFree(quadIndexScratch);
			quadIndexScratch = MemoryUtil.memAllocInt(Math.max(quads * 6, 6144));
		}
		quadIndexScratch.clear();
		// Camera-space z is negative in front of the camera: ascending numerical depth therefore
		// already is far → near. Walking this array backwards was drawing transparent terrain
		// near → far, which let water reveal the glass/terrain behind it as black "air pockets".
		for (int i = 0; i < quads; i++) {
			int v = ((int) quadSortScratch[i]) * 4;
			quadIndexScratch.put(v).put(v + 1).put(v + 2).put(v).put(v + 2).put(v + 3);
		}
		quadIndexScratch.flip();
		indexArena.uploadInts(s.wiOff, quadIndexScratch);
		return true;
	}

	private static int rawTexture2D(int unit) {
		int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
		GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
		int texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
		GL13.glActiveTexture(active);
		return texture;
	}

	private static int rawSampler(int unit) {
		int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
		GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
		int sampler = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
		GL13.glActiveTexture(active);
		return sampler;
	}

	private static void bindTextureUnit(int unit, int texture) {
		if (unit < 12) {
			GlStateManager._activeTexture(GL13.GL_TEXTURE0 + unit);
			GlStateManager._bindTexture(texture);
			return;
		}

		int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
		GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
		GL13.glActiveTexture(active);
	}

	private static void restoreTextureUnit(int unit, int texture, int sampler) {
		if (unit < 12) {
			GlStateManager._activeTexture(GL13.GL_TEXTURE0 + unit);
			GlStateManager._bindTexture(texture);
			GL33.glBindSampler(unit, sampler);
			return;
		}

		int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
		GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
		GL33.glBindSampler(unit, sampler);
		GL13.glActiveTexture(active);
	}

	/**
	 * Sub-allocate one layer's quads into the shared arenas. Returns {vertexByteOffset,
	 * indexByteOffset, indexCount}, or null if the mesh is empty or the arena is full.
	 * Indices are 0-based within the section; the draw command's baseVertex offsets them.
	 */
	private static long[] uploadLayerToArena(ByteBuffer vertexBytes, int verts, boolean allowSharedQuadIndices) {
		if (vertexBytes == null) {
			return null;
		}
		int quads = verts / 4;
		int indexCount = quads * 6;
		long vSize = (long) verts * TerrainVertex.STRIDE;
		boolean sharedIndices = allowSharedQuadIndices && indexCount <= sharedQuadIndexCount;
		long iSize = sharedIndices ? 0L : (long) indexCount * Integer.BYTES;
		long vOff = vertexArena.alloc(vSize);
		if (vOff < 0) {
			return null;
		}
		long iOff = sharedIndices ? sharedQuadIndexOffset : indexArena.alloc(iSize);
		if (iOff < 0) {
			vertexArena.free(vOff, vSize);
			return null;
		}
		vertexArena.upload(vOff, vertexBytes);
		if (!sharedIndices) {
			java.nio.IntBuffer ib = MemoryUtil.memAllocInt(indexCount);
			for (int q = 0; q < quads; q++) {
				int v = q * 4;
				ib.put(v).put(v + 1).put(v + 2).put(v).put(v + 2).put(v + 3);
			}
			ib.flip();
			indexArena.uploadInts(iOff, ib);
			MemoryUtil.memFree(ib);
		}
		return new long[] { vOff, iOff, indexCount };
	}

	private static long incrementalUploadBytes(SectionMeshData mesh) {
		long bytes = 0L;
		int solidVertices = mesh.vertexCount(SectionMeshData.LAYER_SOLID);
		int translucentVertices = mesh.vertexCount(SectionMeshData.LAYER_TRANSLUCENT);
		bytes += (long) solidVertices * TerrainVertex.STRIDE;
		if (solidVertices / 4 * 6 > sharedQuadIndexCount) {
			bytes += (long) (solidVertices / 4 * 6) * Integer.BYTES;
		}
		bytes += (long) translucentVertices * TerrainVertex.STRIDE;
		bytes += (long) (translucentVertices / 4 * 6) * Integer.BYTES;
		return bytes;
	}

	private static IncrementalLayerUpload allocateIncrementalLayer(ByteBuffer vertices, int vertexCount,
			boolean allowSharedQuadIndices) {
		IncrementalLayerUpload layer = new IncrementalLayerUpload();
		layer.vertices = vertices;
		layer.vertexCount = vertexCount;
		if (vertices == null || vertexCount <= 0) return layer;
		layer.vertexBytes = Math.multiplyExact(vertexCount, TerrainVertex.STRIDE);
		layer.indexCount = vertexCount / 4 * 6;
		layer.sharedIndices = allowSharedQuadIndices && layer.indexCount <= sharedQuadIndexCount;
		layer.vertexOffset = vertexArena.alloc(layer.vertexBytes);
		if (layer.vertexOffset < 0L) return null;
		layer.indexOffset = layer.sharedIndices ? sharedQuadIndexOffset
				: indexArena.alloc((long) layer.indexCount * Integer.BYTES);
		if (layer.indexOffset < 0L) {
			vertexArena.free(layer.vertexOffset, layer.vertexBytes);
			return null;
		}
		return layer;
	}

	private static IncrementalMeshUpload tryBeginIncrementalUpload(ChunkMeshExecutor.MeshResult result) {
		SectionMeshData mesh = result.mesh();
		mesh.prepareUploadMetadata();
		IncrementalLayerUpload solid = allocateIncrementalLayer(
				mesh.buffer(SectionMeshData.LAYER_SOLID), mesh.vertexCount(SectionMeshData.LAYER_SOLID), true);
		if (solid == null) return null;
		IncrementalLayerUpload translucent = allocateIncrementalLayer(
				mesh.buffer(SectionMeshData.LAYER_TRANSLUCENT),
				mesh.vertexCount(SectionMeshData.LAYER_TRANSLUCENT), false);
		if (translucent == null) {
			freeIncrementalLayer(solid);
			return null;
		}
		Sec section = new Sec();
		section.bx = result.bx(); section.by = result.by(); section.bz = result.bz();
		section.openFaces = mesh.openFaces;
		section.visibility = mesh.visibility;
		section.placedLights = mesh.placedLights();
		section.placedLightCount = mesh.placedLightCount();
		section.placedLightTotal = mesh.placedLightTotal();
		section.placedLightSolidMask = mesh.placedLightSolidMask();
		section.placedLightFaceMasks = mesh.placedLightFaceMasks();
		section.opaqueVoxels = mesh.opaqueVoxels();
		section.biomeFar = BiomeBlurTint.isFarSection(result.bx(), result.bz());
		section.vOff = solid.vertexOffset;
		section.iOff = solid.indexOffset;
		section.count = solid.indexCount;
		section.wvOff = translucent.vertexOffset;
		section.wiOff = translucent.indexOffset;
		section.wcount = translucent.indexCount;
		section.wCentroids = mesh.translucentQuadCentroids();
		section.wMixedWater = mesh.translucentMixedWater();
		section.wOnlyWater = mesh.translucentOnlyWater();
		return new IncrementalMeshUpload(result, section, solid, translucent);
	}

	private static IncrementalMeshUpload beginIncrementalUpload(ChunkMeshExecutor.MeshResult result) {
		IncrementalMeshUpload upload;
		int reclaimed = 0;
		while ((upload = tryBeginIncrementalUpload(result)) == null) {
			if (ORPHANS.isEmpty() || reclaimed++ >= 32) return null;
			long eldest = ORPHANS.firstLongKey();
			Sec cached = removeOrphanEntry(eldest);
			orphanVertexBytes -= sectionVertexBytes(cached);
			freeSection(cached);
		}
		return upload;
	}

	private static void freeIncrementalLayer(IncrementalLayerUpload layer) {
		if (layer == null || layer.vertexCount <= 0) return;
		vertexArena.free(layer.vertexOffset, layer.vertexBytes);
		if (!layer.sharedIndices) {
			indexArena.free(layer.indexOffset, (long) layer.indexCount * Integer.BYTES);
		}
	}

	private static void cancelIncrementalUpload(IncrementalMeshUpload upload, boolean freeMesh) {
		if (upload == null) return;
		freeIncrementalLayer(upload.solid);
		freeIncrementalLayer(upload.translucent);
		if (freeMesh) upload.result.mesh().free();
	}

	private static void abandonActiveIncrementalUpload(boolean remesh) {
		IncrementalMeshUpload upload = activeIncrementalUpload;
		if (upload == null) return;
		activeIncrementalUpload = null;
		cancelIncrementalUpload(upload, true);
		if (executor != null) executor.releaseReadyResultSlot(upload.result.streamPriority());
		if (remesh) REMESH.add(upload.result.key());
	}

	/** Copies one bounded piece and returns the number of bytes written. */
	private static int uploadIncrementalLayerPiece(IncrementalLayerUpload layer, int byteLimit) {
		if (layer == null || layer.vertexCount <= 0 || byteLimit <= 0) return 0;
		if (layer.vertexCopied < layer.vertexBytes) {
			int bytes = Math.min(Math.min(byteLimit, 256 * 1024), layer.vertexBytes - layer.vertexCopied);
			vertexArena.uploadSlice(layer.vertexOffset + layer.vertexCopied, layer.vertices,
					layer.vertexCopied, bytes);
			layer.vertexCopied += bytes;
			return bytes;
		}
		if (layer.sharedIndices || layer.indexCopied >= layer.indexCount) return 0;
		if (incrementalIndexScratch == null) {
			incrementalIndexScratch = MemoryUtil.memAllocInt(INDEX_UPLOAD_SCRATCH_INTS);
		}
		int remainingQuads = (layer.indexCount - layer.indexCopied) / 6;
		int maxQuadsByBytes = Math.max(1, byteLimit / (6 * Integer.BYTES));
		int quads = Math.min(remainingQuads,
				Math.min(INDEX_UPLOAD_SCRATCH_INTS / 6, maxQuadsByBytes));
		incrementalIndexScratch.clear();
		for (int q = 0; q < quads; q++) {
			int vertex = (layer.indexCopied / 6 + q) * 4;
			incrementalIndexScratch.put(vertex).put(vertex + 1).put(vertex + 2)
					.put(vertex).put(vertex + 2).put(vertex + 3);
		}
		incrementalIndexScratch.flip();
		indexArena.uploadInts(layer.indexOffset + (long) layer.indexCopied * Integer.BYTES,
				incrementalIndexScratch);
		int indices = quads * 6;
		layer.indexCopied += indices;
		return indices * Integer.BYTES;
	}

	private static boolean incrementalLayerComplete(IncrementalLayerUpload layer) {
		return layer == null || layer.vertexCount <= 0
				|| layer.vertexCopied >= layer.vertexBytes
				&& (layer.sharedIndices || layer.indexCopied >= layer.indexCount);
	}

	private static boolean stepIncrementalUpload(IncrementalMeshUpload upload, int byteBudget,
			long deadlineNs, boolean useFrameBudget) {
		int remaining = (int) Math.min(uploadFrameBytesRemaining, Math.max(64 * 1024, byteBudget));
		while (remaining > 0) {
			int wrote = uploadIncrementalLayerPiece(upload.solid, remaining);
			if (wrote == 0) wrote = uploadIncrementalLayerPiece(upload.translucent, remaining);
			if (wrote == 0) break;
			remaining -= wrote;
			uploadFrameBytesRemaining -= wrote;
			if (useFrameBudget) {
				if (!com.moneyakshaders.client.FrameWorkBudget.hasBudget(
						com.moneyakshaders.client.FrameWorkBudget.BUCKET_MESH_UPLOAD)) break;
			} else if (System.nanoTime() >= deadlineNs) {
				break;
			}
		}
		return incrementalLayerComplete(upload.solid) && incrementalLayerComplete(upload.translucent);
	}

	private static void resolveFluidSprites(MinecraftClient client) {
		long now = System.currentTimeMillis();
		boolean all = waterSpriteResolved && waterFlowResolved && waterOverlayResolved && lavaResolved && lavaFlowResolved;
		// Re-check ~once/sec even after resolving: a SERVER resourcepack re-stitches the block atlas
		// AFTER we first resolved, which MOVES sprites → our cached fluid UVs (and every baked block
		// UV in already-meshed sections) become stale → wrong water colour / textures. We detect the
		// re-stitch by the water sprite's U moving, then re-apply all fluid sprites and re-mesh
		// everything so the new atlas locations are picked up (what the user did manually with F3+A).
		if (all && now - lastFluidResolveMs < 1000L) return;
		lastFluidResolveMs = now;
		try {
			if (client.getTextureManager().getTexture(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE)
					instanceof SpriteAtlasTexture atlas) {
				Sprite ws = atlas.getSprite(ModelBaker.WATER_STILL.getTextureId());
				if (ws != null) {
					boolean atlasChanged = !Float.isNaN(resolvedWaterU0) && ws.getMinU() != resolvedWaterU0;
					McSectionMesher.setWaterSprite(ws.getMinU(), ws.getMaxU(), ws.getMinV(), ws.getMaxV());
					resolvedWaterU0 = ws.getMinU();
					waterSpriteResolved = true;
					Sprite wf = atlas.getSprite(net.minecraft.util.Identifier.of("minecraft", "block/water_flow"));
					if (wf != null) { McSectionMesher.setWaterFlowSprite(wf.getMinU(), wf.getMaxU(), wf.getMinV(), wf.getMaxV()); waterFlowResolved = true; }
					Sprite wo = atlas.getSprite(net.minecraft.util.Identifier.of("minecraft", "block/water_overlay"));
					if (wo != null) { McSectionMesher.setWaterOverlaySprite(wo.getMinU(), wo.getMaxU(), wo.getMinV(), wo.getMaxV()); waterOverlayResolved = true; }
					Sprite ls = atlas.getSprite(net.minecraft.util.Identifier.of("minecraft", "block/lava_still"));
					if (ls != null) { McSectionMesher.setLavaSprite(ls.getMinU(), ls.getMaxU(), ls.getMinV(), ls.getMaxV()); lavaResolved = true; }
					Sprite lf = atlas.getSprite(net.minecraft.util.Identifier.of("minecraft", "block/lava_flow"));
					if (lf != null) { McSectionMesher.setLavaFlowSprite(lf.getMinU(), lf.getMaxU(), lf.getMinV(), lf.getMaxV()); lavaFlowResolved = true; }
					if (atlasChanged) {
						MoneyakShaders.LOGGER.info("[Plan C/GL] block atlas re-stitched (resourcepack) — re-meshing all sections");
						markAllDirty();
					}
				}
			}
		} catch (Throwable t) {
			MoneyakShaders.LOGGER.error("[Plan C/GL] fluid sprite resolve failed", t);
			waterSpriteResolved = waterFlowResolved = waterOverlayResolved = lavaResolved = lavaFlowResolved = true;
		}
	}

	/** Re-mesh every loaded section (e.g. after the atlas is re-stitched by a resourcepack). Render thread.
	 *  ASYNC-only: old meshes keep drawing until each rebuild swaps in — a full re-mesh must never blank
	 *  the world for a few frames (the "white screen flash like a chunk reload" report). */
	private static void markAllDirty() {
		MoneyakShaders.LOGGER.info("[Plan C/GL] full re-mesh requested (atlas change) — rebuilding {} sections asynchronously", SECTIONS.size());
		for (Sec s : SECTIONS.values()) {
			com.moneyakshaders.client.DebugStats.remeshAtlas.incrementAndGet();
			markDirtyAsync(s.cx, s.sy, s.cz);
		}
	}

	/**
	 * Configure the atlas sampler from current settings. Mipmaps need Mipmap Levels &gt; 0
	 * in Video Settings (otherwise the atlas has no mip chain and a mipmap min-filter would
	 * sample incomplete → fall back to plain nearest). NEAREST_MIPMAP_LINEAR keeps the
	 * pixel-art look while smoothing the distance transition; anisotropy sharpens grazing angles.
	 */
	private static void configureAtlasSampler(MinecraftClient client) {
		MoneyakShadersConfig cfg = MoneyakShadersConfig.get();
		int mipLevels = client.options.getMipmapLevels().getValue();
		boolean useMips = cfg.terrainMipmaps && mipLevels > 0;
		int configKey = 31 * (31 * (31 * atlasSampler + mipLevels) + (useMips ? 1 : 0))
				+ Float.floatToIntBits(cfg.terrainAnisotropy);
		if (configKey == atlasSamplerConfigKey) {
			return;
		}
		GL33.glSamplerParameteri(atlasSampler, GL11.GL_TEXTURE_MIN_FILTER,
				useMips ? GL11.GL_NEAREST_MIPMAP_LINEAR : GL11.GL_NEAREST);
		GL33.glSamplerParameteri(atlasSampler, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
		GL33.glSamplerParameteri(atlasSampler, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
		GL33.glSamplerParameteri(atlasSampler, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
		// Per-sampler rather than per-texture: applies only to our terrain pass and never mutates
		// resource-pack atlas state used by vanilla items/entities. Positive bias preserves nearby
		// detail while reducing distant bandwidth/cache pressure from high-resolution block models.
		GL33.glSamplerParameterf(atlasSampler, GL14.GL_TEXTURE_LOD_BIAS, 0f);
		float aniso = useMips ? Math.max(1f, cfg.terrainAnisotropy) : 1f;
		if (maxAnisoCached > 0f) {
			aniso = Math.min(aniso, maxAnisoCached);
		}
		GL33.glSamplerParameterf(atlasSampler, EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT, aniso);
		atlasSamplerConfigKey = configKey;
	}

	/**
	 * Build the terrain point-light uniforms for DYNAMIC sources only (held/dropped light items,
	 * burning entities — things that MOVE each frame). Placed light blocks are no longer here: their
	 * colour is BAKED into the section mesh per vertex by {@link BakedLightTint} (no range/count
	 * limits, zero per-frame cost) and re-bakes via the normal light-update re-mesh path.
	 */
	private static void setTerrainLights(MinecraftClient client, MoneyakShadersConfig cfg) {
		if (uLightCount < 0 || lightPosBuf == null) {
			return;
		}
		if (!cfg.terrainPointLights) {
			GL20.glUniform1i(uLightCount, 0);
			return;
		}
		int cand = 0;
		float[] ed = DynamicLightSources.renderData(client);
		int en = DynamicLightSources.count();
		// Skip the LOCAL player's held light: uHeldLight already fills for it — now that the uLights loop
		// adds dynFill, keeping it here would light the ground around the player twice.
		for (int i = DynamicLightSources.firstIsLocalHeld() ? 1 : 0; i < en && cand < LIGHT_CAND_MAX; i++) {
			System.arraycopy(ed, i * 7, lightCand, cand * 7, 7);
			cand++;
		}
		// dynamic sources are few (hands + burning mobs) — a fixed small cap keeps the FS loop short
		int maxLights = Math.min(MAX_TERRAIN_LIGHTS, 16);
		for (int k = 0; k < maxLights; k++) { bestD2[k] = Float.MAX_VALUE; bestIdx[k] = -1; }
		for (int i = 0; i < cand; i++) {
			float dx = lightCand[i * 7] - (float) capCamX, dy = lightCand[i * 7 + 1] - (float) capCamY, dz = lightCand[i * 7 + 2] - (float) capCamZ;
			float d2 = dx * dx + dy * dy + dz * dz;
			int worst = 0; float worstV = -1f;
			for (int k = 0; k < maxLights; k++) { if (bestD2[k] > worstV) { worstV = bestD2[k]; worst = k; } }
			if (d2 < worstV) { bestD2[worst] = d2; bestIdx[worst] = i; }
		}
		lightPosBuf.clear();
		lightColBuf.clear();
		int chosen = 0;
		for (int k = 0; k < maxLights; k++) {
			int i = bestIdx[k];
			if (i < 0) continue;
			int o = i * 7;
			lightPosBuf.put(lightCand[o] - (float) capCamX).put(lightCand[o + 1] - (float) capCamY).put(lightCand[o + 2] - (float) capCamZ).put(lightCand[o + 3]);
			float scale = lightCand[o + 3] / 15f * 0.25f; // gentle: stacked lights are clamped in the FS too
			lightColBuf.put(lightCand[o + 4] * scale).put(lightCand[o + 5] * scale).put(lightCand[o + 6] * scale);
			chosen++;
		}
		lightPosBuf.flip();
		lightColBuf.flip();
		GL20.glUniform1i(uLightCount, chosen);
		if (chosen > 0) {
			GL20.glUniform4fv(uLights, lightPosBuf);
			GL20.glUniform3fv(uLightCols, lightColBuf);
		}
	}

	/**
	 * ARGB vertex tint for model-rendered block entities. Their vanilla lightmap accepts only scalar
	 * brightness, so without this channel a chest beside a soul lantern becomes brighter but not blue.
	 */
	public static int blockEntityLightTint(BlockPos pos) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null || pos == null || !MoneyakShadersConfig.get().terrainPointLights) return -1;
		return lightTintAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
	}

	/** Shared ARGB colour multiplier for queued entities and block-break particles. */
	public static int lightTintAt(double x, double y, double z) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null || !MoneyakShadersConfig.get().terrainPointLights) return -1;
		float px = (float) x, py = (float) y, pz = (float) z;
		float sumR = 0f, sumG = 0f, sumB = 0f, sumW = 0f;

		float[] dynamic = DynamicLightSources.renderData(client);
		int dynamicCount = DynamicLightSources.count();
		for (int i = 0; i < dynamicCount; i++) {
			int o = i * 7;
			float dx = px - dynamic[o], dy = py - dynamic[o + 1], dz = pz - dynamic[o + 2];
			float range = dynamic[o + 3];
			float d2 = dx * dx + dy * dy + dz * dz;
			if (range <= 0f || d2 >= range * range) continue;
			if (!DynamicLightSources.isVisibleFromSource(i, x, y, z)) continue;
			float att = 1f - d2 / (range * range);
			float weight = att * att;
			sumR += dynamic[o + 4] * weight;
			sumG += dynamic[o + 5] * weight;
			sumB += dynamic[o + 6] * weight;
			sumW += weight;
		}

		int pcx = ((int) Math.floor(px)) >> 4, psy = ((int) Math.floor(py)) >> 4,
				pcz = ((int) Math.floor(pz)) >> 4;
		for (int sx = pcx - 1; sx <= pcx + 1; sx++) {
			for (int sy = psy - 1; sy <= psy + 1; sy++) {
				for (int sz = pcz - 1; sz <= pcz + 1; sz++) {
					Sec section = SECTIONS.get(ChunkSectionPos.asLong(sx, sy, sz));
					if (section == null || section.placedLightCount == 0 || section.placedLights == null) continue;
					for (int source = 0; source < section.placedLightCount; source++) {
						int packed = section.placedLights[source];
						float range = packed >>> 12 & 15;
						int bx = (section.cx << 4) + (packed & 15);
						int by = (section.sy << 4) + (packed >>> 8 & 15);
						int bz = (section.cz << 4) + (packed >>> 4 & 15);
						float dx = px - (bx + 0.5f), dy = py - (by + 0.5f), dz = pz - (bz + 0.5f);
						float d2 = dx * dx + dy * dy + dz * dz;
						if (range <= 0f || d2 >= range * range) continue;
						float att = 1f - d2 / (range * range);
						float weight = att * att;
						sumR += SectionMeshData.placedLightRed(packed) * weight;
						sumG += SectionMeshData.placedLightGreen(packed) * weight;
						sumB += SectionMeshData.placedLightBlue(packed) * weight;
						sumW += weight;
					}
				}
			}
		}
		if (sumW <= 0.004f) return -1;
		float peak = Math.max(sumR, Math.max(sumG, sumB));
		if (peak <= 0f) return -1;
		float strength = Math.min(0.24f, sumW * 0.24f);
		int r = Math.min(255, Math.max(0, Math.round(255f * ((1f - strength) + strength * sumR / peak))));
		int g = Math.min(255, Math.max(0, Math.round(255f * ((1f - strength) + strength * sumG / peak))));
		int b = Math.min(255, Math.max(0, Math.round(255f * ((1f - strength) + strength * sumB / peak))));
		return 0xFF000000 | r << 16 | g << 8 | b;
	}

	/** Per-pass: time/wind + day-night tint + held dynamic light + ore glow uniforms. */
private static void setEffectUniforms(MinecraftClient client, MoneyakShadersConfig cfg) {
		GL20.glUniform1f(uTime, (System.currentTimeMillis() - shaderStartMs) / 1000.0f);
		GL20.glUniform1f(uWind, cfg.windSway ? 0.06f : 0.0f);
		GL20.glUniform1f(uOreGlow, cfg.glowingOres ? 0.55f : 0.0f);
		bindSceneLightingUniforms();
		if (cfg.dynamicHeldLight && client.player != null) {
			heldLight(client, HELD_LIGHT);
			GL20.glUniform3f(uHeldLight, HELD_LIGHT[0], HELD_LIGHT[1], HELD_LIGHT[2]);
			GL20.glUniform1f(uHeldRadius, HELD_LIGHT[3]);
		} else GL20.glUniform1f(uHeldRadius, 0f);
	}

	/**
	 * Day/night colour grade (multiplier around 1.0 — the lightmap already handles brightness, this
	 * only shifts hue). CONTINUOUS across the horizon (no hard day/night branch, which used to SNAP
	 * the colour at dawn/dusk): night→day is a single smoothstep, with a warm golden-hour glow that
	 * peaks while the sun sits near the horizon and fades out by full day and deep night. Night is
	 * cool, lifted a touch on full-moon nights.
	 */
private static void dayNightTint(long timeOfDay, float[] out) {
		Vector3f c = SCENE_LIGHTING.activeColor();
		out[0] = c.x; out[1] = c.y; out[2] = c.z;
	}

	/** {r,g,b,radius} of a light-emitting block held in either hand (radius 0 = nothing held). */
	private static void heldLight(MinecraftClient client, float[] out) {
		DynamicLightSources.localHeldLight(client, out);
	}

	private static float[] lightColor(net.minecraft.block.Block b) {
		// Delegated to the central spec §8.1 registry so every subsystem (held light, terrain
		// point lights, entity dynamic lighting, shadow-map hero-light selection) sees the same
		// colour for the same block. Callers must not mutate the returned array — treat as const.
		return PointLightRegistry.rgb(b);
	}

	// --- sun/moon shadow mapping ---

	private static final int CLAMP_TO_BORDER = 0x812D;
	/** Core OpenGL shadow-comparison enums (kept local for compatibility with the mapped GL bindings). */
	private static final int TEXTURE_COMPARE_MODE = 0x884C;
	private static final int TEXTURE_COMPARE_FUNC = 0x884D;
	private static final int COMPARE_REF_TO_TEXTURE = 0x884E;

	/** (Re)create all directional cascade depth/transmission targets independently. */
	private static void ensureShadowTargets() {
		int maxTex = maxTextureSize();
		int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
		int prevFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
		for (int i = 0; i < DIRECTIONAL_CASCADE_COUNT; i++) {
			int cap = i == ShadowCascadeLayout.NEAR ? NEAR_SHADOW_RES_CAP : i == ShadowCascadeLayout.MID ? MID_SHADOW_RES_CAP : FAR_SHADOW_RES_CAP;
			int res = Math.max(512, Math.min(cap, SHADOW_LAYOUT.resolution[i]));
			if (maxTex > 0) res = Math.min(res, maxTex);
			if (shadowFbo[i] != 0 && shadowTex[i] != 0 && shadowWaterTex[i] != 0 && shadowRes[i] == res) continue;
			directionalShadowReady[i] = false;
			directionalShadowDirty[i] = true;
			SHADOW_ORIGINS[i].reset();
			shadowRes[i] = res;
			if (shadowFbo[i] == 0) shadowFbo[i] = GL30.glGenFramebuffers();
			if (shadowTex[i] == 0) shadowTex[i] = GL11.glGenTextures();
			if (shadowWaterTex[i] == 0) shadowWaterTex[i] = GL11.glGenTextures();

			GlStateManager._bindTexture(shadowTex[i]);
			GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL14.GL_DEPTH_COMPONENT24, res, res, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (ByteBuffer)null);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, TEXTURE_COMPARE_MODE, COMPARE_REF_TO_TEXTURE);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, TEXTURE_COMPARE_FUNC, GL11.GL_LEQUAL);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, CLAMP_TO_BORDER);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, CLAMP_TO_BORDER);
			GL11.glTexParameterfv(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_BORDER_COLOR, SHADOW_DEPTH_BORDER);

			GlStateManager._bindTexture(shadowWaterTex[i]);
			GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, res, res, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer)null);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, CLAMP_TO_BORDER);
			GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, CLAMP_TO_BORDER);
			GL11.glTexParameterfv(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_BORDER_COLOR, SHADOW_WATER_BORDER);

			GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, shadowFbo[i]);
			GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, shadowTex[i], 0);
			GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, shadowWaterTex[i], 0);
			GL11.glDrawBuffer(GL11.GL_NONE);
			GL11.glReadBuffer(GL11.GL_NONE);
			int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
			if (status != GL30.GL_FRAMEBUFFER_COMPLETE) MoneyakShaders.LOGGER.error("[Plan C/GL] shadow FBO {} incomplete: 0x{}", i, Integer.toHexString(status));
		}
		GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
		GlStateManager._bindTexture(prevTex);
	}
	/**
	 * Hard ceiling on a shadow map's edge length, regardless of what the config asks for.
	 *
	 * <p>A depth texture costs {@code res² × 4} bytes: 4096 is 67 MB, 8192 is 268 MB and 16384 is a
	 * full gigabyte. The near cascade is the only map allowed to reach 8192: it covers the player's
	 * contact shadows and is never adaptively downscaled. The wider far cascade stays capped at 4096
	 * to prevent excessive VRAM and fill-rate pressure.
	 */
	private static final int NEAR_SHADOW_RES_CAP = 8192;
	private static final int MID_SHADOW_RES_CAP = 4096;
	private static final int FAR_SHADOW_RES_CAP = 4096;

	public static int postShadowNearTexture() { return directionalShadowReady[0] ? shadowTex[0] : 0; }

	public static int postShadowFarTexture() { return directionalShadowReady[2] ? shadowTex[2] : 0; }

	public static int postShadowMidTexture() { return directionalShadowReady[1] ? shadowTex[1] : 0; }
	public static int postShadowWaterNearTexture() { return directionalShadowReady[0] ? shadowWaterTex[0] : 0; }
	public static int postShadowWaterMidTexture() { return directionalShadowReady[1] ? shadowWaterTex[1] : 0; }
	public static int postShadowWaterFarTexture() { return directionalShadowReady[2] ? shadowWaterTex[2] : 0; }

	public static boolean postCameraMatrices(Matrix4f projection, Matrix4f view) {
		if (!haveMatrix || projection == null || view == null) return false;
		projection.set(capturedProjection); view.set(capturedModelView); return true;
	}

	public static boolean postShadowMatrices(Matrix4f near, Matrix4f far) {
		if (!directionalShadowReady[0] || !directionalShadowReady[2]) return false;
		near.set(lightMx[0]);
		far.set(lightMx[2]);
		return true;
	}

	public static void postShadowOffsets(Vector3f near, Vector3f far) {
		near.set((float)(capCamX - shadowCamX[0]), (float)(capCamY - shadowCamY[0]), (float)(capCamZ - shadowCamZ[0]));
		far.set((float)(capCamX - shadowCamX[2]), (float)(capCamY - shadowCamY[2]), (float)(capCamZ - shadowCamZ[2]));
	}

	public static boolean postShadowMatrices(Matrix4f near, Matrix4f mid, Matrix4f far) {
		if (!allDirectionalShadowsReady()) return false;
		near.set(lightMx[0]); mid.set(lightMx[1]); far.set(lightMx[2]);
		return true;
	}

	public static void postShadowOffsets(Vector3f near, Vector3f mid, Vector3f far) {
		near.set((float)(capCamX - shadowCamX[0]), (float)(capCamY - shadowCamY[0]), (float)(capCamZ - shadowCamZ[0]));
		mid.set((float)(capCamX - shadowCamX[1]), (float)(capCamY - shadowCamY[1]), (float)(capCamZ - shadowCamZ[1]));
		far.set((float)(capCamX - shadowCamX[2]), (float)(capCamY - shadowCamY[2]), (float)(capCamZ - shadowCamZ[2]));
	}

	/** World-space celestial state shared by terrain, clouds and volumetric post. */
	public static boolean postCelestial(float[] out) {
		if (out == null || out.length < 8 || SCENE_LIGHTING.activeDirectStrength() <= 0.001f) return false;
		Vector3f dir = SCENE_LIGHTING.activeDirection();
		Vector3f color = SCENE_LIGHTING.activeColor();
		out[0] = dir.x; out[1] = dir.y; out[2] = dir.z; out[3] = SCENE_LIGHTING.activeDirectStrength();
		out[4] = SCENE_LIGHTING.moonLighting ? 1f : 0f; out[5] = color.x; out[6] = color.y; out[7] = color.z;
		return true;
	}

	private static int pointShadowBaseResolution(
			MoneyakShadersConfig cfg) {

		int res =
				Math.max(
						256,
						cfg.pointShadowResolution);

		int maxTex = maxTextureSize();

		if (maxTex > 0) {
			res = Math.min(
					res,
					Math.min(
							maxTex / 3,
							maxTex / (2 * POINT_MAX)));
		}

		while (res > 256
				&& (long) res
						* res
						* 6L
						* POINT_MAX
						> POINT_SHADOW_ATLAS_PIXEL_BUDGET) {

			res >>= 1;
		}

		return Math.max(256, res);
	}

	/**
	 * Resolves one sun cascade. The near map is a visual contract around the camera: adaptive
	 * quality may save GPU work on the distant cascade, but must never make a block-edge shadow
	 * beneath the player blur merely because the user increased shadow distance.
	 */








	private static void updateAdaptiveShadowQuality(MoneyakShadersConfig cfg) {
		SHADOW_LAYOUT.update(cfg, 0);
		int floor = Math.max(512, cfg.shadowMinResolution);
		double measured = GPU_PROFILER.workloadMs("shadow-near") + GPU_PROFILER.workloadMs("shadow-mid") + GPU_PROFILER.workloadMs("shadow-far");
		double budget = Math.max(1.0, cfg.shadowGpuBudgetMs - GPU_PROFILER.workloadMs("shadow-point"));
		adaptiveShadowLevel = SUN_QUALITY.update(System.nanoTime() / 1_000_000L, cfg.adaptiveShadowQuality && cfg.sunShadows, measured, budget,
				AdaptiveShadowQuality.maxLevel(SHADOW_LAYOUT.resolution, floor));
	}

	/** Point-light cubemaps have a separate budget from sun cascades: lowering one must not blur the other. */
	private static void updateAdaptivePointShadowQuality(
			MoneyakShadersConfig cfg) {

		double budget =
				Math.max(
						1.0,
						cfg.shadowGpuBudgetMs
								* 0.60);

		double amortized =
				GPU_PROFILER.workloadMs(
						"shadow-point");

		double invocation =
				Math.max(
						GPU_PROFILER.averageMs(
								"shadow-point"),
						GPU_PROFILER.latestMs(
								"shadow-point"));

		/*
		* Amortized workload describes steady GPU pressure.
		*
		* But point-shadow rebuilding is bursty. Keep part of the individual-pass
		* cost in the signal so a 6 ms rebuild every few frames cannot hide behind
		* a deceptively cheap amortized average and repeatedly break VSync.
		*/
		double measured =
				Math.max(
						amortized,
						invocation * 0.65);

		int requested =
				pointShadowBaseResolution(cfg);

		adaptivePointShadowLevel =
				POINT_QUALITY.update(
						System.nanoTime()
								/ 1_000_000L,
						cfg.adaptiveShadowQuality
								&& cfg.pointLightShadows,
						measured,
						budget,
						AdaptiveShadowQuality.maxLevel(
								requested,
								256));
	}

	// ---- hero point-light shadow -------------------------------------------------------------------

	private static int heroScanCooldown;
	// candidate scratch (dynamic sources + scanned placed blocks) for the slot assignment
	private static final int CAND_MAX = 48;
	private static final float[] candX = new float[CAND_MAX], candY = new float[CAND_MAX],
			candZ = new float[CAND_MAX], candLvl = new float[CAND_MAX], candScore = new float[CAND_MAX],
			candR = new float[CAND_MAX], candG = new float[CAND_MAX], candB = new float[CAND_MAX];
	private static final int[] candFaceMask = new int[CAND_MAX];
	private static final boolean[] candDyn = new boolean[CAND_MAX], candEmbedded = new boolean[CAND_MAX],
			candSolid = new boolean[CAND_MAX],
			candInfluenceVisible = new boolean[CAND_MAX],
			candTarget = new boolean[CAND_MAX],
			candUsed = new boolean[CAND_MAX];
	private static final float POINT_VISIBLE_PRIORITY = 4096f;
	// throttled placed-light scan results (top-N blocks around the player)
	private static final int SCAN_MAX = 32;
	private static final float[] scanPX = new float[SCAN_MAX], scanPY = new float[SCAN_MAX],
			scanPZ = new float[SCAN_MAX], scanPLvl = new float[SCAN_MAX], scanPScore = new float[SCAN_MAX],
			scanPR = new float[SCAN_MAX], scanPG = new float[SCAN_MAX], scanPB = new float[SCAN_MAX];
	private static final boolean[] scanPSolid = new boolean[SCAN_MAX];
	private static final int[] scanPFaceMask = new int[SCAN_MAX];
	private static int scanPN;
	// Published section meshes carry their own luminous-block metadata. Querying that compact registry
	// every few frames replaces the old 31^3 live-world getBlockState scan and reaches far enough that a
	// visible lamp pool already has its map before the player approaches it.
	// Registry discovery follows the complete resident render radius. A smaller fixed radius made
	// already-visible lamps/point shadows suddenly "activate" when the player crossed that boundary.
	private static final int POINT_REGISTRY_RADIUS_SECTIONS = RADIUS_CAP;
	private static int scanOriginX, scanOriginY, scanOriginZ;
	private static long pointSelectionLastNs;
	// Density remains diagnostic only; it must not suppress selected point shadows.
	private static int scanSatCount;
	private static float pointSatFactor = 1f;
	// per-slot map bookkeeping: position the slot's map was last rendered from (NaN = needs render)
	private static final float[] pRenderX = new float[POINT_MAX], pRenderY = new float[POINT_MAX], pRenderZ = new float[POINT_MAX];
	private static final boolean[] pMapDirty = new boolean[POINT_MAX];
	/** Monotonic caster/source generation. A phased six-face build may only clear the generation it captured. */
	private static final long[] pMapRevision = new long[POINT_MAX];
	private static final boolean[] pMapMotionDirty = new boolean[POINT_MAX];
	private static final long[] pEntityMotionFingerprint = new long[POINT_MAX];
	private static final long[] pMapDirtyReadyNs = new long[POINT_MAX];
	private static final long[] pMapLastRenderNs = new long[POINT_MAX];
	private static final boolean[] pEntityMapValid = new boolean[POINT_MAX];
	static {
		java.util.Arrays.fill(pRenderX, Float.NaN);
		java.util.Arrays.fill(pEntityMotionFingerprint, Long.MIN_VALUE);
	}
	private static final boolean[] needRender = new boolean[POINT_MAX];
	private static final boolean[] needEntityRender = new boolean[POINT_MAX];
	private static int staticPointRenderCursor;
	/** Static terrain cubemaps are rebuilt two faces per frame instead of one six-face GPU burst. */
	private static int pointTerrainBuildSlot = -1;
	private static int pointTerrainBuildNextFace;
	private static long pointTerrainBuildRevision;
	private static float pointTerrainBuildX, pointTerrainBuildY, pointTerrainBuildZ, pointTerrainBuildRange;
	private static boolean pointTerrainBuildSolid;
	private static final Matrix4f[] pointTerrainBuildMatrices = new Matrix4f[6];
	static {
		for (int i = 0; i < pointTerrainBuildMatrices.length; i++) pointTerrainBuildMatrices[i] = new Matrix4f();
	}

	private static int maxTextureSize() {
		if (cachedMaxTextureSize <= 0) {
			int value = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);

			if (value > 0) {
				cachedMaxTextureSize = value;
			}

			return value;
		}

		return cachedMaxTextureSize;
	}

	/** (Re)create the point-light depth atlas: POINT_MAX stacked 3×2 cube-face blocks in one texture/FBO. */
	private static void ensurePointShadowTarget(int res) {
		int maxTex = maxTextureSize();
		if (maxTex > 0) {
			res = Math.min(res, Math.min(maxTex / 3, maxTex / (2 * POINT_MAX)));
		}
		int entityRes = Math.max(256, res >> 1);
		if (pointShadowFbo != 0 && pointEntityShadowFbo != 0
				&& pointShadowRes == res && pointEntityShadowRes == entityRes) {
			return;
		}
		int oldTerrainFbo = pointShadowFbo, oldTerrainTex = pointShadowTex, oldRes = pointShadowRes;
		int oldEntityFbo = pointEntityShadowFbo, oldEntityTex = pointEntityShadowTex, oldEntityRes = pointEntityShadowRes;
		boolean preserve = oldTerrainFbo != 0 && oldEntityFbo != 0 && oldRes > 0 && oldEntityRes > 0;
		// Adaptation changes sampling density, not ownership or visibility. Keep complete maps until
		// their normal budgeted refresh replaces them, instead of fading every lamp out on resize.
		if (!preserve) java.util.Arrays.fill(pRenderX, Float.NaN);
		java.util.Arrays.fill(pMapDirty, true);
		for (int slot = 0; slot < POINT_MAX; slot++) pMapRevision[slot]++;
		java.util.Arrays.fill(pMapMotionDirty, false);
		java.util.Arrays.fill(pEntityMotionFingerprint, Long.MIN_VALUE);
		java.util.Arrays.fill(pMapDirtyReadyNs, 0L);
		java.util.Arrays.fill(pMapLastRenderNs, 0L);
		java.util.Arrays.fill(pEntityMapValid, false);
		if (!preserve) java.util.Arrays.fill(pLightFade, 0f);
		pointTerrainBuildSlot = -1;
		pointTerrainBuildNextFace = 0;
		pointTerrainBuildRevision = 0L;
		pointShadowRes = res;
		pointEntityShadowRes = entityRes;
		int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
		int prevFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
		int prevReadFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
		pointShadowFbo = pointShadowTex = pointEntityShadowFbo = pointEntityShadowTex = 0;
		if (pointShadowFbo == 0) pointShadowFbo = GL30.glGenFramebuffers();
		if (pointShadowTex == 0) pointShadowTex = GL11.glGenTextures();
		GlStateManager._bindTexture(pointShadowTex);
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL14.GL_DEPTH_COMPONENT24, res * 3, res * 2 * POINT_MAX, 0,
				GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (java.nio.ByteBuffer) null);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, CLAMP_TO_BORDER);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, CLAMP_TO_BORDER);
		GL11.glTexParameterfv(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_BORDER_COLOR, new float[] { 1f, 1f, 1f, 1f });
		GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, pointShadowFbo);
		GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, pointShadowTex, 0);
		GL11.glDrawBuffer(GL11.GL_NONE);
		GL11.glReadBuffer(GL11.GL_NONE);

		if (pointEntityShadowFbo == 0) pointEntityShadowFbo = GL30.glGenFramebuffers();
		if (pointEntityShadowTex == 0) pointEntityShadowTex = GL11.glGenTextures();
		GlStateManager._bindTexture(pointEntityShadowTex);
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL14.GL_DEPTH_COMPONENT24,
				entityRes * 3, entityRes * 2 * POINT_MAX, 0,
				GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (java.nio.ByteBuffer) null);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, CLAMP_TO_BORDER);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, CLAMP_TO_BORDER);
		GL11.glTexParameterfv(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_BORDER_COLOR, new float[] { 1f, 1f, 1f, 1f });
		GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, pointEntityShadowFbo);
		GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
				GL11.GL_TEXTURE_2D, pointEntityShadowTex, 0);
		GL11.glDrawBuffer(GL11.GL_NONE);
		GL11.glReadBuffer(GL11.GL_NONE);
		if (preserve) {
			boolean scissor = RenderGlState.scissorEnabled();
			GlStateManager._disableScissorTest();
			try {
				copyPointShadowFaces(oldTerrainFbo, pointShadowFbo, oldRes, res);
				copyPointShadowFaces(oldEntityFbo, pointEntityShadowFbo, oldEntityRes, entityRes);
			} finally {
				if (scissor) GlStateManager._enableScissorTest();
			}
		}
		int restoreDraw = prevFbo == oldTerrainFbo && oldTerrainFbo != 0 ? pointShadowFbo
				: prevFbo == oldEntityFbo && oldEntityFbo != 0 ? pointEntityShadowFbo : prevFbo;
		int restoreRead = prevReadFbo == oldTerrainFbo && oldTerrainFbo != 0 ? pointShadowFbo
				: prevReadFbo == oldEntityFbo && oldEntityFbo != 0 ? pointEntityShadowFbo : prevReadFbo;
		GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, restoreDraw);
		GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, restoreRead);
		GlStateManager._bindTexture(prevTex == oldTerrainTex && oldTerrainTex != 0 ? pointShadowTex
				: prevTex == oldEntityTex && oldEntityTex != 0 ? pointEntityShadowTex : prevTex);
		if (oldTerrainFbo != 0) GL30.glDeleteFramebuffers(oldTerrainFbo);
		if (oldEntityFbo != 0) GL30.glDeleteFramebuffers(oldEntityFbo);
		if (oldTerrainTex != 0) GlStateManager._deleteTexture(oldTerrainTex);
		if (oldEntityTex != 0) GlStateManager._deleteTexture(oldEntityTex);
	}

	private static void copyPointShadowFaces(int from, int to, int oldRes, int newRes) {
		GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, from);
		GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, to);
		PointShadowAtlasCopy.copy(oldRes, newRes, POINT_MAX);
	}

	/** Allocate the multi-slot integer occupancy atlas used only by full-cube emitters. */
	private static void ensurePointVoxelTarget() {
		if (pointVoxelTex != 0) return;
		int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
		pointVoxelTex = GL11.glGenTextures();
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, pointVoxelTex);
		GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R8UI,
				POINT_VOXEL_SIZE * POINT_VOXEL_SIZE, POINT_VOXEL_SIZE * POINT_MAX, 0,
				GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
		GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
		java.util.Arrays.fill(pVoxelValid, false);
	}

	/**
	 * Publish a 32^3 full-cube occupancy volume around one emitter. The source sections already own
	 * immutable 4096-bit masks produced by the mesh workers, so this performs no world reads and no
	 * allocations on the render thread. Each Z slice occupies 32 adjacent X texels in a 2D atlas.
	 */
	private static void uploadPointVoxelSlot(int slot) {
		int originX = (int) Math.floor(pLightX[slot]) - (POINT_VOXEL_SIZE >> 1);
		int originY = (int) Math.floor(pLightY[slot]) - (POINT_VOXEL_SIZE >> 1);
		int originZ = (int) Math.floor(pLightZ[slot]) - (POINT_VOXEL_SIZE >> 1);
		POINT_VOXEL_UPLOAD.clear();
		while (POINT_VOXEL_UPLOAD.hasRemaining()) POINT_VOXEL_UPLOAD.put((byte) 0);

		int maxX = originX + POINT_VOXEL_SIZE - 1;
		int maxY = originY + POINT_VOXEL_SIZE - 1;
		int maxZ = originZ + POINT_VOXEL_SIZE - 1;
		for (int sx = originX >> 4; sx <= maxX >> 4; sx++) {
			int sectionX = sx << 4;
			int fromX = Math.max(originX, sectionX), toX = Math.min(maxX, sectionX + 15);
			for (int sy = originY >> 4; sy <= maxY >> 4; sy++) {
				int sectionY = sy << 4;
				int fromY = Math.max(originY, sectionY), toY = Math.min(maxY, sectionY + 15);
				for (int sz = originZ >> 4; sz <= maxZ >> 4; sz++) {
					Sec section = SECTIONS.get(ChunkSectionPos.asLong(sx, sy, sz));
					if (section == null || section.residency != Sec.Residency.ACTIVE
							|| section.opaqueVoxels == null) continue;
					int sectionZ = sz << 4;
					int fromZ = Math.max(originZ, sectionZ), toZ = Math.min(maxZ, sectionZ + 15);
					long[] bits = section.opaqueVoxels;
					for (int wx = fromX; wx <= toX; wx++) {
						int localX = wx & 15, vx = wx - originX;
						for (int wy = fromY; wy <= toY; wy++) {
							int localY = wy & 15, vy = wy - originY;
							for (int wz = fromZ; wz <= toZ; wz++) {
								int localZ = wz & 15;
								int sectionIndex = localX << 8 | localY << 4 | localZ;
								if ((bits[sectionIndex >>> 6] & 1L << (sectionIndex & 63)) == 0L) continue;
								int vz = wz - originZ;
								POINT_VOXEL_UPLOAD.put(vy * POINT_VOXEL_SIZE * POINT_VOXEL_SIZE
										+ vz * POINT_VOXEL_SIZE + vx,
										(byte) 1);
							}
						}
					}
				}
			}
		}
		POINT_VOXEL_UPLOAD.position(0).limit(POINT_VOXEL_VOLUME);
		int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
		int prevUnpackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
		int prevAlignment = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
		int prevRowLength = GL11.glGetInteger(GL12.GL_UNPACK_ROW_LENGTH);
		int prevSkipRows = GL11.glGetInteger(GL12.GL_UNPACK_SKIP_ROWS);
		int prevSkipPixels = GL11.glGetInteger(GL12.GL_UNPACK_SKIP_PIXELS);
		try {
			// Minecraft's atlas uploader is allowed to leave pixel-store row/skip state and a PBO
			// binding behind. The ByteBuffer overload then becomes an offset into that PBO, or the
			// driver reads past this 32 KiB buffer. AMD handles that invalid read as a native crash.
			GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
			GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
			GL11.glPixelStorei(GL12.GL_UNPACK_ROW_LENGTH, 0);
			GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_ROWS, 0);
			GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_PIXELS, 0);
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, pointVoxelTex);
			GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, slot * POINT_VOXEL_SIZE,
					POINT_VOXEL_SIZE * POINT_VOXEL_SIZE, POINT_VOXEL_SIZE,
					GL30.GL_RED_INTEGER, GL11.GL_UNSIGNED_BYTE, POINT_VOXEL_UPLOAD);
		} finally {
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
			GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, prevAlignment);
			GL11.glPixelStorei(GL12.GL_UNPACK_ROW_LENGTH, prevRowLength);
			GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_ROWS, prevSkipRows);
			GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_PIXELS, prevSkipPixels);
			GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, prevUnpackBuffer);
		}
		pVoxelOriginX[slot] = originX;
		pVoxelOriginY[slot] = originY;
		pVoxelOriginZ[slot] = originZ;
		pVoxelValid[slot] = true;
	}

	private static boolean pointVoxelOriginMatches(int slot) {
		return pVoxelValid[slot]
				&& pVoxelOriginX[slot] == (int) Math.floor(pLightX[slot]) - (POINT_VOXEL_SIZE >> 1)
				&& pVoxelOriginY[slot] == (int) Math.floor(pLightY[slot]) - (POINT_VOXEL_SIZE >> 1)
				&& pVoxelOriginZ[slot] == (int) Math.floor(pLightZ[slot]) - (POINT_VOXEL_SIZE >> 1);
	}

	/**
	 * Assign the strongest nearby lights (dynamic held/burning/dropped + a throttled placed-block scan)
	 * to the {@link #POINT_MAX} shadow slots. SLOT-STABLE: a light that already owns a slot keeps it as
	 * long as it is still a candidate (matched by proximity), so several competing sources never make
	 * the shadows strobe — the old single-hero pick relaid every shadow whenever the winner changed.
	 * Anchored to the PLAYER (never the camera — the third-person camera orbits with the view).
	 */
	private static void selectPointLights(MinecraftClient client) {
		// Head rotation must not change which lamps occupy the finite shadow slots.  Use the player's
		// stable position; third-person and looking around therefore cannot brighten/darken the room.
		double cx = client.player.getX();
		double cy = client.player.getEyeY();
		double cz = client.player.getZ();
		long nowNs = System.nanoTime();
		float dt = pointSelectionLastNs == 0L ? 1f / 60f
				: Math.min(0.10f, Math.max(0f, (nowNs - pointSelectionLastNs) * 1.0e-9f));
		pointSelectionLastNs = nowNs;
		// gather candidates: dynamic sources + scanned placed blocks
		int nc = 0;
		float[] d = DynamicLightSources.renderData(client);
		boolean[] embeddedSources = DynamicLightSources.embeddedSources();
		int n = DynamicLightSources.count();
		for (int i = 0; i < n && nc < CAND_MAX - SCAN_MAX; i++) {
			int o = i * 7;
			if (d[o + 3] < 6f) {
				continue;
			}
			candX[nc] = d[o]; candY[nc] = d[o + 1]; candZ[nc] = d[o + 2];
			candLvl[nc] = d[o + 3]; candDyn[nc] = true;
			candEmbedded[nc] = i < embeddedSources.length && embeddedSources[i];
			candSolid[nc] = false;
			candFaceMask[nc] = 0x3F;
			float colorScale = d[o + 3] / 15f * 0.25f;
			// Local held, remote held and dropped sources now share the same visibility-gated direct
			// path. uHeldLight remains only as the no-point-shadow configuration fallback.
			candR[nc] = d[o + 4] * colorScale;
			candG[nc] = d[o + 5] * colorScale;
			candB[nc] = d[o + 6] * colorScale;
			nc++;
		}
		if (--heroScanCooldown <= 0 || Math.abs(cx - scanOriginX) > 4.0 || Math.abs(cy - scanOriginY) > 4.0
				|| Math.abs(cz - scanOriginZ) > 4.0) {
			refreshRegisteredPlacedLights(cx, cy, cz);
		}
		for (int i = 0; i < scanPN && nc < CAND_MAX; i++) {
			candX[nc] = scanPX[i]; candY[nc] = scanPY[i]; candZ[nc] = scanPZ[i];
			candLvl[nc] = scanPLvl[i]; candDyn[nc] = false; candEmbedded[nc] = false;
			candSolid[nc] = scanPSolid[i];
			candFaceMask[nc] = scanPFaceMask[i];
			candR[nc] = scanPR[i]; candG[nc] = scanPG[i]; candB[nc] = scanPB[i];
			nc++;
		}
		for (int i = 0; i < nc; i++) {
			float dx = candX[i] - (float) cx, dy = candY[i] - (float) cy, dz = candZ[i] - (float) cz;
			candInfluenceVisible[i] = pointLightInfluenceVisible(
					candX[i], candY[i], candZ[i], Math.max(6f, candLvl[i]));
			// A player can see a lamp's pool from far outside its physical radius. Prefer every source whose
			// influence sphere touches submitted terrain, then use player distance only to break ties. This
			// prepares its retained cubemap before approaching instead of activating the shadow at the player.
			candScore[i] = candLvl[i] - (float) Math.sqrt(dx * dx + dy * dy + dz * dz)
					+ (candInfluenceVisible[i] ? POINT_VISIBLE_PRIORITY : 0f);
			candUsed[i] = false;
			candTarget[i] = false;
		}
		// Reserve one of the finite slots for the best placed source before applying the strong dynamic
		// priority. Otherwise four dropped/held sources can permanently evict the torch or lantern that
		// lights the room, so terrain keeps its vanilla brightness but has no block-source shadow at all.
		// The remaining slots stay global and dynamic-first, preserving responsive held/entity lighting.
		int selected = 0;
		int bestPlaced = -1;
		float bestPlacedPriority = -Float.MAX_VALUE;
		for (int i = 0; i < nc; i++) {
			if (candDyn[i] || candScore[i] <= POINT_STATIC_ENTER_SCORE) continue;
			float priority = candScore[i];
			for (int slot = 0; slot < POINT_MAX; slot++) {
				if (pLightValid[slot] && !pLightDyn[slot]
						&& sq(candX[i] - pLightX[slot]) + sq(candY[i] - pLightY[slot])
								+ sq(candZ[i] - pLightZ[slot]) < 2.25f) {
					priority += 4f;
					break;
				}
			}
			if (priority > bestPlacedPriority) {
				bestPlacedPriority = priority;
				bestPlaced = i;
			}
		}
		if (bestPlaced >= 0) {
			candTarget[bestPlaced] = true;
			selected = 1;
		}
		int selectedDynamic = 0;
		int placedCandidates = 0;
		for (int i = 0; i < nc; i++) if (!candDyn[i]) placedCandidates++;
		int dynamicLimit = Math.max(2, POINT_MAX - placedCandidates);
		for (int pick = selected; pick < POINT_MAX; pick++) {
			int best = -1;
			float bestPriority = -Float.MAX_VALUE;
			for (int i = 0; i < nc; i++) {
				float enterScore = candDyn[i] ? POINT_DYNAMIC_ENTER_SCORE : POINT_STATIC_ENTER_SCORE;
				if (candTarget[i] || candScore[i] <= enterScore
						|| candDyn[i] && selectedDynamic >= dynamicLimit) continue;
				float priority = candScore[i] + (candDyn[i] ? 256f : 0f);
				for (int slot = 0; slot < POINT_MAX; slot++) {
					if (pLightValid[slot] && sq(candX[i] - pLightX[slot]) + sq(candY[i] - pLightY[slot])
							+ sq(candZ[i] - pLightZ[slot]) < 2.25f) {
						priority += 4f;
						break;
					}
				}
				if (priority > bestPriority) {
					bestPriority = priority;
					best = i;
				}
			}
			if (best < 0) break;
			candTarget[best] = true;
			if (candDyn[best]) selectedDynamic++;
		}
		// pFadeTarget[s]: where each slot's fade should head this pass (1 = casting, 0 = retiring/empty).
		float[] pFadeTarget = POINT_FADE_TARGET;
		java.util.Arrays.fill(pFadeTarget, 0f);
		// Phase 1 — continuity WITH hysteresis: each occupied slot re-claims its nearest candidate (≤1.5
		// blocks; a held light moves fast). It KEEPS the light while that candidate still scores above the
		// wide EXIT threshold — a source hovering at the edge no longer toggles in/out (that toggling was
		// the "shadows appear then vanish" as you walk past the range limit). Losing the light only starts a
		// smooth fade-out; the slot (and its map) stay until the fade reaches zero.
		for (int s = 0; s < POINT_MAX; s++) {
			if (!pLightValid[s]) {
				continue;
			}
			int best = -1;
			float bestD2 = 2.25f; // 1.5-block match radius
			for (int i = 0; i < nc; i++) {
				if (candUsed[i] || !candTarget[i]) {
					continue;
				}
				float d2 = sq(candX[i] - pLightX[s]) + sq(candY[i] - pLightY[s]) + sq(candZ[i] - pLightZ[s]);
				if (d2 < bestD2) {
					bestD2 = d2;
					best = i;
				}
			}
			if (best >= 0) {
				float oldX = pLightX[s], oldY = pLightY[s], oldZ = pLightZ[s];
				float oldRange = pLightRange[s];
				boolean oldDynamic = pLightDyn[s];
				boolean sourceKindChanged = pLightSolid[s] != candSolid[best];
				pLightX[s] = candX[best]; pLightY[s] = candY[best]; pLightZ[s] = candZ[best];
				pLightRange[s] = Math.max(6f, candLvl[best]);
				pLightDyn[s] = candDyn[best];
				pLightEmbedded[s] = candEmbedded[best];
				pLightSolid[s] = candSolid[best];
				pLightFaceMask[s] = candFaceMask[best];
				pLightR[s] = candR[best]; pLightG[s] = candG[best]; pLightB[s] = candB[best];
				boolean sourceChanged = sourceKindChanged || oldDynamic != candDyn[best]
						|| sq(oldX - candX[best]) + sq(oldY - candY[best]) + sq(oldZ - candZ[best]) > 1.0e-6f
						|| Math.abs(oldRange - Math.max(6f, candLvl[best])) > 1.0e-4f;
				if (sourceChanged) {
					pRenderX[s] = Float.NaN;
					pMapDirty[s] = true;
					pMapRevision[s]++;
					pMapDirtyReadyNs[s] = 0L;
					pEntityMapValid[s] = false;
					pVoxelValid[s] = false;
					pLightFade[s] = 0f;
				}
				candUsed[best] = true;
				pFadeTarget[s] = 1f; // still lit → ramp/hold at full
			}
			// else: light gone or past the exit band → leave pFadeTarget at 0 (retire, fade out).
		}
		// Phase 2 — fill genuinely FREE slots (empty, or a retiring one that has finished fading out) with the
		// strongest fresh candidate scoring above the ENTER threshold. A new light starts at fade 0 and ramps
		// in, so it never pops the room dark on the first frame it appears.
		for (int s = 0; s < POINT_MAX; s++) {
			if (pLightValid[s] && pFadeTarget[s] > 0f) {
				continue; // owned and staying
			}
			boolean urgentDynamicWaiting = false;
			for (int i = 0; i < nc; i++) {
				if (candTarget[i] && !candUsed[i] && candDyn[i]
						&& candScore[i] > POINT_DYNAMIC_ENTER_SCORE) {
					urgentDynamicWaiting = true;
					break;
				}
			}
			if (pLightValid[s] && pLightFade[s] > 0.02f
					&& (pLightDyn[s] || !urgentDynamicWaiting)) {
				continue; // ordinary retiring owners finish fading; a new dynamic source may preempt static
			}
			int best = -1;
			float bestPriority = -Float.MAX_VALUE;
			for (int i = 0; i < nc; i++) {
				float enterScore = candDyn[i] ? POINT_DYNAMIC_ENTER_SCORE : POINT_STATIC_ENTER_SCORE;
				float priority = candScore[i] + (candDyn[i] ? 256f : 0f);
				if (candTarget[i] && !candUsed[i] && candScore[i] > enterScore && priority > bestPriority) {
					bestPriority = priority;
					best = i;
				}
			}
			if (best < 0) {
				continue; // nothing to put here; a spent slot is freed in the fade step below
			}
			pLightX[s] = candX[best]; pLightY[s] = candY[best]; pLightZ[s] = candZ[best];
			pLightRange[s] = Math.max(6f, candLvl[best]);
			pLightDyn[s] = candDyn[best];
			pLightEmbedded[s] = candEmbedded[best];
			pLightSolid[s] = candSolid[best];
			pLightFaceMask[s] = candFaceMask[best];
			pLightR[s] = candR[best]; pLightG[s] = candG[best]; pLightB[s] = candB[best];
			pLightValid[s] = true;
			pRenderX[s] = Float.NaN; // force a map render for the new occupant
			pMapDirty[s] = true;
			pMapRevision[s]++;
			pEntityMotionFingerprint[s] = Long.MIN_VALUE;
			pMapDirtyReadyNs[s] = 0L;
			pMapLastRenderNs[s] = 0L;
			pEntityMapValid[s] = false;
			pVoxelValid[s] = false;
			pLightFade[s] = 0f;      // ramp in from black
			pFadeTarget[s] = 1f;
			candUsed[best] = true;
		}
		// Time-based fade: the old fixed 0.1/frame ramp lasted ~60 ms at 160 FPS and made a valid
		// shadow look like a one-frame flash. Keep the same visual duration at every frame rate.
		for (int s = 0; s < POINT_MAX; s++) {
			// A newly assigned static slot may wait one or two frames for the one-cubemap render budget.
			// Keep it at zero until all six faces exist; otherwise it would sample the previous occupant.
			boolean waitingForFirstMap = pLightValid[s] && !pLightDyn[s] && Float.isNaN(pRenderX[s]);
			// Dynamic cubemaps render later in this same pass, before any terrain samples them. They can
			// therefore become fully visible immediately; only retained static maps must wait here.
			float t = waitingForFirstMap ? 0f : pFadeTarget[s];
			// Equipping/removing a held, helmet or dropped source must feel immediate. Static slot
			// transitions retain the slower cross-fade which prevents room-wide popping between lamps.
			float fadeStep = pLightDyn[s] ? 1f : Math.min(0.25f, dt / 0.35f);
			if (pLightFade[s] < t) {
				pLightFade[s] = Math.min(t, pLightFade[s] + fadeStep);
			} else if (pLightFade[s] > t) {
				pLightFade[s] = Math.max(t, pLightFade[s] - fadeStep);
			}
			// A freshly assigned slot intentionally stays at fade=0 until its first six faces are rendered.
			// It is still owned (pFadeTarget=1) and must not be mistaken for a finished retiring slot.
			if (pFadeTarget[s] == 0f && !waitingForFirstMap && pLightFade[s] <= 0.02f) {
				pLightValid[s] = false; // fully faded → slot is now free for a new light
				pEntityMotionFingerprint[s] = Long.MIN_VALUE;
				pVoxelValid[s] = false;
			}
		}

		// Source density must not disable already selected shadow maps.
		pointSatFactor = 1f;
	}

	private static float sq(float v) {
		return v * v;
	}

	/**
	 * Publishes the {@link #SCAN_MAX} best placed lights from immutable, render-thread-owned section
	 * metadata. No live world reads, no partial scan whose centre chases a flying player, and no hard
	 * 15-block discovery boundary. The physical shader range remains each block's real luminance.
	 */
	private static void refreshRegisteredPlacedLights(double cx, double cy, double cz) {
		scanOriginX = (int) Math.floor(cx);
		scanOriginY = (int) Math.floor(cy);
		scanOriginZ = (int) Math.floor(cz);
		int pcx = scanOriginX >> 4, psy = scanOriginY >> 4, pcz = scanOriginZ >> 4;
		int found = 0;
		int saturated = 0;
		int radius = Math.min(POINT_REGISTRY_RADIUS_SECTIONS, Math.max(1, radiusChunks));
		for (int sx = pcx - radius; sx <= pcx + radius; sx++) {
			for (int sy = psy - VERTICAL_SECTIONS; sy <= psy + VERTICAL_SECTIONS; sy++) {
				for (int sz = pcz - radius; sz <= pcz + radius; sz++) {
					Sec section = SECTIONS.get(ChunkSectionPos.asLong(sx, sy, sz));
					if (section == null || section.residency != Sec.Residency.ACTIVE
							|| section.placedLightCount == 0 || section.placedLights == null) continue;
					for (int source = 0; source < section.placedLightCount; source++) {
						boolean solidEmitter = (section.placedLightSolidMask & 1L << source) != 0L;
						int faceMask = section.placedLightFaceMasks != null
								&& source < section.placedLightFaceMasks.length
								? section.placedLightFaceMasks[source] & 0x3F : 0x3F;
						int packed = section.placedLights[source];
						float level = packed >>> 12 & 15;
						float x = (section.cx << 4) + (packed & 15) + 0.5f;
						float y = (section.sy << 4) + (packed >>> 8 & 15) + 0.5f;
						float z = (section.cz << 4) + (packed >>> 4 & 15) + 0.5f;
						float dx = x - (float) cx, dy = y - (float) cy, dz = z - (float) cz;
						float distanceScore = level - (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
						if (distanceScore > 3f) saturated++;
						if (distanceScore <= POINT_STATIC_EXIT_SCORE) continue;
						float score = distanceScore + (pointLightInfluenceVisible(
								x, y, z, Math.max(6f, level)) ? POINT_VISIBLE_PRIORITY : 0f);
						if (found < SCAN_MAX) {
							scanPX[found] = x; scanPY[found] = y; scanPZ[found] = z;
							scanPLvl[found] = level; scanPScore[found] = score;
							scanPSolid[found] = solidEmitter;
							scanPFaceMask[found] = faceMask;
							scanPR[found] = SectionMeshData.placedLightRed(packed);
							scanPG[found] = SectionMeshData.placedLightGreen(packed);
							scanPB[found] = SectionMeshData.placedLightBlue(packed);
							found++;
						} else {
							int worst = 0;
							for (int k = 1; k < SCAN_MAX; k++) if (scanPScore[k] < scanPScore[worst]) worst = k;
							if (score > scanPScore[worst]) {
								scanPX[worst] = x; scanPY[worst] = y; scanPZ[worst] = z;
								scanPLvl[worst] = level; scanPScore[worst] = score;
								scanPSolid[worst] = solidEmitter;
								scanPFaceMask[worst] = faceMask;
								scanPR[worst] = SectionMeshData.placedLightRed(packed);
								scanPG[worst] = SectionMeshData.placedLightGreen(packed);
								scanPB[worst] = SectionMeshData.placedLightBlue(packed);
							}
						}
					}
				}
			}
		}
		scanPN = found;
		scanSatCount = saturated;
		heroScanCooldown = 16;
	}

	/** A changed caster section invalidates cached placed/dropped maps whose light sphere reaches its AABB. */
	private static void invalidateStaticPointMapsForSection(int cx, int sy, int cz) {
		invalidateStaticPointMapsForSection(cx, sy, cz, false);
	}

	private static void invalidateStaticPointMapsForSection(int cx, int sy, int cz, boolean immediate) {
		long readyNs = System.nanoTime() + (immediate ? 0L : 75_000_000L);
		float minX = cx << 4, minY = sy << 4, minZ = cz << 4;
		float maxX = minX + 16f, maxY = minY + 16f, maxZ = minZ + 16f;
		for (int slot = 0; slot < POINT_MAX; slot++) {
			if (!pLightValid[slot]) continue;
			float dx = pLightX[slot] < minX ? minX - pLightX[slot] : Math.max(0f, pLightX[slot] - maxX);
			float dy = pLightY[slot] < minY ? minY - pLightY[slot] : Math.max(0f, pLightY[slot] - maxY);
			float dz = pLightZ[slot] < minZ ? minZ - pLightZ[slot] : Math.max(0f, pLightZ[slot] - maxZ);
			float reach = pLightRange[slot] + 1f;
			if (dx * dx + dy * dy + dz * dz <= reach * reach) {
				// Advance even while a phased build is in flight. Its completion may publish a coherent
				// map, but it must not clear this newer caster generation.
				pMapRevision[slot]++;
				// Every point light uses the immutable full-cube occupancy snapshot as a conservative fallback
				// to its cubemap. A changed caster inside its reach must publish a fresh volume next frame.
				pVoxelValid[slot] = false;
				// Debounce from the FIRST changed mesh, not the most recent one. A 3x3x3 light refresh
				// uploads neighbouring consumers over several frames; continually pushing this deadline
				// forward starved the cubemap and left the removed caster visible as a black hole. If a
				// refresh is already waiting, preserve its earliest eligible render. Uploads which arrive
				// after that render dirty the map again and get their own bounded 75 ms window.
				if (!pMapDirty[slot]) {
					pMapDirty[slot] = true;
					pMapDirtyReadyNs[slot] = readyNs;
				} else if (pMapDirtyReadyNs[slot] == 0L || readyNs < pMapDirtyReadyNs[slot]) {
					pMapDirtyReadyNs[slot] = readyNs;
				}
			}
		}
	}

	private static boolean placedLightsChanged(Sec old, Sec replacement) {
		if (old.placedLightCount != replacement.placedLightCount
				|| old.placedLightTotal != replacement.placedLightTotal
				|| old.placedLightSolidMask != replacement.placedLightSolidMask) return true;
		return !java.util.Arrays.equals(old.placedLights, replacement.placedLights)
				|| !java.util.Arrays.equals(old.placedLightFaceMasks, replacement.placedLightFaceMasks);
	}

	/**
	 * A section can publish its luminous blocks after already-visible neighbours were meshed from a
	 * provisional/missing chunk halo. Vanilla light notifications may have completed before that GPU
	 * publication, so those neighbours otherwise retain a different baked hue indefinitely. Refresh
	 * only resident consumers; pending first meshes already capture the latest world/halo snapshot.
	 */
	private static void refreshPlacedLightConsumers(Sec source, long sourceKey) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -1; dz <= 1; dz++) {
					long consumerKey = ChunkSectionPos.asLong(source.cx + dx, source.sy + dy, source.cz + dz);
					if (consumerKey == sourceKey) continue;
					Sec consumer = SECTIONS.get(consumerKey);
					if (consumer != null && consumer.residency == Sec.Residency.ACTIVE) {
						ASYNC_ONLY.add(consumerKey);
						markLightDirty(consumerKey);
					}
				}
			}
		}
	}

	/**
	 * Build the 6 cube-face view-projections (±X ±Y ±Z, 92° so faces overlap the 45° seams slightly).
	 * They depend on NOTHING but the light's position (folded in via world−lightPos), so neither camera
	 * rotation nor player movement can shift a placed light's shadows — the earlier single player-aimed
	 * cone strobed on turning and lost block shadows whenever the player left its footprint.
	 * NEAR = 0.03: a dropped item can rest almost flush with a solid face. A 0.2-block near plane
	 * clipped that face from the cube map and produced a bright line through the block exactly along
	 * its edge. Carrier/item self-shadowing is already removed by the explicit near cuts in the entity
	 * layer, while a placed emitter's own terrain cube is removed by the exact source-block AABB test
	 * in SHADOW_FS, so the projection itself can safely retain close occluders.
	 */
	private static void buildPointLightMatrices(int slot) {
		buildPointLightMatrices(Math.max(4f, pLightRange[slot]), pointFaceMx, slot * 6);
	}

	private static void buildPointLightMatrices(float far, Matrix4f[] destination, int base) {
		for (int f = 0; f < 6; f++) {
			destination[base + f].identity().perspective((float) Math.toRadians(92.0), 1f, 0.03f, far);
		}
		destination[base].lookAt(0, 0, 0, 1, 0, 0, 0, 1, 0);
		destination[base + 1].lookAt(0, 0, 0, -1, 0, 0, 0, 1, 0);
		destination[base + 2].lookAt(0, 0, 0, 0, 1, 0, 1, 0, 0);
		destination[base + 3].lookAt(0, 0, 0, 0, -1, 0, 1, 0, 0);
		destination[base + 4].lookAt(0, 0, 0, 0, 0, 1, 0, 1, 0);
		destination[base + 5].lookAt(0, 0, 0, 0, 0, -1, 0, 1, 0);
	}

	/**
	 * Whether a point light can affect terrain that contributes to this frame. The source itself does
	 * not have to be on screen: a lamp behind the camera or behind a wall is still relevant when its
	 * sphere reaches a visible wall/floor. Conversely, a light fully outside the frustum or contained
	 * only in occluded/sleeping sections keeps its cached cubemap without paying for an update.
	 *
	 * <p>The source range is at most 15 blocks, so this examines only the small set of section AABBs
	 * touched by the sphere (normally 2-3 per axis), never the complete visible-section collection.
	 * An unavailable visibility graph remains the conservative "visible" state.
	 */
	private static boolean pointLightInfluenceVisible(float x, float y, float z, float range) {
		float reach = Math.max(1f, range) + 1f;
		if (!isSphereInFrustum((float) (x - capCamX), (float) (y - capCamY),
				(float) (z - capCamZ), reach)) return false;
		if (VISIBLE_SECTIONS.isEmpty()) return true;

		int minSx = ((int) Math.floor(x - reach)) >> 4;
		int maxSx = ((int) Math.floor(x + reach)) >> 4;
		int minSy = ((int) Math.floor(y - reach)) >> 4;
		int maxSy = ((int) Math.floor(y + reach)) >> 4;
		int minSz = ((int) Math.floor(z - reach)) >> 4;
		int maxSz = ((int) Math.floor(z + reach)) >> 4;
		float reachSq = reach * reach;
		for (int sx = minSx; sx <= maxSx; sx++) {
			float minX = sx << 4, maxX = minX + 16f;
			float dx = x < minX ? minX - x : Math.max(0f, x - maxX);
			for (int sy = minSy; sy <= maxSy; sy++) {
				float minY = sy << 4, maxY = minY + 16f;
				float dy = y < minY ? minY - y : Math.max(0f, y - maxY);
				for (int sz = minSz; sz <= maxSz; sz++) {
					float minZ = sz << 4, maxZ = minZ + 16f;
					float dz = z < minZ ? minZ - z : Math.max(0f, z - maxZ);
					if (dx * dx + dy * dy + dz * dz > reachSq) continue;
					long key = ChunkSectionPos.asLong(sx, sy, sz);
					if (!isVisibleSectionKey(key)) continue;
					Sec section = SECTIONS.get(key);
					if (section == null || section.residency != Sec.Residency.ACTIVE
							|| section.count == 0 && section.wcount == 0) continue;
					if (!haveMatrix || sectionVisible((float) (minX - capCamX),
							(float) (minY - capCamY), (float) (minZ - capCamZ))) return true;
				}
			}
		}
		return false;
	}

	private static int colorMaskBits() {
		try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
			ByteBuffer mask = stack.malloc(4);

			GL11.glGetBooleanv(
					GL11.GL_COLOR_WRITEMASK,
					mask);

			return (mask.get(0) != 0 ? 1 : 0)
					| (mask.get(1) != 0 ? 2 : 0)
					| (mask.get(2) != 0 ? 4 : 0)
					| (mask.get(3) != 0 ? 8 : 0);
		}
	}

	/** Render the hero light's shadow map — only when the light/player actually MOVED (else keep it). */
	private static void renderPointShadowPass(MinecraftClient client, MoneyakShadersConfig cfg) {
		if (!cfg.pointLightShadows || shadowProgram == 0 || SECTIONS.isEmpty()
				|| client.world == null || client.player == null) {
			java.util.Arrays.fill(pLightValid, false);
			java.util.Arrays.fill(pLightFade, 0f);
			return;
		}
		int pointRes =
				Math.max(
						256,
						pointShadowBaseResolution(cfg)
								>> adaptivePointShadowLevel);

		ensurePointShadowTarget(pointRes); // resize preserves depths and visibility while maps refresh
		ensurePointVoxelTarget();
		selectPointLights(client);
		// A three-frame terrain build belongs to its captured source, not just the slot index.
		// Picking up a source can reassign that slot while old faces are still being rendered.
		// Never publish those faces/matrices into the replacement owner's state.
		if (pointTerrainBuildSlot >= 0) {
			int s = pointTerrainBuildSlot;
			boolean ownerChanged = !pLightValid[s] || pLightDyn[s]
					|| pLightSolid[s] != pointTerrainBuildSolid
					|| Math.abs(pLightRange[s] - pointTerrainBuildRange) > 1.0e-4f
					|| sq(pLightX[s] - pointTerrainBuildX) + sq(pLightY[s] - pointTerrainBuildY)
							+ sq(pLightZ[s] - pointTerrainBuildZ) > 1.0e-6f;
			if (ownerChanged) {
				pRenderX[s] = Float.NaN;
				pLightFade[s] = 0f;
				pEntityMapValid[s] = false;
				pMapDirty[s] = true;
				pMapDirtyReadyNs[s] = 0L;
				pointTerrainBuildSlot = -1;
				pointTerrainBuildNextFace = 0;
				pointTerrainBuildRevision = 0L;
			}
		}
		if (pointSatFactor < 0.05f) {
			return; // saturated area — four selected shadows would be physically misleading
		}
		long nowNs = System.nanoTime();
		int relevantLights = 0;
		for (int s = 0; s < POINT_MAX; s++) {
			// Slot selection is already bounded. Frustum-gating updates left an off-screen map stale
			// and made its shadow pop in after a quick camera turn.
			pLightRelevant[s] = pLightValid[s];
			if (!pLightRelevant[s]) continue;
			relevantLights++;
			if (!pointVoxelOriginMatches(s)) {
				uploadPointVoxelSlot(s);
			}
			long fingerprint = EntityShadowCapture.spatialMotionFingerprint(
					pLightX[s], pLightY[s], pLightZ[s], pLightRange[s]);
			if (fingerprint != pEntityMotionFingerprint[s]) {
				// The first signature belongs to a fresh slot which already has to render. Afterwards only
				// physical motion inside this light's reach invalidates its map; distant mobs stay irrelevant.
				if (pEntityMotionFingerprint[s] != Long.MIN_VALUE && !Float.isNaN(pRenderX[s])) {
					pMapMotionDirty[s] = true;
				}
				pEntityMotionFingerprint[s] = fingerprint;
			}
		}
		if (cfg.debugStats) debugPointRelevant = relevantLights;
		// Terrain is the expensive layer: cache it and refresh at most one full cubemap per frame when the
		// source moves or nearby blocks change. Entity/item pose changes are handled by the separate live
		// depth atlas below and therefore never force six chunk multidraws.
		int chosenSlot = pointTerrainBuildSlot;
		if (chosenSlot < 0) {
			for (int offset = 0; offset < POINT_MAX; offset++) {
				int slot = (staticPointRenderCursor + offset) % POINT_MAX;
				if (!pLightRelevant[slot] || pLightDyn[slot]) continue;
				boolean fresh = Float.isNaN(pRenderX[slot]);
				boolean moved = fresh || sq(pLightX[slot] - pRenderX[slot]) + sq(pLightY[slot] - pRenderY[slot])
						+ sq(pLightZ[slot] - pRenderZ[slot]) > 1.0e-6f;
				boolean terrainReady = pMapDirty[slot] && nowNs >= pMapDirtyReadyNs[slot];
				if (!fresh && !moved && !terrainReady) continue;
				chosenSlot = slot;
				pointTerrainBuildSlot = slot;
				pointTerrainBuildNextFace = 0;
				pointTerrainBuildRevision = pMapRevision[slot];
				pointTerrainBuildX = pLightX[slot];
				pointTerrainBuildY = pLightY[slot];
				pointTerrainBuildZ = pLightZ[slot];
				pointTerrainBuildRange = pLightRange[slot];
				pointTerrainBuildSolid = pLightSolid[slot];
				buildPointLightMatrices(Math.max(4f, pointTerrainBuildRange), pointTerrainBuildMatrices, 0);
				// A reassigned slot keeps its previous complete map until the replacement is ready, but
				// fades out because its old depth cannot describe a different light origin.
				if (moved) pLightFade[slot] = 0f;
				break;
			}
			if (chosenSlot >= 0) staticPointRenderCursor = (chosenSlot + 1) % POINT_MAX;
		}
		boolean anyRender = chosenSlot >= 0;
		for (int s = 0; s < POINT_MAX; s++) {
			boolean fresh = Float.isNaN(pRenderX[s]);
			boolean moved = fresh || sq(pLightX[s] - pRenderX[s]) + sq(pLightY[s] - pRenderY[s])
					+ sq(pLightZ[s] - pRenderZ[s]) > 1.0e-6f;
			boolean terrainReady = pMapDirty[s] && nowNs >= pMapDirtyReadyNs[s];
			needRender[s] = s == chosenSlot;
			needEntityRender[s] = false;
			if (!pLightRelevant[s]) {
				continue;
			}
			if (!Float.isNaN(pRenderX[s]) && (!needRender[s] || pointTerrainBuildNextFace > 0)) {
				// A cached light waiting for the one-cubemap budget must sample from the exact origin
				// that produced its retained map. selectPointLights restores the live candidate next frame.
				pLightX[s] = pRenderX[s]; pLightY[s] = pRenderY[s]; pLightZ[s] = pRenderZ[s]; // stay in sync with the kept map
			}
			needEntityRender[s] = !needRender[s] && !Float.isNaN(pRenderX[s])
					&& (!pEntityMapValid[s] || pMapMotionDirty[s]);
			anyRender |= needRender[s] || needEntityRender[s];
		}
		if (!anyRender) {
			return;
		}
		if (cfg.debugStats) {
			debugPointShadowPasses++;
			for (int s = 0; s < POINT_MAX; s++) {
				if (needRender[s]) debugPointShadowSlots++;
				if (needEntityRender[s]) debugPointEntitySlots++;
			}
		}
		GPU_PROFILER.begin("shadow-point");

		// Capture EVERYTHING this pass touches. It also runs when the sun pass doesn't (night, caves) —
		// leaving any binding behind desynced MC's own entity/item rendering right after us (dropped items
		// drew as flat white silhouettes: their atlas bind was clobbered).
		int prevFbo =
				GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);

		int prevProgram =
				GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);

		int prevVao =
				GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);

		int[] vp = POINT_VIEWPORT_SCRATCH;
		GL11.glGetIntegerv(GL11.GL_VIEWPORT, vp);

		boolean prevDepth =
				RenderGlState.depthTest();

		boolean prevCull =
				RenderGlState.cullEnabled();

		boolean prevBlend =
				RenderGlState.blendEnabled();

		boolean prevDepthMask =
				RenderGlState.depthMask();

		int prevDepthFunc =
				RenderGlState.depthFunc();

		boolean prevPolygonOffset =
				RenderGlState.polygonOffsetEnabled();

		float prevPolygonFactor =
				RenderGlState.polygonOffsetFactor();

		float prevPolygonUnits =
				RenderGlState.polygonOffsetUnits();

		int prevActive =
				RenderGlState.activeTexture();

		int prevTex0 =
				RenderGlState.texture2D(0);

		GlStateManager._activeTexture(GL13.GL_TEXTURE0);

		int prevSampler0 =
				GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
		GlStateManager._enableDepthTest();
		GlStateManager._depthFunc(GL11.GL_LEQUAL);
		GlStateManager._depthMask(true);
		GlStateManager._disableBlend();
		// Point-light occlusion must be two-sided. Custom chunk meshes contain only faces exposed to
		// air, while resource-pack models and captured block geometry do not promise one common winding
		// for every visible surface. Either front- or back-face culling can therefore erase the only
		// available wall polygon from one cube face and let a held/dropped light shine permanently
		// through a solid block. The point volume is local and receiver bias handles acne, so retaining
		// both sides is the reliable contract here. Sun cascades keep their cheaper directional culling.
		GlStateManager._disableCull();
		GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, pointShadowFbo);

		int blockAtlas = atlasGlId(client);
		int itemsAtlas = itemsAtlasGlId(client); // 1.21.11: regular items live on a separate items atlas
		if (dayFactor <= 0f || !cfg.sunShadows) {
			buildEntityBoxes(client); // sun pass didn't run this frame (night/off) → captured geometry not uploaded yet
		}
		boolean prevScissor = RenderGlState.scissorEnabled();
		for (int s = 0; s < POINT_MAX; s++) {
			if (!needRender[s]) {
				continue;
			}
			float lx = pointTerrainBuildX, ly = pointTerrainBuildY, lz = pointTerrainBuildZ;
			int lcx = ((int) Math.floor(lx)) >> 4;
			int lsy = ((int) Math.floor(ly)) >> 4;
			int lcz = ((int) Math.floor(lz)) >> 4;
			// +2 chunks (not +1): a light near a chunk edge reaches its full range diagonally into the
			// SECOND chunk over. At +1 those occluders were absent from the depth map, so their shadow
			// only appeared once you walked closer and the chunk entered the radius — the "shadow pops
			// into the screen" artefact. +2 covers the whole reach so shadows are there from the start.
			int rad = Math.max(2, (((int) pointTerrainBuildRange) >> 4) + 2);
			int firstFace = pointTerrainBuildNextFace;
			int endFace = Math.min(6, firstFace + 2);
			for (int f = firstFace; f < endFace; f++) {
				// Clear only the face being replaced. Other faces retain the last complete cubemap while
				// the rebuild is spread over three frames.
				GlStateManager._enableScissorTest();
				GL11.glScissor((f % 3) * pointShadowRes,
						(s * 2 + f / 3) * pointShadowRes, pointShadowRes, pointShadowRes);
				GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
				GlStateManager._disableScissorTest();
				GlStateManager._viewport((f % 3) * pointShadowRes, (s * 2 + f / 3) * pointShadowRes,
						pointShadowRes, pointShadowRes);
				GlStateManager._glUseProgram(shadowProgram);
				GlStateManager._disableCull();
				// NO wind in the point map: the map is retained while nothing moves — leaves frozen mid-sway
				// would shimmer against the live swaying geometry on every re-render.
				GL20.glUniform1f(suTime, 0f);
				GL20.glUniform1f(suWind, 0f);
				// Physical emitters now use the exact same centred cubemap as entity lights. Discard only
				// the source cube's dedicated emitter material; adjacent walls/floors remain in the map.
				GL20.glUniform1f(suNearCut, pointTerrainBuildSolid ? 1f : 0f);
				if (blockAtlas > 0) {
					GlStateManager._activeTexture(GL13.GL_TEXTURE0);
					GlStateManager._bindTexture(blockAtlas);
					GL33.glBindSampler(0, 0);
					GL20.glUniform1i(suShadowAtlas, 0);
				}
				try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
					GL20.glUniformMatrix4fv(suLightMVP, false,
							pointTerrainBuildMatrices[f].get(stack.mallocFloat(16)));
				}
				GL20.glUniform3f(suCamPos, lx, ly, lz);
				// Sun cascades use a split origin; point maps already pass the complete light position.
				// Uniforms persist across passes, so a retained sun remainder shifts every point caster.
				GL20.glUniform3f(suCamPosLow, 0f, 0f, 0f);
				// A point light needs every nearby occluder around its source.  The directional
				// caster set is swept only towards the sun from camera-visible receivers, so
				// applying it here omitted arbitrary cube-map faces as the player moved.
				// Push only terrain raster depth away from its receiver. This slope-aware source-side
				// bias removes the dashed/moire self-shadow grid without moving the receiver through a
				// wall; entity and item silhouettes below intentionally keep their exact contact depth.
				// Keep slope-scale offset so lit faces do not self-shadow into a dashed/moire grid. Full
				// emitters close the small resulting contact gap in the receiver with a conservative 3x3
				// minimum-depth footprint instead of disabling this whole-surface stability guard.
				GlStateManager._enablePolygonOffset();
				GlStateManager._polygonOffset(1.1f, 4.0f);
				multidrawShadow(lcx, lsy, lcz, rad, false, -1);
				GlStateManager._disablePolygonOffset();
			}
			pointTerrainBuildNextFace = endFace;
			if (pointTerrainBuildNextFace >= 6) {
				for (int f = 0; f < 6; f++) pointFaceMx[s * 6 + f].set(pointTerrainBuildMatrices[f]);
				pRenderX[s] = pointTerrainBuildX;
				pRenderY[s] = pointTerrainBuildY;
				pRenderZ[s] = pointTerrainBuildZ;
				pLightX[s] = pointTerrainBuildX;
				pLightY[s] = pointTerrainBuildY;
				pLightZ[s] = pointTerrainBuildZ;
				pLightRange[s] = pointTerrainBuildRange;
				if (pMapRevision[s] == pointTerrainBuildRevision) {
					pMapDirty[s] = false;
					pMapDirtyReadyNs[s] = 0L;
				} else {
					pMapDirty[s] = true;
					pMapDirtyReadyNs[s] = nowNs;
				}
				pMapLastRenderNs[s] = nowNs;
				pEntityMapValid[s] = false;
				pointTerrainBuildSlot = -1;
				pointTerrainBuildNextFace = 0;
				pointTerrainBuildRevision = 0L;
			}
		}

		// Live entity/item layer. It contains no chunks, so all visible dirty slots can refresh in the
		// current frame without multiplying the expensive terrain multidraw. The atlas is half-resolution:
		// point ranges are small and these silhouettes use a hard comparison, while cached terrain retains
		// the configured high-resolution soft PCF map.
		GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, pointEntityShadowFbo);
		boolean anyItems = (itemVertCount > 0 && itemsAtlas > 0)
				|| (blockItemVertCount > 0 && blockAtlas > 0);
		for (int s = 0; s < POINT_MAX; s++) {
			if (!needEntityRender[s] || Float.isNaN(pRenderX[s])) continue;
			buildPointLightMatrices(s);
			float lx = pRenderX[s], ly = pRenderY[s], lz = pRenderZ[s];
			// The source of a held/burning light sits inside its carrier. Exclude the full carrier so its
			// own body cannot radiate self-shadow streaks; dropped items use only a tiny emitter cut.
			float boxNearCut = pLightSolid[s] ? 0f : (pLightEmbedded[s] ? 1.7f : (pLightDyn[s] ? 0.10f : 0.3f));
			float itemNearCut = pLightSolid[s] ? 0f : (pLightEmbedded[s] ? 1.7f : (pLightDyn[s] ? 0.90f : 0.1f));
			GlStateManager._enableScissorTest();
			GL11.glScissor(0, s * 2 * pointEntityShadowRes,
					3 * pointEntityShadowRes, 2 * pointEntityShadowRes);
			GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
			GlStateManager._disableScissorTest();
			for (int f = 0; f < 6; f++) {
				float flx = lx, fly = ly, flz = lz;
				GlStateManager._viewport((f % 3) * pointEntityShadowRes,
						(s * 2 + f / 3) * pointEntityShadowRes,
						pointEntityShadowRes, pointEntityShadowRes);
				if (boxProgram != 0 && boxVertCount > 0) {
					GlStateManager._glUseProgram(boxProgram);
					GlStateManager._disableCull();
					try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
						GL20.glUniformMatrix4fv(suBoxLightMVP, false,
								pointFaceMx[s * 6 + f].get(stack.mallocFloat(16)));
					}
					GL20.glUniform3f(suBoxCamPos, flx, fly, flz);
					GL20.glUniform1f(suBoxNearCut, boxNearCut);
					GL30.glBindVertexArray(boxVao);
					GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, boxVertCount);
				}
				if (itemShadowProgram != 0 && anyItems) {
					GlStateManager._glUseProgram(itemShadowProgram);
					GlStateManager._disableCull();
					GlStateManager._activeTexture(GL13.GL_TEXTURE0);
					GL33.glBindSampler(0, 0);
					GL20.glUniform1i(suItemAtlas, 0);
					GL20.glUniform1f(suItemNearCut, itemNearCut);
					try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
						GL20.glUniformMatrix4fv(suItemLightMVP, false,
								pointFaceMx[s * 6 + f].get(stack.mallocFloat(16)));
					}
					GL20.glUniform3f(suItemCamPos, flx, fly, flz);
					if (itemVertCount > 0 && itemsAtlas > 0) {
						GlStateManager._bindTexture(itemsAtlas);
						GL30.glBindVertexArray(itemVao);
						GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, itemVertCount);
					}
					if (blockItemVertCount > 0 && blockAtlas > 0) {
						GlStateManager._bindTexture(blockAtlas);
						GL30.glBindVertexArray(blockItemVao);
						GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, blockItemVertCount);
					}
				}
			}
			pEntityMapValid[s] = true;
			pMapMotionDirty[s] = false;
		}
		if (prevScissor) {
			GlStateManager._enableScissorTest();
		}
		// --- restore EVERYTHING ---

		if (prevScissor) {
			GlStateManager._enableScissorTest();
		} else {
			GlStateManager._disableScissorTest();
		}

		GlStateManager._glBindFramebuffer(
				GL30.GL_FRAMEBUFFER,
				prevFbo);

		GlStateManager._glUseProgram(prevProgram);
		GlStateManager._glBindVertexArray(prevVao);

		GlStateManager._viewport(
				vp[0],
				vp[1],
				vp[2],
				vp[3]);

		if (prevCull) {
			GlStateManager._enableCull();
		} else {
			GlStateManager._disableCull();
		}

		if (prevBlend) {
			GlStateManager._enableBlend();
		} else {
			GlStateManager._disableBlend();
		}

		GlStateManager._depthMask(prevDepthMask);
		GlStateManager._depthFunc(prevDepthFunc);

		if (prevDepth) {
			GlStateManager._enableDepthTest();
		} else {
			GlStateManager._disableDepthTest();
		}

		if (prevPolygonOffset) {
			GlStateManager._enablePolygonOffset();
			GlStateManager._polygonOffset(
					prevPolygonFactor,
					prevPolygonUnits);
		} else {
			GlStateManager._disablePolygonOffset();
		}

		GlStateManager._activeTexture(GL13.GL_TEXTURE0);
		GlStateManager._bindTexture(prevTex0);
		GL33.glBindSampler(0, prevSampler0);

		GlStateManager._activeTexture(prevActive);

		GPU_PROFILER.end("shadow-point");
	}

	/** GLSL-style smoothstep: 0 below {@code e0}, 1 above {@code e1}, smooth Hermite in between. */
	private static float smoothstep(float e0, float e1, float x) {
		float t = Math.max(0f, Math.min(1f, (x - e0) / (e1 - e0)));
		return t * t * (3f - 2f * t);
	}

	/** Light view-projection from the sun's position (time of day) + dayFactor (0 at night). */
	private static void updateSceneLightingState(MinecraftClient client, MoneyakShadersConfig cfg) {
		SCENE_LIGHTING.computeFromWorld(client, cfg);
		boolean underwater = isCameraInWaterVolume(client);
		SCENE_LIGHTING.setWaterState(underwater, Float.NEGATIVE_INFINITY);
		Vector3f dir = SCENE_LIGHTING.activeDirection();
		Vector3f color = SCENE_LIGHTING.activeColor();
		sunDirX = dir.x; sunDirY = dir.y; sunDirZ = dir.z;
		dayFactor = SCENE_LIGHTING.activeDirectStrength();
		sunUpFactor = SCENE_LIGHTING.directSunStrength;
		celestialMoon = SCENE_LIGHTING.moonLighting;
		celestialR = color.x; celestialG = color.y; celestialB = color.z;
	}

	/** Whether the camera is actually submerged. Air pockets around doors and chests stay air. */
	public static boolean isCameraInWaterVolume(MinecraftClient client) {
		return client != null && client.gameRenderer != null
				&& client.gameRenderer.getCamera().getSubmersionType()
						== net.minecraft.block.enums.CameraSubmersionType.WATER;
	}
	/** Build cascade {@code idx}: ortho of half-size {@code s} from the sun + per-cascade texel snap. */
	private static void buildCascade(int idx) {
		float sx = sunDirX, sy = sunDirY, sz = sunDirZ;
		float s = SHADOW_LAYOUT.radiusBlocks[idx];
		float depthHalf = Math.max(SHADOW_LAYOUT.depthHalf[idx], SHADOW_LAYOUT.depthHalf[ShadowCascadeLayout.FAR]);
		orthoHalf[idx] = s;
		float d = 2f * depthHalf;
		shadowDepthSpan[idx] = 4f * depthHalf - 0.1f;
		float upX = 0f, upY = Math.abs(sy) > 0.96f ? 0f : 1f, upZ = Math.abs(sy) > 0.96f ? 1f : 0f;
		lightMx[idx].identity().ortho(-s, s, -s, s, 0.1f, 4f * depthHalf).lookAt(sx * d, sy * d, sz * d, 0f, 0f, 0f, upX, upY, upZ);
		double texel = 2.0 * s / Math.max(1, shadowRes[idx]);
		Vector3f fwd = SHADOW_FWD.set(-sx, -sy, -sz);
		Vector3f right = SHADOW_RIGHT.set(fwd).cross(upX, upY, upZ).normalize();
		Vector3f upL = SHADOW_UP.set(right).cross(fwd).normalize();
		shadowRightX = right.x; shadowRightY = right.y; shadowRightZ = right.z;
		shadowUpX = upL.x; shadowUpY = upL.y; shadowUpZ = upL.z;
		ShadowOrigin origin = SHADOW_ORIGINS[idx];
		origin.update(capCamX, capCamY, capCamZ, texel, right.x, right.y, right.z, upL.x, upL.y, upL.z);
		shadowCamX[idx] = origin.x; shadowCamY[idx] = origin.y; shadowCamZ[idx] = origin.z;
		shadowBaseX[idx] = (float)(Math.floor(shadowCamX[idx] / 256.0) * 256.0);
		shadowBaseY[idx] = (float)(Math.floor(shadowCamY[idx] / 256.0) * 256.0);
		shadowBaseZ[idx] = (float)(Math.floor(shadowCamZ[idx] / 256.0) * 256.0);
		shadowLowX[idx] = (float)(shadowCamX[idx] - shadowBaseX[idx]);
		shadowLowY[idx] = (float)(shadowCamY[idx] - shadowBaseY[idx]);
		shadowLowZ[idx] = (float)(shadowCamZ[idx] - shadowBaseZ[idx]);
	}

	/** Keep rotation continuous; {@link #buildCascade(int)} snaps camera translation. */
	private static void updateShadowBasis() { shadowBasisX = sunDirX; shadowBasisY = sunDirY; shadowBasisZ = sunDirZ; }

	private static void bindSceneLightingUniforms() {
		SceneLightingSnapshot lighting = SCENE_LIGHTING.snapshot();
		Vector3f dir = lighting.direction;
		Vector3f color = lighting.directColor;
		GL20.glUniform3f(uDirectionalDir, dir.x, dir.y, dir.z);
		GL20.glUniform3f(uDirectionalColor, color.x, color.y, color.z);
		GL20.glUniform1f(uDirectionalStrength, lighting.directStrength);
		GL20.glUniform3f(uSkyAmbientColor, lighting.skyAmbientColor.x, lighting.skyAmbientColor.y, lighting.skyAmbientColor.z);
		GL20.glUniform3f(uShadowAmbientColor, lighting.shadowAmbientColor.x, lighting.shadowAmbientColor.y, lighting.shadowAmbientColor.z);
		GL20.glUniform1f(uSkyAmbientStrength, lighting.skyAmbientStrength);
		GL20.glUniform3f(uHorizonFogColor, lighting.fogColor.x, lighting.fogColor.y, lighting.fogColor.z);
		GL20.glUniform1f(uFoliageTransmission, 0.04f);
		GL20.glUniform1f(uRainFactor, lighting.rainFactor);
		GL20.glUniform3f(uSunDir, dir.x, dir.y, dir.z);
		GL20.glUniform3f(uCelestialColor, color.x, color.y, color.z);
		GL20.glUniform1f(uCelestialMoon, lighting.moonLighting ? 1f : 0f);
		GL20.glUniform1f(uDayFactor, lighting.directStrength);
	}

	private static boolean allDirectionalShadowsReady() {
		for (int i = 0; i < DIRECTIONAL_CASCADE_COUNT; i++) if (!directionalShadowReady[i] || shadowTex[i] == 0) return false;
		return true;
	}

	private static void resetDirectionalShadowState() {
		for (int i = 0; i < DIRECTIONAL_CASCADE_COUNT; i++) {
			directionalShadowReady[i] = false; directionalShadowDirty[i] = true; directionalTerrainRevision[i] = Long.MIN_VALUE;
			shadowRenderCamX[i] = shadowRenderCamY[i] = shadowRenderCamZ[i] = Double.NaN;
			shadowRenderSunX[i] = shadowRenderSunY[i] = shadowRenderSunZ[i] = Float.NaN;
			shadowRenderHalf[i] = Float.NaN; SHADOW_ORIGINS[i].reset();
		}
		lastSunEntityRevision = Long.MIN_VALUE;
	}

	private static String shadowProfileName(int idx) { return idx == 0 ? "shadow-near" : idx == 1 ? "shadow-mid" : "shadow-far"; }

	private static void bindDirectionalShadowUniforms(MoneyakShadersConfig cfg) {
		if (!cfg.sunShadows || !allDirectionalShadowsReady() || SCENE_LIGHTING.activeDirectStrength() <= 0.001f) {
			GL20.glUniform1f(uShadowStrength, 0f);
			return;
		}

		for (int i = 0; i < DIRECTIONAL_CASCADE_COUNT; i++) {
			int shadowUnit = SHADOW_TEXTURE_UNITS[i];
			int waterUnit = WATER_SHADOW_TEXTURE_UNITS[i];

			bindTextureUnit(shadowUnit, shadowTex[i]);
			GL33.glBindSampler(shadowUnit, 0);
			GL20.glUniform1i(uShadowMap[i], shadowUnit);

			bindTextureUnit(waterUnit, shadowWaterTex[i]);
			GL33.glBindSampler(waterUnit, 0);
			GL20.glUniform1i(uShadowWaterMap[i], waterUnit);

			try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
				GL20.glUniformMatrix4fv(uLightMVP[i], false, lightMx[i].get(stack.mallocFloat(16)));
			}

			GL20.glUniform1f(uShadowBias[i],
					SHADOW_LAYOUT.constantBias[i] / Math.max(0.001f, shadowDepthSpan[i]));
			GL20.glUniform1f(uShadowNormalBias[i], SHADOW_LAYOUT.normalBias[i]);
			GL20.glUniform3f(uShadowCam[i],
					(float)(capCamX - shadowCamX[i]),
					(float)(capCamY - shadowCamY[i]),
					(float)(capCamZ - shadowCamZ[i]));
			GL20.glUniform1f(uCascadeEnd[i], SHADOW_LAYOUT.radiusBlocks[i]);
		}

		GL20.glUniform1f(uCascadeBlend[0],
				Math.max(1f, SHADOW_LAYOUT.blendEnd[0] - SHADOW_LAYOUT.blendStart[0]));
		GL20.glUniform1f(uCascadeBlend[1],
				Math.max(1f, SHADOW_LAYOUT.blendEnd[1] - SHADOW_LAYOUT.blendStart[1]));
		GL20.glUniform1f(uShadowStrength,
				Math.max(0f, Math.min(1f, cfg.shadowStrength / 100f)));
	}
	private static void checkGl(String where) {
		int err;

		while ((err = GL11.glGetError()) != GL11.GL_NO_ERROR) {
			MoneyakShaders.LOGGER.info(
					"[Plan C/GL] {} -> GL error 0x{}",
					where,
					Integer.toHexString(err));
		}
	}

	/** Render all opaque sections near the player into the depth map from the sun's POV. */
	private static void renderShadowPass(MinecraftClient client, MoneyakShadersConfig cfg) {
		if (shadowProgram == 0 || SECTIONS.isEmpty()) {
			return;
		}
		boolean tracked = cfg.frameBudgetEnabled;
		if (tracked) com.moneyakshaders.client.FrameWorkBudget.startBucket(
				com.moneyakshaders.client.FrameWorkBudget.BUCKET_SHADOW);
		try {
			renderShadowPassBody(client, cfg);
		} finally {
			if (tracked) com.moneyakshaders.client.FrameWorkBudget.endBucket(
					com.moneyakshaders.client.FrameWorkBudget.BUCKET_SHADOW);
		}
	}

	private static void renderShadowPassBody(MinecraftClient client, MoneyakShadersConfig cfg) {
		if (SCENE_LIGHTING.activeDirectStrength() <= 0.001f) return;
		SHADOW_LAYOUT.update(cfg, cfg.adaptiveShadowQuality ? adaptiveShadowLevel : 0);
		ensureShadowTargets();
		BlockPos p = client.player.getBlockPos();
		boolean casterSetChanged = updateShadowCasters(p);
		long entityRevision = EntityShadowCapture.readyRevision();
		boolean entityChanged = entityRevision != lastSunEntityRevision;
		boolean[] update = new boolean[DIRECTIONAL_CASCADE_COUNT];
		boolean anyUpdate = false;
		for (int i = 0; i < DIRECTIONAL_CASCADE_COUNT; i++) {
			update[i] = !directionalShadowReady[i] || directionalShadowDirty[i] || sunTerrainRevision != directionalTerrainRevision[i]
					|| casterSetChanged || entityChanged || shadowTransformDirty(i, SHADOW_LAYOUT.radiusBlocks[i]);
			anyUpdate |= update[i];
		}
		if (!anyUpdate) { lastSunEntityRevision = entityRevision; return; }

		int prevFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
		int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
		int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
		int[] vp = SUN_VIEWPORT_SCRATCH; GL11.glGetIntegerv(GL11.GL_VIEWPORT, vp);
		boolean prevDepth = RenderGlState.depthTest();
		int prevDepthFunc = RenderGlState.depthFunc();
		boolean prevDepthMask = RenderGlState.depthMask();
		boolean prevCull = RenderGlState.cullEnabled();
		boolean prevBlend = RenderGlState.blendEnabled();
		int prevBlendSrcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), prevBlendDstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
		int prevBlendSrcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), prevBlendDstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
		boolean prevPolygonOffset = RenderGlState.polygonOffsetEnabled();
		float prevPolygonFactor = RenderGlState.polygonOffsetFactor(), prevPolygonUnits = RenderGlState.polygonOffsetUnits();
		int prevColorMask = colorMaskBits();
		int prevActive = RenderGlState.activeTexture(), prevTex0 = RenderGlState.texture2D(0);
		GlStateManager._activeTexture(GL13.GL_TEXTURE0);
		int prevSampler0 = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);

		GlStateManager._enableDepthTest(); GlStateManager._depthFunc(GL11.GL_LEQUAL); GlStateManager._depthMask(true);
		GlStateManager._disableBlend(); RenderGlState.colorMask(true, true, true, true); GlStateManager._disableCull();
		GlStateManager._enablePolygonOffset(); GlStateManager._polygonOffset(1.1f, 4f);

		buildEntityBoxes(client);
		int itemAtlas = itemsAtlasGlId(client), blockAtlas = atlasGlId(client);
		float time = (System.currentTimeMillis() - shaderStartMs) / 1000f, wind = cfg.windSway ? 0.06f : 0f;

		for (int i = 0; i < DIRECTIONAL_CASCADE_COUNT; i++) {
			if (!update[i]) continue;
			updateShadowBasis();
			buildCascade(i);
			String profile = shadowProfileName(i);
			GPU_PROFILER.begin(profile);
			GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, shadowFbo[i]);
			GL11.glDrawBuffer(GL11.GL_NONE); GL11.glReadBuffer(GL11.GL_NONE);
			GlStateManager._viewport(0, 0, shadowRes[i], shadowRes[i]);
			GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);

			GlStateManager._glUseProgram(shadowProgram); GlStateManager._disableCull();
			GL20.glUniform1f(suTime, time); GL20.glUniform1f(suWind, wind); GL20.glUniform1f(suNearCut, 0f);
			if (blockAtlas > 0) { GlStateManager._activeTexture(GL13.GL_TEXTURE0); GlStateManager._bindTexture(blockAtlas); GL33.glBindSampler(0, 0); GL20.glUniform1i(suShadowAtlas, 0); }
			try (var stack = org.lwjgl.system.MemoryStack.stackPush()) { GL20.glUniformMatrix4fv(suLightMVP, false, lightMx[i].get(stack.mallocFloat(16))); }
			GL20.glUniform3f(suCamPos, shadowBaseX[i], shadowBaseY[i], shadowBaseZ[i]);
			GL20.glUniform3f(suCamPosLow, shadowLowX[i], shadowLowY[i], shadowLowZ[i]);
			int chunks = Math.max(1, (int)Math.ceil(orthoHalf[i] / 16f) + 1);
			int draws = multidrawShadow(p.getX() >> 4, p.getY() >> 4, p.getZ() >> 4, chunks, true, i);

			if (cfg.debugStats) {
				if (i == 0) { debugSunNearPasses++; debugSunNearDraws += draws; }
				else if (i == 1) { debugSunMidPasses++; debugSunMidDraws += draws; }
				else { debugSunFarPasses++; debugSunFarDraws += draws; }
			}

			if (boxProgram != 0 && boxVertCount > 0) {
				GlStateManager._glUseProgram(boxProgram); GlStateManager._disableCull();
				try (var stack = org.lwjgl.system.MemoryStack.stackPush()) { GL20.glUniformMatrix4fv(suBoxLightMVP, false, lightMx[i].get(stack.mallocFloat(16))); }
				GL20.glUniform3f(suBoxCamPos, (float)shadowCamX[i], (float)shadowCamY[i], (float)shadowCamZ[i]);
				GL20.glUniform1f(suBoxNearCut, 0f); GL30.glBindVertexArray(boxVao); GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, boxVertCount);
			}

			boolean anyItems = itemVertCount > 0 && itemAtlas > 0 || blockItemVertCount > 0 && blockAtlas > 0;
			if (itemShadowProgram != 0 && anyItems) {
				GlStateManager._glUseProgram(itemShadowProgram); GlStateManager._disableCull(); GlStateManager._activeTexture(GL13.GL_TEXTURE0); GL33.glBindSampler(0, 0);
				GL20.glUniform1i(suItemAtlas, 0); GL20.glUniform1f(suItemNearCut, 0f);
				try (var stack = org.lwjgl.system.MemoryStack.stackPush()) { GL20.glUniformMatrix4fv(suItemLightMVP, false, lightMx[i].get(stack.mallocFloat(16))); }
				GL20.glUniform3f(suItemCamPos, (float)shadowCamX[i], (float)shadowCamY[i], (float)shadowCamZ[i]);
				if (itemVertCount > 0 && itemAtlas > 0) { GlStateManager._bindTexture(itemAtlas); GL30.glBindVertexArray(itemVao); GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, itemVertCount); }
				if (blockItemVertCount > 0 && blockAtlas > 0) { GlStateManager._bindTexture(blockAtlas); GL30.glBindVertexArray(blockItemVao); GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, blockItemVertCount); }
			}

			if (waterShadowProgram != 0 && shadowWaterTex[i] != 0) {
				GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0); GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0); GL30.glClearBufferfv(GL11.GL_COLOR, 0, SHADOW_WATER_BORDER);
				GlStateManager._depthMask(false); GlStateManager._disablePolygonOffset(); GlStateManager._disableBlend(); GlStateManager._glUseProgram(waterShadowProgram); GlStateManager._disableCull();
				GlStateManager._activeTexture(GL13.GL_TEXTURE0); GlStateManager._bindTexture(WaterSurfaceResources.waveTexture()); GL33.glBindSampler(0, 0);
				GL20.glUniform1i(swWaterWaveTex, 0); GL20.glUniform1f(swTime, time); GL20.glUniform1f(swWind, wind);
				try (var stack = org.lwjgl.system.MemoryStack.stackPush()) { GL20.glUniformMatrix4fv(swLightMVP, false, lightMx[i].get(stack.mallocFloat(16))); }
				GL20.glUniform3f(swCamPos, shadowBaseX[i], shadowBaseY[i], shadowBaseZ[i]); GL20.glUniform3f(swCamPosLow, shadowLowX[i], shadowLowY[i], shadowLowZ[i]);
				multidrawWaterShadow(p.getX() >> 4, p.getY() >> 4, p.getZ() >> 4, chunks, i);
				GlStateManager._depthMask(true); GL11.glDrawBuffer(GL11.GL_NONE); GL11.glReadBuffer(GL11.GL_NONE); GlStateManager._enablePolygonOffset(); GlStateManager._polygonOffset(1.1f, 4f);
			}

			GPU_PROFILER.end(profile);
			directionalShadowReady[i] = true; directionalShadowDirty[i] = false; directionalTerrainRevision[i] = sunTerrainRevision;
			shadowRenderCamX[i] = capCamX; shadowRenderCamY[i] = capCamY; shadowRenderCamZ[i] = capCamZ;
			shadowRenderSunX[i] = sunDirX; shadowRenderSunY[i] = sunDirY; shadowRenderSunZ[i] = sunDirZ;
			shadowRenderHalf[i] = SHADOW_LAYOUT.radiusBlocks[i];
		}
		lastSunEntityRevision = entityRevision;

		GlStateManager._glUseProgram(prevProgram); GlStateManager._glBindVertexArray(prevVao);
		if (prevCull) GlStateManager._enableCull(); else GlStateManager._disableCull();
		GlStateManager._blendFuncSeparate(prevBlendSrcRgb, prevBlendDstRgb, prevBlendSrcAlpha, prevBlendDstAlpha);
		if (prevBlend) GlStateManager._enableBlend(); else GlStateManager._disableBlend();
		GlStateManager._depthMask(prevDepthMask); GlStateManager._depthFunc(prevDepthFunc);
		if (prevDepth) GlStateManager._enableDepthTest(); else GlStateManager._disableDepthTest();
		if (prevPolygonOffset) { GlStateManager._enablePolygonOffset(); GlStateManager._polygonOffset(prevPolygonFactor, prevPolygonUnits); } else GlStateManager._disablePolygonOffset();
		restoreColorMask(prevColorMask);
		GlStateManager._activeTexture(GL13.GL_TEXTURE0); GlStateManager._bindTexture(prevTex0); GL33.glBindSampler(0, prevSampler0);
		GlStateManager._activeTexture(prevActive); GlStateManager._viewport(vp[0], vp[1], vp[2], vp[3]); GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
	}

	/** Gather nearby entities' bounding boxes into the box VBO (world coords) for shadow casting. */
	private static void buildEntityBoxes(MinecraftClient client) {
		if (boxScratch == null || boxProgram == 0 || !MoneyakShadersConfig.get().entityShadows) {
			boxVertCount = itemVertCount = blockItemVertCount = 0;
			entityShadowVboRevision = Long.MIN_VALUE;
			return;
		}
		long revision = EntityShadowCapture.readyRevision();
		if (revision == entityShadowVboRevision) return;
		boxVertCount = 0;
		// Upload the real entity-model geometry captured during the previous frame's entity pass
		// (CuboidCaptureMixin → EntityShadowCapture): head/body/limbs/armor/resource-pack cuboids,
		// in world space. Drawn into all directional shadow cascades as solid occluders → model-shaped shadows.
		float[] geo = EntityShadowCapture.readyBuffer();
		int floats = Math.min(EntityShadowCapture.readyCount(), (boxScratch.capacity() / 3) * 3);
		if (floats > 0) {
			boxScratch.clear();
			boxScratch.put(geo, 0, floats);
			boxScratch.flip();
			boxVertCount = floats / 3;
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, boxVbo);
			GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, boxScratch);
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
		}

		// Item geometry (pos+uv) captured last frame → item VBOs, drawn with the matching atlas +
		// alpha discard. Two streams: items-atlas (regular items) and block-atlas (block items).
		itemVertCount = 0;
		blockItemVertCount = 0;
		if (itemScratch != null && itemShadowProgram != 0) {
			itemVertCount = uploadItemStream(EntityShadowCapture.itemReadyBuffer(),
					EntityShadowCapture.itemReadyCount(), itemVbo);
			blockItemVertCount = uploadItemStream(EntityShadowCapture.blockItemReadyBuffer(),
					EntityShadowCapture.blockItemReadyCount(), blockItemVbo);
		}
		entityShadowVboRevision = revision;
	}

	/** Upload one captured item stream (5 floats/vert) into {@code vbo}; returns the vertex count. */
	private static int uploadItemStream(float[] geo, int count, int vbo) {
		int floats = Math.min(count, (itemScratch.capacity() / 5) * 5);
		if (floats <= 0) {
			return 0;
		}
		itemScratch.clear();
		itemScratch.put(geo, 0, floats);
		itemScratch.flip();
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
		GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, itemScratch);
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
		return floats / 5;
	}

	/** One multidraw of all opaque sections within {@code dist} chunks (no camera frustum). */
	/**
	 * Rebuild {@link #SHADOW_CASTERS}: sweep every camera-visible section towards the sun and collect
	 * the sections the ray passes through. Those are exactly the places geometry could sit and drop a
	 * shadow onto something visible; everything else casts only into already-hidden geometry.
	 *
	 * <p>Conservative by construction — the sweep marks whole sections and always includes the
	 * receiver itself, so a caster is only dropped when no visible receiver lies downsun of it within
	 * {@link #CASTER_SWEEP_SECTIONS}. Overshooting that range costs a little unnecessary drawing;
	 * undershooting would clip a genuine long shadow, hence the deliberately generous 8 sections
	 * (128 blocks) — well past the point where the far cascade's texel size dissolves a shadow.
	 */
	private static void rebuildShadowCasters() {
		SHADOW_CASTERS.clear();
		shadowCastersKnown = false;
		debugShadowReceivers = 0;
		if (VISIBLE_SECTIONS.isEmpty()) {
			return; // no graph yet → leave the set empty, which means "draw everything"
		}
		if (sunDirX == 0f && sunDirY == 0f && sunDirZ == 0f) {
			return; // sun direction not resolved this frame → don't cull on garbage
		}
		// Shadow length scales with 1/tan(elevation): a midday sun drops a shadow next to its caster,
		// a sunset one throws it across the valley. Sweeping a fixed distance would cut exactly the
		// long, low-sun shadows that are the most visible ones, so scale the reach by the sun's
		// height and clamp it to the far cascade's useful range.
		float elevation = Math.max(Math.abs(sunDirY), 0.15f);
		float reach = Math.min(CASTER_SWEEP_BLOCKS_MAX, CASTER_SWEEP_BLOCKS_MIN / elevation);
		// Step half a section at a time so a shallow, near-horizontal ray can't skip a section
		// diagonally; consecutive steps usually land in the same section, so skip the repeat add.
		int steps = Math.max(1, Math.round(reach / 8f));
		float sx = sunDirX * 8f, sy = sunDirY * 8f, sz = sunDirZ * 8f;
		// Iterate the smaller resident mesh map, not every abstract BFS cell. In a normal open scene the
		// graph contains thousands of empty/unreceived keys; looking each one up dominated rotation p99.
		for (Sec receiver : SECTIONS.values()) {
			long key = ChunkSectionPos.asLong(receiver.cx, receiver.sy, receiver.cz);
			if (!VISIBLE_SECTIONS.contains(key)) continue;
			// The visibility BFS is a conservative 360-degree connectivity graph and also contains
			// unknown/empty cells. Only an active mesh inside the real camera frustum can receive a
			// shadow this frame. Casters themselves may remain outside the frustum: the sunward sweep
			// below retains them whenever their shadow can land on one of these visible receivers.
			if (receiver.residency != Sec.Residency.ACTIVE
					|| receiver.count == 0 && receiver.wcount == 0) continue;
			float relX = (float) (receiver.bx - capCamX);
			float relY = (float) (receiver.by - capCamY);
			float relZ = (float) (receiver.bz - capCamZ);
			boolean nearCamera = Math.abs(relX + 8f) < 24f && Math.abs(relY + 8f) < 24f
					&& Math.abs(relZ + 8f) < 24f;
			if (!nearCamera && !sectionVisible(relX, relY, relZ,
					Math.max(currentBloatBlocks, SHADOW_RECEIVER_FRUSTUM_MARGIN_BLOCKS))) continue;
			debugShadowReceivers++;
			// Start at the receiver's centre and walk towards the sun.
			float x = (ChunkSectionPos.unpackX(key) << 4) + 8f;
			float y = (ChunkSectionPos.unpackY(key) << 4) + 8f;
			float z = (ChunkSectionPos.unpackZ(key) << 4) + 8f;
			SHADOW_CASTERS.add(key);
			long lastKey = key;
			for (int i = 0; i < steps; i++) {
				x += sx; y += sy; z += sz;
				long k = ChunkSectionPos.asLong(
						((int) Math.floor(x)) >> 4, ((int) Math.floor(y)) >> 4, ((int) Math.floor(z)) >> 4);
				if (k != lastKey) {
					SHADOW_CASTERS.add(k);
					lastKey = k;
				}
			}
		}
		// A valid but empty receiver set means that no terrain shadow can reach the current view. Keep
		// it distinct from the pre-BFS "unknown" state, where the conservative full-radius fallback is
		// still required while chunks are first arriving.
		shadowCastersKnown = true;
	}

	private static boolean shadowReceiverViewMoved() {
		if (!havePrevFwd || Float.isNaN(casterViewZ)) return true;
		return prevFwdX * casterViewX + prevFwdY * casterViewY + prevFwdZ * casterViewZ
				< SHADOW_RECEIVER_VIEW_DOT;
	}

	/** Refresh {@link #SHADOW_CASTERS} when the camera section, visibility graph or sun has moved. */
	private static boolean updateShadowCasters(BlockPos playerPos) {
		long camKey = ChunkSectionPos.asLong(playerPos.getX() >> 4, playerPos.getY() >> 4, playerPos.getZ() >> 4);
		// The sun crawls; only a direction change big enough to move a shadow by a section matters.
		boolean sunMoved = Float.isNaN(casterSunZ)
				|| Math.abs(sunDirX - casterSunX) + Math.abs(sunDirY - casterSunY)
						+ Math.abs(sunDirZ - casterSunZ) > 0.02f;
		boolean viewMoved = shadowReceiverViewMoved();
		boolean sectionMoved = camKey != casterSectionKey;
		long nowNs = System.nanoTime();
		boolean dirtyDue = castersDirty && nowNs >= nextShadowCasterDirtyRebuildNs;
		if (shadowCastersKnown && !sectionMoved && !sunMoved && !viewMoved && !dirtyDue) {
			return false;
		}
		casterSectionKey = camKey;
		casterSunX = sunDirX; casterSunY = sunDirY; casterSunZ = sunDirZ;
		casterViewX = prevFwdX; casterViewY = prevFwdY; casterViewZ = prevFwdZ;
		castersDirty = false;
		nextShadowCasterDirtyRebuildNs = nowNs + SHADOW_CASTER_DIRTY_INTERVAL_NS;
		long rebuildStartNs = System.nanoTime();
		rebuildShadowCasters();
		long rebuildNs = System.nanoTime() - rebuildStartNs;
		if (MoneyakShadersConfig.get().debugStats) {
			debugCasterRebuilds++;
			debugCasterRebuildTotalNs += rebuildNs;
			debugCasterRebuildMaxNs = Math.max(debugCasterRebuildMaxNs, rebuildNs);
		}
		return true;
	}

	/**
	 * Draw shadow casters inside one light volume.
	 *
	 * <p>{@code directionalCasterCull} is valid only for the sun cascades: their
	 * receiver set is swept towards the sun. A point-light cube map instead
	 * needs occluders in every direction from its source, independent of camera
	 * visibility or the current sun direction.
	 */
	private static int multidrawShadow(int pcx, int psy, int pcz, int dist,
			boolean directionalCasterCull, int cascadeIndex) {
		indirectScratch.clear();
		instanceScratch.clear();
		int n = 0;
		// Empty = "unknown" (no visibility graph / no sun vector yet) → fall back to the full radius.
		boolean cull = directionalCasterCull
				&& MoneyakShadersConfig.get().shadowCasterCulling && shadowCastersKnown;
		for (Sec s : SECTIONS.values()) {
			if (s.residency != Sec.Residency.ACTIVE) continue;
			if (s.count == 0 || n >= MAX_DRAWS) {
				continue;
			}
			if (Math.abs(s.cx - pcx) > dist || Math.abs(s.cz - pcz) > dist
					|| cascadeIndex < 0 && !inVerticalRange(s.sy, psy)) {
				continue;
			}
			if (cascadeIndex >= 0 && !sectionIntersectsSunCascade(s, cascadeIndex)) continue;
			if (cull && !SHADOW_CASTERS.contains(ChunkSectionPos.asLong(s.cx, s.sy, s.cz))) {
				continue; // nothing visible lies downsun of this section — its shadow can't be seen
			}
			indirectScratch.putInt(s.count);
			indirectScratch.putInt(1);
			indirectScratch.putInt((int) (s.iOff / Integer.BYTES));
			indirectScratch.putInt((int) (s.vOff / TerrainVertex.STRIDE));
			indirectScratch.putInt(n);
			instanceScratch.put(s.bx).put(s.by).put(s.bz).put(1f);
			n++;
		}
		if (n == 0) {
			return 0;
		}
		indirectScratch.flip();
		instanceScratch.flip();
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, instanceBuffer);
		GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, instanceScratch);
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
		GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, indirectBuffer);
		GL15.glBufferSubData(GL40.GL_DRAW_INDIRECT_BUFFER, 0L, indirectScratch);
		GL30.glBindVertexArray(sharedVao);
		GL43.glMultiDrawElementsIndirect(GL11.GL_TRIANGLES, GL11.GL_UNSIGNED_INT, 0L, n, 0);
		GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, 0);
		return n;
	}

	private static int multidrawWaterShadow(int pcx, int psy, int pcz, int dist, int cascadeIndex) {
		indirectScratch.clear(); instanceScratch.clear(); int n = 0;
		for (Sec s : SECTIONS.values()) {
			if (s.residency != Sec.Residency.ACTIVE || s.wcount == 0 || n >= MAX_DRAWS) continue;
			if (Math.abs(s.cx - pcx) > dist || Math.abs(s.cz - pcz) > dist) continue;
			if (!sectionIntersectsSunCascade(s, cascadeIndex)) continue;
			// Water transmission is itself a receiver-domain signal. Reusing the opaque caster set here
			// dropped lakes/oceans that were not selected as solid casters, leaving the mask completely white.
			indirectScratch.putInt(s.wcount).putInt(1).putInt((int)(s.wiOff / Integer.BYTES)).putInt((int)(s.wvOff / TerrainVertex.STRIDE)).putInt(n);
			instanceScratch.put(s.bx).put(s.by).put(s.bz).put(1f); n++;
		}
		if (n == 0) return 0;
		indirectScratch.flip(); instanceScratch.flip();
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, instanceBuffer); GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, instanceScratch); GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
		GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, indirectBuffer); GL15.glBufferSubData(GL40.GL_DRAW_INDIRECT_BUFFER, 0L, indirectScratch);
		GL30.glBindVertexArray(sharedVao); GL43.glMultiDrawElementsIndirect(GL11.GL_TRIANGLES, GL11.GL_UNSIGNED_INT, 0L, n, 0); GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, 0);
		return n;
	}

	/** Camera-independent light-space cull. It rejects only sections clipped by the shadow map itself. */
	private static boolean sectionIntersectsSunCascade(Sec section, int cascadeIndex) {
		double cx = section.bx + 8.0 - shadowCamX[cascadeIndex];
		double cy = section.by + 8.0 - shadowCamY[cascadeIndex];
		double cz = section.bz + 8.0 - shadowCamZ[cascadeIndex];
		double right = cx * shadowRightX + cy * shadowRightY + cz * shadowRightZ;
		double up = cx * shadowUpX + cy * shadowUpY + cz * shadowUpZ;
		float rightExtent = 8f * (Math.abs(shadowRightX) + Math.abs(shadowRightY) + Math.abs(shadowRightZ));
		float upExtent = 8f * (Math.abs(shadowUpX) + Math.abs(shadowUpY) + Math.abs(shadowUpZ));
		float half = orthoHalf[cascadeIndex];
		return Math.abs(right) <= half + rightExtent && Math.abs(up) <= half + upExtent;
	}

	private static int lightmapGlId(MinecraftClient client) {
		try {
			var view = client.gameRenderer.getLightmapTextureManager().getGlTextureView();
			if (view != null && view.texture() instanceof GlTexture gt) {
				return gt.getGlId();
			}
		} catch (Throwable t) {
			MoneyakShaders.LOGGER.error("[Plan C/GL] lightmap GL id lookup failed", t);
		}
		return 0;
	}

	private static int atlasGlId(MinecraftClient client) {
		if (cachedAtlasGlId > 0) {
			return cachedAtlasGlId;
		}
		try {
			var tex = client.getTextureManager().getTexture(SpriteAtlasTexture.BLOCK_ATLAS_TEXTURE);
			if (tex.getGlTexture() instanceof GlTexture gt) {
				cachedAtlasGlId = gt.getGlId();
			}
		} catch (Throwable t) {
			MoneyakShaders.LOGGER.error("[Plan C/GL] atlas GL id lookup failed", t);
		}
		return cachedAtlasGlId;
	}

	/** GL id of the 1.21.11 items atlas (textures/atlas/items.png) — item sprites live here, not on
	 *  the block atlas, so item-shadow alpha-discard must sample this texture. */
	private static int itemsAtlasGlId(MinecraftClient client) {
		if (cachedItemsAtlasGlId > 0) {
			return cachedItemsAtlasGlId;
		}
		try {
			var tex = client.getTextureManager().getTexture(SpriteAtlasTexture.ITEMS_ATLAS_TEXTURE);
			if (tex.getGlTexture() instanceof GlTexture gt) {
				cachedItemsAtlasGlId = gt.getGlId();
			}
		} catch (Throwable t) {
			MoneyakShaders.LOGGER.error("[Plan C/GL] items atlas GL id lookup failed", t);
		}
		return cachedItemsAtlasGlId;
	}

	private static void init() {
		try {
			// Phase 2.3: start worker pool for off-thread meshing
			int cores = Runtime.getRuntime().availableProcessors();
			// This is the terrain mesher actually used by the experimental renderer.  It used to
			// hard-code up to 12 threads, ignoring the user-visible chunk-builder limit and the
			// shared Worker-Main pool.  During a join that made the render-thread snapshot producer
			// compete with far more workers than the machine has cores.
			int meshThreads = MoneyakShadersConfig.get().effectiveChunkBuilderThreads();
			executor = new ChunkMeshExecutor(meshThreads);
			MoneyakShaders.LOGGER.info("[Plan C/GL] ChunkMeshExecutor started with {} threads (cpus={})", meshThreads, cores);

			program = GlShader.build(VS, FS);
			if (program == 0) {
				return;
			}
			uProj = GL20.glGetUniformLocation(program, "uProj");
			uView = GL20.glGetUniformLocation(program, "uView");
			uCamPos = GL20.glGetUniformLocation(program, "uCamPos");
			uAtlas = GL20.glGetUniformLocation(program, "uAtlas");
			uLightmap = GL20.glGetUniformLocation(program, "uLightmap");
			uFogColor = GL20.glGetUniformLocation(program, "uFogColor");
			uWaterY = GL20.glGetUniformLocation(program, "uWaterY");
			uFogStart2 = GL20.glGetUniformLocation(program, "uFogStart2");
			uFogEnd2 = GL20.glGetUniformLocation(program, "uFogEnd2");
			uFogStart = GL20.glGetUniformLocation(program, "uFogStart");
			uFogEnd = GL20.glGetUniformLocation(program, "uFogEnd");
			uTranslucent = GL20.glGetUniformLocation(program, "uTranslucent");
			uOit = GL20.glGetUniformLocation(program, "uOit");
			uCameraUnderwater = GL20.glGetUniformLocation(program, "uCameraUnderwater");
			uWaterSceneColor = GL20.glGetUniformLocation(program, "uWaterSceneColor");
			uWaterSceneDepth = GL20.glGetUniformLocation(program, "uWaterSceneDepth");
			uWaterWaveTex = GL20.glGetUniformLocation(program, "uWaterWaveTex");
			uInvProj = GL20.glGetUniformLocation(program, "uInvProj");
			uScreenSize = GL20.glGetUniformLocation(program, "uScreenSize");
			uWaterBumpiness = GL20.glGetUniformLocation(program, "uWaterBumpiness");
			uWaterReflection = GL20.glGetUniformLocation(program, "uWaterReflection");
			uWaterRefraction = GL20.glGetUniformLocation(program, "uWaterRefraction");
			uWaterFoam = GL20.glGetUniformLocation(program, "uWaterFoam");
			uWaterTransparency = GL20.glGetUniformLocation(program, "uWaterTransparency");
			uWaterAbsorption = GL20.glGetUniformLocation(program, "uWaterAbsorption");
			uWaterSpecular = GL20.glGetUniformLocation(program, "uWaterSpecular");
			uWaterSsrSteps = GL20.glGetUniformLocation(program, "uWaterSsrSteps");
		uLeavesFarDist = GL20.glGetUniformLocation(program, "uLeavesFarDist");
		uPackLodNear = GL20.glGetUniformLocation(program, "uPackLodNear");
		uPackLodFar = GL20.glGetUniformLocation(program, "uPackLodFar");
		uPackLodBias = GL20.glGetUniformLocation(program, "uPackLodBias");
			uTime = GL20.glGetUniformLocation(program, "uTime");
			uDepthOnly = GL20.glGetUniformLocation(program, "uDepthOnly");
			uWind = GL20.glGetUniformLocation(program, "uWind");
			uHeldLight = GL20.glGetUniformLocation(program, "uHeldLight");
			uHeldRadius = GL20.glGetUniformLocation(program, "uHeldRadius");
			uOreGlow = GL20.glGetUniformLocation(program, "uOreGlow");
			shaderStartMs = System.currentTimeMillis();
			oitCompositeProg = GlShader.build(OIT_VS, OIT_FS);
			if (oitCompositeProg != 0) {
				oitCompAccum = GL20.glGetUniformLocation(oitCompositeProg, "uAccum");
				oitCompReveal = GL20.glGetUniformLocation(oitCompositeProg, "uReveal");
				oitVao = GL30.glGenVertexArrays();
			}

			// Directional lighting + 3-cascade CSM uniforms.
			for (int i = 0; i < DIRECTIONAL_CASCADE_COUNT; i++) {
				uShadowMap[i] = GL20.glGetUniformLocation(program, "uShadowMap" + i);
				uShadowWaterMap[i] = GL20.glGetUniformLocation(program, "uShadowWaterMap" + i);
				uLightMVP[i] = GL20.glGetUniformLocation(program, "uLightMVP" + i);
				uShadowBias[i] = GL20.glGetUniformLocation(program, "uShadowBias" + i);
				uShadowNormalBias[i] = GL20.glGetUniformLocation(program, "uShadowNormalBias" + i);
				uShadowCam[i] = GL20.glGetUniformLocation(program, "uShadowOffset" + i);
				uCascadeEnd[i] = GL20.glGetUniformLocation(program, "uCascadeEnd" + i);
			}
			uCascadeBlend[0] = GL20.glGetUniformLocation(program, "uCascadeBlend0");
			uCascadeBlend[1] = GL20.glGetUniformLocation(program, "uCascadeBlend1");
			uShadowStrength = GL20.glGetUniformLocation(program, "uShadowStrength");
			uDayFactor = GL20.glGetUniformLocation(program, "uDayFactor");
			uSunDir = GL20.glGetUniformLocation(program, "uSunDir");
			uCelestialColor = GL20.glGetUniformLocation(program, "uCelestialColor");
			uCelestialMoon = GL20.glGetUniformLocation(program, "uCelestialMoon");
			uDirectionalDir = GL20.glGetUniformLocation(program, "uDirectionalDir");
			uDirectionalColor = GL20.glGetUniformLocation(program, "uDirectionalColor");
			uDirectionalStrength = GL20.glGetUniformLocation(program, "uDirectionalStrength");
			uSkyAmbientColor = GL20.glGetUniformLocation(program, "uSkyAmbientColor");
			uShadowAmbientColor = GL20.glGetUniformLocation(program, "uShadowAmbientColor");
			uSkyAmbientStrength = GL20.glGetUniformLocation(program, "uSkyAmbientStrength");
			uHorizonFogColor = GL20.glGetUniformLocation(program, "uHorizonFogColor");
			uFoliageTransmission = GL20.glGetUniformLocation(program, "uFoliageTransmission");
			uRainFactor = GL20.glGetUniformLocation(program, "uRainFactor");
			uPointLightMVP = GL20.glGetUniformLocation(program, "uPointMVP"); // mat4[POINT_MAX*6] cube-face array
			uPointShadowMap = GL20.glGetUniformLocation(program, "uPointShadowMap");
			uPointEntityShadowMap = GL20.glGetUniformLocation(program, "uPointEntityShadowMap");
			uPointVoxelMap = GL20.glGetUniformLocation(program, "uPointVoxelMap");
			uPointData = GL20.glGetUniformLocation(program, "uPointData"); // vec4[POINT_MAX]: cam-rel pos + range
			uPointCols = GL20.glGetUniformLocation(program, "uPointCols");
			uPointVoxelOrigin = GL20.glGetUniformLocation(program, "uPointVoxelOrigin");
			uPointVoxelValid = GL20.glGetUniformLocation(program, "uPointVoxelValid");
			uPointDynA = GL20.glGetUniformLocation(program, "uPointDynA");
			uPointSolidA = GL20.glGetUniformLocation(program, "uPointSolidA");
			uPointFaceMask = GL20.glGetUniformLocation(program, "uPointFaceMask");
			uPointFade_ = GL20.glGetUniformLocation(program, "uPointFade");
			uPointShadowStr = GL20.glGetUniformLocation(program, "uPointShadowStr");
			uSunUp = GL20.glGetUniformLocation(program, "uSunUp");
			uLightCount = GL20.glGetUniformLocation(program, "uLightCount");
			uLights = GL20.glGetUniformLocation(program, "uLights");
			uLightCols = GL20.glGetUniformLocation(program, "uLightCols");
			lightPosBuf = MemoryUtil.memAllocFloat(MAX_TERRAIN_LIGHTS * 4);
			lightColBuf = MemoryUtil.memAllocFloat(MAX_TERRAIN_LIGHTS * 3);
			shadowProgram = GlShader.build(SHADOW_VS, SHADOW_FS);
			if (shadowProgram != 0) {
				suLightMVP = GL20.glGetUniformLocation(shadowProgram, "uLightMVP");
				suCamPos = GL20.glGetUniformLocation(shadowProgram, "uCamPos");
				suCamPosLow = GL20.glGetUniformLocation(shadowProgram, "uCamPosLow");
				suTime = GL20.glGetUniformLocation(shadowProgram, "uTime");
				suWind = GL20.glGetUniformLocation(shadowProgram, "uWind");
				suShadowAtlas = GL20.glGetUniformLocation(shadowProgram, "uAtlas");
				suNearCut = GL20.glGetUniformLocation(shadowProgram, "uNearCut");
			}
			waterShadowProgram = GlShader.build(SHADOW_VS, WATER_SHADOW_FS);
			if (waterShadowProgram != 0) {
				swLightMVP = GL20.glGetUniformLocation(waterShadowProgram, "uLightMVP");
				swCamPos = GL20.glGetUniformLocation(waterShadowProgram, "uCamPos");
				swCamPosLow = GL20.glGetUniformLocation(waterShadowProgram, "uCamPosLow");
				swTime = GL20.glGetUniformLocation(waterShadowProgram, "uTime");
				swWind = GL20.glGetUniformLocation(waterShadowProgram, "uWind");
				swWaterWaveTex = GL20.glGetUniformLocation(waterShadowProgram, "uWaterWaveTex");
			}
			// entity-box shadow caster
			boxProgram = GlShader.build(BOX_VS, BOX_FS);
			if (boxProgram != 0) {
				suBoxLightMVP = GL20.glGetUniformLocation(boxProgram, "uLightMVP");
				suBoxCamPos = GL20.glGetUniformLocation(boxProgram, "uCamPos");
				suBoxNearCut = GL20.glGetUniformLocation(boxProgram, "uNearCut");
			}
			boxScratch = MemoryUtil.memAllocFloat(ENTITY_SHADOW_FLOATS);
			boxVbo = GL15.glGenBuffers();
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, boxVbo);
			GL44.glBufferStorage(GL15.GL_ARRAY_BUFFER, (long) ENTITY_SHADOW_FLOATS * Float.BYTES, GL44.GL_DYNAMIC_STORAGE_BIT);
			boxVao = GL30.glGenVertexArrays();
			GL30.glBindVertexArray(boxVao);
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, boxVbo);
			GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 3 * Float.BYTES, 0L);
			GL20.glEnableVertexAttribArray(0);
			GL30.glBindVertexArray(0);
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

			// Item shadow caster: pos(3)+uv(2) interleaved, atlas-sampled depth pass with alpha discard.
			itemShadowProgram = GlShader.build(ITEM_SHADOW_VS, ITEM_SHADOW_FS);
			if (itemShadowProgram != 0) {
				suItemLightMVP = GL20.glGetUniformLocation(itemShadowProgram, "uLightMVP");
				suItemCamPos = GL20.glGetUniformLocation(itemShadowProgram, "uCamPos");
				suItemAtlas = GL20.glGetUniformLocation(itemShadowProgram, "uAtlas");
				suItemNearCut = GL20.glGetUniformLocation(itemShadowProgram, "uNearCut");
			}
			itemScratch = MemoryUtil.memAllocFloat(ITEM_SHADOW_FLOATS);
			itemVbo = GL15.glGenBuffers();
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, itemVbo);
			GL44.glBufferStorage(GL15.GL_ARRAY_BUFFER, (long) ITEM_SHADOW_FLOATS * Float.BYTES, GL44.GL_DYNAMIC_STORAGE_BIT);
			itemVao = GL30.glGenVertexArrays();
			GL30.glBindVertexArray(itemVao);
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, itemVbo);
			GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 5 * Float.BYTES, 0L);
			GL20.glEnableVertexAttribArray(0);
			GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 5 * Float.BYTES, 3L * Float.BYTES);
			GL20.glEnableVertexAttribArray(1);
			GL30.glBindVertexArray(0);
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

			// Second stream for BLOCK-atlas item shadows (same pos+uv layout, drawn with the block atlas).
			blockItemVbo = GL15.glGenBuffers();
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, blockItemVbo);
			GL44.glBufferStorage(GL15.GL_ARRAY_BUFFER, (long) ITEM_SHADOW_FLOATS * Float.BYTES, GL44.GL_DYNAMIC_STORAGE_BIT);
			blockItemVao = GL30.glGenVertexArrays();
			GL30.glBindVertexArray(blockItemVao);
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, blockItemVbo);
			GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 5 * Float.BYTES, 0L);
			GL20.glEnableVertexAttribArray(0);
			GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 5 * Float.BYTES, 3L * Float.BYTES);
			GL20.glEnableVertexAttribArray(1);
			GL30.glBindVertexArray(0);
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

			atlasSampler = GL33.glGenSamplers();
			atlasSamplerConfigKey = Integer.MIN_VALUE;
			maxAnisoCached = GL11.glGetFloat(EXTTextureFilterAnisotropic.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT);

			vertexArena = new GlArena(GL15.GL_ARRAY_BUFFER, VERTEX_ARENA_BYTES);
			indexArena = new GlArena(GL15.GL_ELEMENT_ARRAY_BUFFER, INDEX_ARENA_BYTES);
			int sharedQuads = SHARED_QUAD_INDEX_MAX_VERTICES / 4;
			sharedQuadIndexCount = sharedQuads * 6;
			sharedQuadIndexOffset = indexArena.alloc((long) sharedQuadIndexCount * Integer.BYTES);
			if (sharedQuadIndexOffset < 0L) {
				throw new IllegalStateException("Unable to reserve shared terrain quad indices");
			}
			java.nio.IntBuffer sharedIndices = MemoryUtil.memAllocInt(sharedQuadIndexCount);
			for (int q = 0; q < sharedQuads; q++) {
				int v = q * 4;
				sharedIndices.put(v).put(v + 1).put(v + 2).put(v).put(v + 2).put(v + 3);
			}
			sharedIndices.flip();
			indexArena.uploadInts(sharedQuadIndexOffset, sharedIndices);
			MemoryUtil.memFree(sharedIndices);
			instanceBuffer = GL15.glGenBuffers();
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, instanceBuffer);
			GL44.glBufferStorage(GL15.GL_ARRAY_BUFFER, (long) MAX_DRAWS * 4 * Float.BYTES, GL44.GL_DYNAMIC_STORAGE_BIT);
			indirectBuffer = GL15.glGenBuffers();
			GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, indirectBuffer);
			GL44.glBufferStorage(GL40.GL_DRAW_INDIRECT_BUFFER, (long) MAX_DRAWS * 20, GL44.GL_DYNAMIC_STORAGE_BIT);
			GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, 0);

			// one VAO for all sections: per-vertex attribs (0-4) from the vertex arena, the
			// per-draw section origin (5) from the instance buffer (divisor 1), index arena bound.
			sharedVao = GL30.glGenVertexArrays();
			GL30.glBindVertexArray(sharedVao);
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vertexArena.buffer());
			GL20.glVertexAttribPointer(0, 3, GL11.GL_UNSIGNED_SHORT, false, TerrainVertex.STRIDE, TerrainVertex.OFF_POS);
			GL20.glEnableVertexAttribArray(0);
			GL20.glVertexAttribPointer(1, 3, GL11.GL_BYTE, true, TerrainVertex.STRIDE, TerrainVertex.OFF_NORMAL);
			GL20.glEnableVertexAttribArray(1);
			GL20.glVertexAttribPointer(2, 2, GL11.GL_UNSIGNED_SHORT, true, TerrainVertex.STRIDE, TerrainVertex.OFF_UV);
			GL20.glEnableVertexAttribArray(2);
			GL20.glVertexAttribPointer(3, 2, GL11.GL_UNSIGNED_BYTE, false, TerrainVertex.STRIDE, TerrainVertex.OFF_LIGHT);
			GL20.glEnableVertexAttribArray(3);
			GL20.glVertexAttribPointer(4, 4, GL11.GL_UNSIGNED_BYTE, true, TerrainVertex.STRIDE, TerrainVertex.OFF_COLOR);
			GL20.glEnableVertexAttribArray(4);
			// material tag (per-vertex, from the vertex arena): 0=default, 1=leaves.
			GL20.glVertexAttribPointer(6, 1, GL11.GL_UNSIGNED_BYTE, false, TerrainVertex.STRIDE, TerrainVertex.OFF_MATERIAL);
			GL20.glEnableVertexAttribArray(6);
			// baked coloured block-light: rgb = hue, a = strength (normalized bytes).
		GL20.glVertexAttribPointer(7, 4, GL11.GL_UNSIGNED_BYTE, true, TerrainVertex.STRIDE, TerrainVertex.OFF_LIGHT_TINT);
		GL20.glEnableVertexAttribArray(7);
		// AO and animation weight are separate from tint/albedo; shader lighting owns both uses.
		GL20.glVertexAttribPointer(8, 2, GL11.GL_UNSIGNED_BYTE, true, TerrainVertex.STRIDE, TerrainVertex.OFF_AO);
		GL20.glEnableVertexAttribArray(8);
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, instanceBuffer);
			GL20.glVertexAttribPointer(5, 4, GL11.GL_FLOAT, false, 4 * Float.BYTES, 0L);
			GL20.glEnableVertexAttribArray(5);
			GL33.glVertexAttribDivisor(5, 1);
			GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, indexArena.buffer());
			GL30.glBindVertexArray(0);
			GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

			indirectScratch = MemoryUtil.memAlloc(MAX_DRAWS * 20);
			instanceScratch = MemoryUtil.memAllocFloat(MAX_DRAWS * 4);
		} catch (Throwable t) {
			MoneyakShaders.LOGGER.error("[Plan C/GL] section render init failed", t);
			program = 0;
		}
	}

	/**
	 * Rebuild the 6 frustum planes from the current captured proj×view matrix.
	 * Gribb-Hartmann method for a column-major (JOML) combined matrix:
	 * planes are in camera-relative world space (same space as our section vertices).
	 */
	private static void updateFrustum() {
		if (frustumRevision == renderFrameRevision)
			return;

		frustumRevision = renderFrameRevision;
		capturedProjection.mul(capturedModelView, combinedScratch);
		Matrix4f m = combinedScratch;
		// LEFT   (qx+qw >= 0): a=m00+m03, b=m10+m13, c=m20+m23, d=m30+m33
		frustumPlanes[ 0]=m.m00()+m.m03(); frustumPlanes[ 1]=m.m10()+m.m13(); frustumPlanes[ 2]=m.m20()+m.m23(); frustumPlanes[ 3]=m.m30()+m.m33();
		// RIGHT  (qw-qx >= 0)
		frustumPlanes[ 4]=m.m03()-m.m00(); frustumPlanes[ 5]=m.m13()-m.m10(); frustumPlanes[ 6]=m.m23()-m.m20(); frustumPlanes[ 7]=m.m33()-m.m30();
		// BOTTOM (qy+qw >= 0)
		frustumPlanes[ 8]=m.m01()+m.m03(); frustumPlanes[ 9]=m.m11()+m.m13(); frustumPlanes[10]=m.m21()+m.m23(); frustumPlanes[11]=m.m31()+m.m33();
		// TOP    (qw-qy >= 0)
		frustumPlanes[12]=m.m03()-m.m01(); frustumPlanes[13]=m.m13()-m.m11(); frustumPlanes[14]=m.m23()-m.m21(); frustumPlanes[15]=m.m33()-m.m31();
		// NEAR   (qz+qw >= 0)
		frustumPlanes[16]=m.m02()+m.m03(); frustumPlanes[17]=m.m12()+m.m13(); frustumPlanes[18]=m.m22()+m.m23(); frustumPlanes[19]=m.m32()+m.m33();
		// FAR    (qw-qz >= 0)
		frustumPlanes[20]=m.m03()-m.m02(); frustumPlanes[21]=m.m13()-m.m12(); frustumPlanes[22]=m.m23()-m.m22(); frustumPlanes[23]=m.m33()-m.m32();
		for (int i = 0; i < 6; i++) {
			int o = i * 4;
			float a = frustumPlanes[o], b = frustumPlanes[o + 1], c = frustumPlanes[o + 2];
			frustumPlaneLen[i] = (float) Math.sqrt(a * a + b * b + c * c);
		}
		// Rotation-driven prefetch: compare current camera forward against the previous frame's.
		// Modelview has camera basis in its rotation; row 2 of the modelview is -forward.
		float fx = -capturedModelView.m02();
		float fy = -capturedModelView.m12();
		float fz = -capturedModelView.m22();
		float len = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
		if (len > 1e-6f) { fx /= len; fy /= len; fz /= len; }
		int maxBloat = MoneyakShadersConfig.get().frustumRotationPrefetchBlocks;
		if (havePrevFwd && maxBloat > 0) {
			float dot = fx * prevFwdX + fy * prevFwdY + fz * prevFwdZ;
			// Any turn >~3° per frame (dot < ~0.9986) triggers full bloat. Slow drift keeps decay.
			if (dot < 0.9986f) {
				currentBloatBlocks = maxBloat;
			} else {
				currentBloatBlocks *= 0.86f; // ~15-frame half-life back to 0
				if (currentBloatBlocks < 0.5f) currentBloatBlocks = 0f;
			}
		} else {
			currentBloatBlocks = 0f;
		}
		prevFwdX = fx; prevFwdY = fy; prevFwdZ = fz;
		havePrevFwd = true;
	}

	/**
	 * Test a 16³ section AABB (origin relX,relY,relZ in camera-relative space)
	 * against the current frustum. Returns false if entirely outside any plane.
	 * Uses the "positive vertex" trick for an efficient AABB-plane test.
	 */
	/** Camera-relative sphere vs the current captured frustum (block-entity culling). True if no matrix yet. */
	public static boolean isSphereInFrustum(
			float relX, float relY, float relZ, float radius) {

		if (!haveMatrix)
			return true;

		for (int i = 0; i < 6; i++) {
			int o = i * 4;

			float a = frustumPlanes[o];
			float b = frustumPlanes[o + 1];
			float c = frustumPlanes[o + 2];
			float d = frustumPlanes[o + 3];

			float len = frustumPlaneLen[i];

			if (len > 1e-6f
					&& a * relX + b * relY + c * relZ + d < -radius * len) {
				return false;
			}
		}

		return true;
	}

	private static boolean sectionVisible(float relX, float relY, float relZ) {
		return sectionVisible(relX, relY, relZ, currentBloatBlocks);
	}

	private static boolean sectionVisible(float relX, float relY, float relZ, float bloat) {
		for (int i = 0; i < 6; i++) {
			int o = i * 4;
			float a = frustumPlanes[o], b = frustumPlanes[o + 1], c = frustumPlanes[o + 2], d = frustumPlanes[o + 3];
			float px = a >= 0 ? relX + 16 : relX;
			float py = b >= 0 ? relY + 16 : relY;
			float pz = c >= 0 ? relZ + 16 : relZ;
			float sd = a * px + b * py + c * pz + d;
			// Bloat: extend the frustum outward by `bloat` blocks along each plane normal. Plane's
			// signed distance is in un-normalized space, so scale by |normal| to convert blocks.
			if (sd < -bloat * frustumPlaneLen[i]) return false;
		}
		return true;
	}

	/**
	 * Build the indirect + instance buffers for the requested layer's non-empty sections
	 * and issue ONE glMultiDrawElementsIndirect.
	 *
	 * <p>Phase 4.2: sections not in {@link #VISIBLE_SECTIONS} are skipped (occluded).
	 * Phase 4.3: translucent sections are sorted far-to-near before building the buffer
	 * so alpha-blending composites correctly (back-to-front).
	 */
	private static int multidrawLayer(boolean translucent) {
		updateFrustum();

		indirectScratch.clear();
		instanceScratch.clear();

		int n = 0;

		MoneyakShadersConfig cfg = MoneyakShadersConfig.get();

		Iterable<Sec> iter;

		/*
		* True only when the selected iterable already passed BOTH:
		*
		* - visibility/occlusion graph
		* - camera frustum
		*
		* The old code precullled OPAQUE_SCRATCH and then repeated sectionVisible()
		* for every entry again in the final loop.
		*/
		boolean opaqueAlreadyCulled = false;

		if (translucent && !weightedOitActive) {
			long camCellX = (long) Math.floor(capCamX * 2.0);
			long camCellY = (long) Math.floor(capCamY * 2.0);
			long camCellZ = (long) Math.floor(capCamZ * 2.0);

			boolean rebuildOrder =
					translucentOrderCachedRevision != translucentOrderRevision
					|| translucentOrderCamCellX != camCellX
					|| translucentOrderCamCellY != camCellY
					|| translucentOrderCamCellZ != camCellZ;

			if (rebuildOrder) {
				TRANSLUCENT_SCRATCH.clear();

				for (Sec s : SECTIONS.values()) {
					if (s.residency == Sec.Residency.ACTIVE
							&& s.wcount > 0) {

						TRANSLUCENT_SCRATCH.add(s);
					}
				}
			}

			boolean orderSensitive = false;

			if (rebuildOrder) {
				for (Sec s : TRANSLUCENT_SCRATCH) {
					if (!s.wOnlyWater) {
						orderSensitive = true;
						break;
					}
				}
			}

			if (rebuildOrder
					&& orderSensitive
					&& TRANSLUCENT_SCRATCH.size() > 1) {

				float cx = (float) capCamX;
				float cy = (float) capCamY;
				float cz = (float) capCamZ;

				TRANSLUCENT_SCRATCH.sort((a, b) -> Float.compare(
						nearestAabbDistSq(b, cx, cy, cz),
						nearestAabbDistSq(a, cx, cy, cz)));
			}

			if (rebuildOrder) {
				translucentOrderCachedRevision = translucentOrderRevision;
				translucentOrderCamCellX = camCellX;
				translucentOrderCamCellY = camCellY;
				translucentOrderCamCellZ = camCellZ;
			}

			/*
			* Mixed water + glass sections need fine quad sorting.
			*/
			int mixedResortBudget = 24;

			for (int i = TRANSLUCENT_SCRATCH.size() - 1;
					i >= 0 && mixedResortBudget > 0;
					i--) {

				Sec s = TRANSLUCENT_SCRATCH.get(i);

				if (!s.wMixedWater || s.wCentroids == null) {
					continue;
				}

				float relX = (float) (s.bx - capCamX);
				float relY = (float) (s.by - capCamY);
				float relZ = (float) (s.bz - capCamZ);

				boolean nearCam =
						Math.abs(relX + 8f) < 24f
						&& Math.abs(relY + 8f) < 24f
						&& Math.abs(relZ + 8f) < 24f;

				if ((nearCam || sectionVisible(relX, relY, relZ))
						&& maybeResortQuads(s)) {

					mixedResortBudget--;
				}
			}

			int resortBudget = 16;

			float ccx = (float) capCamX;
			float ccy = (float) capCamY;
			float ccz = (float) capCamZ;

			for (int i = TRANSLUCENT_SCRATCH.size() - 1;
					i >= 0 && resortBudget > 0;
					i--) {

				Sec s = TRANSLUCENT_SCRATCH.get(i);

				if (s.wCentroids == null || s.wOnlyWater) {
					continue;
				}

				float rx = s.bx - ccx;
				float ry = s.by - ccy;
				float rz = s.bz - ccz;

				if (rx * rx + ry * ry + rz * rz > 128f * 128f) {
					break;
				}

				if (maybeResortQuads(s)) {
					resortBudget--;
				}
			}

			iter = TRANSLUCENT_SCRATCH;

		} else if (translucent) {

			/*
			* Weighted OIT does not need ordering.
			*
			* Keep the existing conservative behaviour here: translucent geometry
			* is NOT CPU-frustum culled because the captured planes may be stale
			* relative to the late translucent pass.
			*/
			iter = SECTIONS.values();

		} else if (cfg.sortOpaqueFrontToBack) {

			OPAQUE_SCRATCH.clear();

			double cx = capCamX;
			double cy = capCamY;
			double cz = capCamZ;

			for (Sec s : SECTIONS.values()) {
				if (s.count == 0 || s.residency != Sec.Residency.ACTIVE) {
					continue;
				}

				/*
				* This was effectively missing from the actual terrain draw path.
				*
				* Empty/unknown VISIBLE_SECTIONS remains conservative because
				* isVisibleSectionKey() returns true in that state.
				*/
				long key = ChunkSectionPos.asLong(
						s.cx,
						s.sy,
						s.cz);

				if (!isVisibleSectionKey(key)) {
					continue;
				}

				float relX = (float) (s.bx - cx);
				float relY = (float) (s.by - cy);
				float relZ = (float) (s.bz - cz);

				boolean nearCam =
						Math.abs(relX + 8f) < 24f
						&& Math.abs(relY + 8f) < 24f
						&& Math.abs(relZ + 8f) < 24f;

				if (!nearCam && !sectionVisible(relX, relY, relZ)) {
					continue;
				}

				float centreX = relX + 8f;
				float centreY = relY + 8f;
				float centreZ = relZ + 8f;

				float distanceSq =
						centreX * centreX
						+ centreY * centreY
						+ centreZ * centreZ;

				OPAQUE_SCRATCH.enqueue(s, distanceSq);
			}

			OPAQUE_SCRATCH.order();

			iter = OPAQUE_SCRATCH;

			/*
			* Everything in OPAQUE_SCRATCH already passed occlusion + frustum.
			*/
			opaqueAlreadyCulled = true;

		} else {

			iter = SECTIONS.values();
		}

		long appearanceNow = System.nanoTime();

		/*
		* Don't call MoneyakShadersConfig.get() for every drawn section.
		*/
		boolean timelineEnabled = cfg.chunkLoadTimelineEnabled;

		for (Sec s : iter) {
			if (s.residency != Sec.Residency.ACTIVE) {
				continue;
			}

			int count = translucent ? s.wcount : s.count;

			if (count == 0 || n >= MAX_DRAWS) {
				continue;
			}

			float relX = (float) (s.bx - capCamX);
			float relY = (float) (s.by - capCamY);
			float relZ = (float) (s.bz - capCamZ);

			/*
			* Sorted opaque sections were already tested while filling
			* OPAQUE_SCRATCH. Do not perform the exact same visibility work twice.
			*
			* The unsorted opaque path still needs both tests here.
			*
			* Translucent keeps the existing conservative behaviour.
			*/
			if (!translucent && !opaqueAlreadyCulled) {

				long key = ChunkSectionPos.asLong(
						s.cx,
						s.sy,
						s.cz);

				if (!isVisibleSectionKey(key)) {
					continue;
				}

				boolean nearCam =
						Math.abs(relX + 8f) < 24f
						&& Math.abs(relY + 8f) < 24f
						&& Math.abs(relZ + 8f) < 24f;

				if (!nearCam && !sectionVisible(relX, relY, relZ)) {
					continue;
				}
			}

			long iOff = translucent ? s.wiOff : s.iOff;
			long vOff = translucent ? s.wvOff : s.vOff;

			indirectScratch.putInt(count);
			indirectScratch.putInt(1);
			indirectScratch.putInt(
					(int) (iOff / Integer.BYTES));
			indirectScratch.putInt(
					(int) (vOff / TerrainVertex.STRIDE));
			indirectScratch.putInt(n);

			if (!translucent
					&& s.appearanceStartNs == Long.MIN_VALUE) {

				s.appearanceStartNs = appearanceNow;
			}

			float visibility = translucent
					? 1f
					: SceneAppearance.visibility(
							appearanceNow - s.appearanceStartNs,
							(double) (relX + 8) * (relX + 8)
							+ (double) (relY + 8) * (relY + 8)
							+ (double) (relZ + 8) * (relZ + 8));

			instanceScratch
					.put((float) (s.bx - capCamX))
					.put((float) (s.by - capCamY))
					.put((float) (s.bz - capCamZ))
					.put(visibility);

			if (timelineEnabled) {
				long key = ChunkSectionPos.asLong(
						s.cx,
						s.sy,
						s.cz);

				if (DRAWN_ONCE.add(key)) {
					com.moneyakshaders.client.ChunkLoadTimeline.mark(
							key,
							com.moneyakshaders.client.ChunkLoadTimeline.PHASE_FIRST_DRAWN);
				}
			}

			n++;
		}

		if (n == 0) {
			return 0;
		}

		indirectScratch.flip();
		instanceScratch.flip();

		GL15.glBindBuffer(
				GL15.GL_ARRAY_BUFFER,
				instanceBuffer);

		GL15.glBufferSubData(
				GL15.GL_ARRAY_BUFFER,
				0L,
				instanceScratch);

		GL15.glBindBuffer(
				GL15.GL_ARRAY_BUFFER,
				0);

		GL15.glBindBuffer(
				GL40.GL_DRAW_INDIRECT_BUFFER,
				indirectBuffer);

		GL15.glBufferSubData(
				GL40.GL_DRAW_INDIRECT_BUFFER,
				0L,
				indirectScratch);

		GL30.glBindVertexArray(sharedVao);

		return n;
	}

	private static boolean shouldUseDepthPrePass(
			MoneyakShadersConfig cfg) {

		/*
		* Front-to-back opaque ordering already gives the hardware early-Z
		* a very good chance to reject hidden fragments.
		*
		* Doing a complete second geometry pass on top of that frequently costs
		* more vertex/raster work than it saves in fragment shading.
		*
		* Keep the explicit depth pre-pass available when front-to-back sorting
		* is disabled.
		*/
		return cfg.depthPrePass
				&& !cfg.sortOpaqueFrontToBack;
	}
	
	/**
	 * Squared distance from the camera to the closest point of a section's 16³ AABB (0 when the camera
	 * is inside it). Used to order the translucent pass back-to-front; see {@link #multidrawLayer}.
	 */
	private static float nearestAabbDistSq(Sec s, float cx, float cy, float cz) {
		float dx = Math.max(0f, Math.max(s.bx - cx, cx - (s.bx + 16f)));
		float dy = Math.max(0f, Math.max(s.by - cy, cy - (s.by + 16f)));
		float dz = Math.max(0f, Math.max(s.bz - cz, cz - (s.bz + 16f)));
		return dx * dx + dy * dy + dz * dz;
	}

	/** Issue the built multidraw. Buffer/VAO must already be bound by {@link #multidrawLayer}. */
	private static void issueMultidraw(int n) {
		GL43.glMultiDrawElementsIndirect(GL11.GL_TRIANGLES, GL11.GL_UNSIGNED_INT, 0L, n, 0);
	}
}
