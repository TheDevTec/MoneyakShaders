package com.moneyakshaders;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Tiny properties-based config so it can be read from mixin handlers that run
 * during static initialization of game classes (no JSON library, no game
 * classes touched).
 */
public final class MoneyakShadersConfig {
	private static final String FILE_NAME = "moneyakshaders.properties";
	private static final int MAX_THREADS = 255;
	private static volatile MoneyakShadersConfig instance;

	/** Last global renderer profile selected in Video Settings. Individual controls remain editable. */
	public String globalQualityProfile = "CLASSIC";
	/** One-time migration marker for the rebuilt atmospheric/water visual defaults. */
	public int visualPolicyVersion = 0;

	/**
	 * Size of the shared background worker pool ("Worker-Main" threads) that
	 * drives world generation, the server light engine and chunk IO. Vanilla
	 * 1.21.11 sizes it as {@code cores - 1} and the {@code max.bg.threads}
	 * property can only shrink it, never grow it. A value &gt; 0 overrides the
	 * size exactly (1-255). 0 uses vanilla sizing unless automatic balancing is enabled,
	 * in which case it reserves capacity for the dedicated terrain mesh pool and client thread.
	 */
	public int backgroundThreads = 0;

public int cinematicPolicyVersion = 0;

public int atmosphereDensity = 42;
public int atmosphereHorizon = 64;
public int ambientStrength = 68;

public int sunWarmth = 72;
public int moonBrightness = 50;

/** Legacy UI value. Directional softness is now controlled per cascade. */
public int shadowSoftness = 50;

/** Legacy water controls kept for settings/config compatibility. */
public int waterTransparency = 72;
public int waterAbsorption = 46;
public int waterSpecular = 58;

public int biomeBlendRadius = 6;
public int biomeTintVibrance = 106;

public int cloudCoverage = 48;
public int cloudDensity = 62;
public int cloudSilverLining = 68;
public int cloudShadowStrength = 68;
public int cloudSpeed = 38;

	/**
	 * Size of the dedicated chunk-mesh building pool. Vanilla runs mesh
	 * building on the same shared worker pool as world generation and
	 * lighting, so they steal threads from each other while chunks load.
	 * This mod gives mesh building its own pool. 0 = auto: half the cores,
	 * at least 2.
	 */
	public int chunkBuilderThreads = 0;

	/**
	 * Maximum number of pending chunk mesh build tasks queued for the dedicated
	 * mesh pool. 0 means unbounded queue.
	 */
	public int chunkBuilderQueueLimit = 256;

	/**
	 * Whether to drop the oldest queued chunk mesh task when the queue is full.
	 * This keeps the dedicated pool responsive when chunk rebuild demand spikes.
	 */
	public boolean chunkBuilderDropOldestWhenFull = true;

	/**
	 * Use a FIFO-style async pool for chunk mesh building.
	 * This can improve fairness in the dedicated mesh pool when many long-running
	 * chunk tasks are queued from multiple sources.
	 */
	public boolean chunkBuilderAsyncMode = true;

	/**
	 * Priority for dedicated chunk mesh threads.
	 * The default is slightly below normal so mesh building does not starve render
	 * and client-critical work.
	 */
	public int chunkBuilderThreadPriority = Thread.NORM_PRIORITY - 1;

	/**
	 * Size of the general background worker pool for non-mesh async tasks.
	 * 0 = auto: available processors minus chunk builder threads, at least 2.
	 */
	public int backgroundWorkerThreads = 0;

	/**
	 * Maximum number of pending general async optimization tasks.
	 * 0 means unbounded queue.
	 */
	public int backgroundQueueLimit = 512;

	/**
	 * Number of threads and queue size for the async lighting engine.
	 */
	public int lightingEngineThreads = 0; // 0 = auto
	public int lightingQueueLimit = 1024; // increased default to reduce rejections under bursty loads

	/**
	 * Maximum number of lighting task submissions per second. 0 = unlimited.
	 */
	public int lightingSubmissionsPerSecond = 0; // correctness: never delay a visible light generation

	/**
	 * Whether to drop the oldest background task when the queue is full.
	 */
	public boolean backgroundDropOldestWhenFull = true;

	/**
	 * Maximum number of queued rebuild tasks before low-priority chunk rebuilds
	 * are skipped.
	 */
	public int chunkRebuildBackpressureThreshold = 64;

	/**
	 * Skip non-important chunk rebuilds when the dedicated mesh queue is already
	 * large. This prevents rebuild backlog spikes from interfering with render
	 * responsiveness.
	 */
	public boolean chunkRebuildDropLowPriorityWhenQueueHigh = true;

	/**
	 * Distance in chunks around the player where low-priority preprocessing
	 * is still allowed when the mesh queue is backed up.
	 */
	public int chunkPrecomputePriorityDistance = 5;

	/**
	 * How long duplicate render tasks are remembered before being allowed again.
	 * Same chunk/section re-schedules within this window are coalesced.
	 */
	public int renderDuplicateSkipWindowMs = 100;

	/**
	 * Threshold for skipping non-important section re-render requests when the
	 * dedicated mesh queue is already backed up.
	 */
	public int sectionRenderBackpressureThreshold = 128;

	/**
	 * Skip non-important section renders when render backlog is high.
	 */
	public boolean sectionRenderDropLowPriorityWhenQueueHigh = true;

	/**
	 * Automatically reduce the client-side shared background worker pool when a
	 * dedicated chunk mesh pool is active. This keeps total worker thread usage
	 * closer to the available CPU count and reduces thread oversubscription.
	 */
	public boolean autoBalanceBackgroundThreads = true;

	/**
	 * Enable teleportation anomaly detection and physics guard.
	 * If a player teleports anomalously, aggressive optimizations are disabled.
	 */
	public boolean enableTeleportationGuard = true;

	/**
	 * Disable aggressive occlusion culling for glass.
	 * Set to true if you experience glass-related teleportation or clipping.
	 */
	public boolean disableAggressiveGlassCulling = false;

	/**
	 * Disable glass LOD optimization.
	 * Set to true if you experience LOD-related geometry issues.
	 */
	public boolean disableGlassLOD = false;

	/**
	 * Enable culling of chunks that belong to unused/hidden scene regions.
	 * This can reduce render scheduling for caves, remote tunnels, and unseen builds.
	 */
	public boolean enableUnusedSceneCulling = true;

	/**
	 * Enable the in-game FPS overlay (top-left). Toggleable via config.
	 */
	public boolean fpsHudEnabled = true;

	/**
	 * Rate limit for animated texture/atlas background tasks (tasks per second).
	 * 0 = unlimited.
	 */
	public int animatedTasksPerSecond = 60;
	/** Approximate animated-atlas pixels blitted per client tick; 0 keeps vanilla unlimited uploads. */
	public int animatedTextureUploadPixelsPerTick = 4 * 1024 * 1024;

	/**
	 * Rate limit for water precomputation tasks (tasks per second).
	 * 0 = unlimited.
	 */
	public int waterTasksPerSecond = 20;

	/**
	 * Enable block face culling optimization.
	 * Prevents rendering of block faces that are completely hidden by adjacent solid blocks.
	 * Significantly reduces geometry sent to GPU, especially for stone/dirt caves and mines.
	 */
	public boolean enableBlockFaceCulling = true;

	/**
	 * Enable texture aggregation for similar adjacent blocks.
	 * Groups blocks with the same material/texture (e.g., stone variants, wood types)
	 * into batches for more efficient rendering. Reduces draw calls.
	 */
	public boolean enableTextureAggregation = true;

	/**
	 * Enable debug logging for block optimization.
	 * Logs chunk optimization statistics to help diagnose performance.
	 */
	public boolean debugBlockOptimization = false;

	/**
	 * Minimum interval in milliseconds between client light engine update
	 * passes. Vanilla runs doLightUpdates() every single frame on the render
	 * thread, which is where most of the light-engine FPS cost comes from.
	 * 50 ms = 20 Hz (same cadence the server uses). 0 = vanilla behaviour.
	 */
	public int lightUpdateIntervalMs = 0;

	/**
	 * Capacity of vanilla's per-thread brightness/AO cache used during chunk
	 * mesh building. Vanilla hardcodes 100 entries, which thrashes constantly
	 * (one 16^3 section with smooth lighting samples thousands of positions).
	 * Trades RAM for FPS. Minimum 100 (vanilla).
	 */
	public int brightnessCacheSize = 32768;

	/**
	 * How long a rendered entity's light level is cached, in milliseconds.
	 * Item frames with maps, armor stands and other entities query the light
	 * engine every frame; with hundreds of them (image walls, animated props)
	 * this is a large per-frame cost. The cache is invalidated immediately
	 * when the entity moves to a different block. 0 = disabled.
	 */
	public int entityLightCacheMs = 100;

	/**
	 * Reuse the last posed ModelPart transforms for distant living entities between bounded refreshes.
	 * Geometry is still submitted every frame, so movement, lighting, outlines and texture animation stay live.
	 */
	public boolean entityAnimationLod = true;
	/** Distance in blocks that always keeps full per-frame pose animation. */
	public int entityAnimationNearDistance = 24;
	/** Distance in blocks where the mid-rate tier changes to the far-rate tier. */
	public int entityAnimationMidDistance = 48;
	/** Maximum pose refresh rate for the middle tier. */
	public int entityAnimationMidFps = 30;
	/** Maximum pose refresh rate beyond the middle tier. */
	public int entityAnimationFarFps = 15;

	/**
	 * Particles spawned farther than this many blocks from the camera are
	 * dropped before they are created. 0 = no limit (vanilla).
	 */
	public int particleSpawnDistanceLimit = 48;

	/**
	 * Run custom-renderer light refresh asynchronously: vanilla publishes light
	 * on its safe client thread, while affected custom terrain sections are
	 * coalesced, snapshotted and meshed by the bounded worker pipeline. The
	 * legacy non-custom renderer retains its dedicated light-thread path.
	 */
	public boolean asyncLightUpdates = true;

	/**
	 * Light-update render budget: max coalesced light-driven section re-render schedules applied per
	 * frame. Smooths the chunk-load light burst. 0 = unlimited (apply all each frame).
	 */
	public int lightRenderBudget = 0;

	/**
	 * Block entities (chests, ender chests, heads, banners, signs, shulker
	 * boxes, ...) farther than this many blocks from the camera are not
	 * rendered at all. Vanilla draws them up to 64 blocks. 0 = vanilla.
	 */
	public int blockEntityRenderDistance = 32;
	/** Cull block entities outside the camera frustum (behind you / off-screen) before their per-frame
	 *  render state is built. Big win for custom-model server BEs. Beacon beams etc. are exempt upstream. */
	public boolean blockEntityFrustumCull = true;
	/** Bake STATIC block entities (server custom-model decorations, …) once into a replay cache, so
	 *  their per-frame render-state extract + model tessellation is skipped. Opt-in (off by default):
	 *  vanilla animated BEs are blacklisted, but custom server renderers can't be proven static, so the
	 *  user turns this on when their world benefits (decoration-heavy servers). See {@code BakedBlockEntities}. */
	public boolean blockEntityBake = false;

	/**
	 * Occlusion culling: Minecraft-style section visibility graph. Skips terrain sections AND block
	 * entities (chests behind walls) the camera has no sight line to. Off = radius/frustum culling only.
	 */
	public boolean occlusionCulling = true;

	/** Cancel ALL vanilla section building (terrain draw is ours; block entities come from the chunk-map
	 * feed). Removes vanilla mesh CPU + uploads entirely — the biggest chunk-loading cost cut. */
	public boolean skipVanillaChunkBuilds = true;

	/** Sort opaque terrain near→far so the GPU early-Z skips hidden fragments (cuts overdraw shading). */
	public boolean sortOpaqueFrontToBack = true;

	/** Don't even build geometry for sections the occlusion graph says are hidden (caves behind rock).
	 * Saves mesh CPU + GPU memory; a section streams in when it first becomes visible. */
	public boolean occlusionMeshing = true;

	/**
	 * While a normal multiplayer player is on the surface, defer sections that are provably below the
	 * local terrain height and outside the camera neighbourhood. They are admitted immediately in a
	 * cave or spectator mode, so this saves underground CPU/GPU/light work without hiding reachable
	 * terrain when the player actually needs it.
	 */
	public boolean deferUndergroundSections = true;

	/** Extra blocks below the local terrain surface before a section is considered safely deferrable. */
	public int undergroundDeferDepth = 0;

	/** Surface-player policy for hidden underground sections: MAXIMUM_FPS sleeps then evicts them. */
	public String undergroundSleepMode = "MAXIMUM_FPS";
	/** 1 means the player's surrounding 3x3x3 section cube is never put to sleep. */
	public int undergroundActiveRadiusSections = 1;
	/** Delay before a sleeping underground section releases its GPU allocation. */
	public int undergroundEvictionDelayMs = 1500;
	/** Reserved GPU cache allowance for sleeping underground geometry; 0 = evict all after delay. */
	public int undergroundSleepGpuBudgetMb = 0;

	/** Depth pre-pass: fill depth cheaply first, then run the expensive terrain shader only on the
	 * fragments that end up visible (depthFunc==EQUAL) — eliminates overdraw shading. */
	public boolean depthPrePass = true;

	/** DEV ONLY (no UI): save screenshots/opl_debug.png every ~5 s in-world, for headless visual checks. */
	public boolean debugAutoScreenshot = false;

	/**
	 * Skip rendering block entities that are behind opaque walls (cached
	 * raycast from the camera). Glass and other transparent blocks do not
	 * count as walls. Never blinks anything out: unknown positions render
	 * until confirmed hidden.
	 */
	public boolean blockEntityOcclusionCulling = false;

	/**
	 * How long a block entity visibility raycast result stays cached (ms).
	 */
	public int blockEntityOcclusionRecheckMs = 400;

	/**
	 * Maximum occlusion raycasts per ~16 ms frame bucket. Keeps the
	 * worst-case culling cost bounded with thousands of block entities.
	 */
	public int blockEntityOcclusionRaycastsPerFrame = 12;

	/**
	 * Skip vanilla blob shadows under entities entirely. Redundant when a
	 * shader pack renders real shadow maps.
	 */
	public boolean disableEntityShadows = true;

	/**
	 * Never compile chunk section meshes synchronously on the render thread.
	 * Vanilla's "By Player"/"Nearby" chunk-builder modes (and important nearby
	 * rebuilds) call ChunkBuilder.rebuild() inline during the frame, which
	 * stalls rendering - this is what F3 attributes to "rendering" while
	 * chunks are loading or blocks change. With our dedicated mesh pool every
	 * rebuild can go async instead. Costs a few frames of latency before a
	 * block you just changed re-meshes; with shaders and a fast GPU that is a
	 * good trade for a stall-free render thread.
	 */
	public boolean forceAsyncChunkRebuilds = true;

	/**
	 * Dump one line per second with per-subsystem activity rates (light,
	 * render scheduling, mesh pool, block-entity culling, entity light cache,
	 * particles). Diagnostic - turn off once tuned.
	 */
	public boolean debugStats = false;

	/**
	 * Occlusion-cull entities in sections the terrain visibility BFS found no sight line to (behind
	 * walls) — the cheap section-graph version (an O(1) set lookup, no per-entity raycast). Aimed at
	 * server "mob" decorations built from DisplayEntities that sit behind walls and are otherwise
	 * exempt from distance culling. Needs occlusionCulling on. Conservative (kept whenever the section
	 * has ANY sight line, so it rarely pops); never culls the player, camera entity or glowing entities.
	 * Off by default — opt in if behind-wall entities tank your FPS.
	 */
	public boolean entityOcclusionCulling = false;

	/** Entities closer than this (blocks) are never occlusion-culled. */
	public int entityCullingMinDistance = 8;

	/** How long an entity visibility raycast result stays cached (ms). */
	public int entityOcclusionRecheckMs = 150;

	/** Max entity occlusion raycasts per ~16 ms frame bucket. */
	public int entityOcclusionRaycastsPerFrame = 24;

	/**
	 * Hard cap on simultaneously active particles. Bounds the tick+render cost
	 * of big bursts (explosions, dense clouds). 0 = vanilla (per-group caps).
	 */
	public int maxActiveParticles = 6000;

	/**
	 * Max chunk-section mesh uploads per frame. Vanilla uploads the whole queue
	 * each frame, so a sudden burst (piston arrays, explosions, a wall of new
	 * chunks) stalls one frame. Bounding it spreads the burst over the next few
	 * frames. 0 = vanilla (no limit).
	 */
	public int maxChunkUploadsPerFrame = 16;

	/**
	 * Maximum vertex/index bytes copied into the terrain arenas in one frame. Large sections are
	 * uploaded in hidden slices and published only after the final slice, preserving atomic meshes.
	 * 0 disables byte slicing and retains the legacy whole-section upload path.
	 */
	public int maxChunkUploadBytesPerFrame = 2 * 1024 * 1024;

	/**
	 * Maximum number of completed custom section meshes kept in RAM while waiting for GPU upload.
	 * This is a memory/backpressure limit, not an upload count: when it is reached workers pause after
	 * their current calculation instead of letting a fast server chunk burst allocate an unbounded
	 * native-mesh backlog. 0 = automatic (four normal upload batches, at least two per mesh worker).
	 */
	public int maxReadySectionMeshes = 32;

	/** Max render-thread source cells copied into immutable section snapshots per frame. */
	/** Emergency count cap only; the time budget is the normal limiter. High enough to finish a normal section promptly. */
	public int sectionSnapshotCellsPerFrame = 24576;

	/** Bound on incomplete immutable snapshots waiting for the mesh workers. */
	public int maxPendingSectionSnapshots = 20;
	/** Reusable immutable snapshot buffers retained after worker completion; 0 disables pooling. */
	public int maxPooledSectionSnapshots = 20;
	/** One-time migration marker for multiplayer section-streaming throughput. */
	public int chunkStreamingPolicyVersion = 0;

	/**
	 * Stop resource-pack custom visuals (item displays, dropped items, frames,
	 * armor stands with large textures) from being frustum-culled when their
	 * big model is still on screen but their tiny bounding box left the frustum.
	 */
	public boolean fixLargeModelCulling = true;

	/** Extra blocks added to the cull box of those entities (each side). */
	public int largeModelCullMargin = 4;

	/**
	 * Per-type render-distance caps for cheap, numerous entities (farms, dense
	 * bases). Beyond this many blocks they are not rendered. 0 = vanilla.
	 */
	public int itemRenderDistance = 24;
	public int xpOrbRenderDistance = 16;
	public int projectileRenderDistance = 32;

	/**
	 * General render-distance cap (blocks) for all other entities — mobs especially.
	 * Beyond this they are not rendered, which is the big lever for mob farms / spawners
	 * with hundreds of entities clustered in view. Deterministic by distance (no raycast,
	 * no pop-in). ALWAYS-rendered exceptions: players, anything with a custom name
	 * (nametags), armor stands, display entities, item frames and paintings — the things
	 * servers use to show custom textures/holograms. 0 = vanilla (no cap).
	 */
	public int mobRenderDistance = 64;

	/**
	 * Far static decorations (armor stands, display entities, frames and paintings) are skipped before
	 * render-state extraction. This is a visual-only sleep policy: server updates and collision remain
	 * untouched. Named, glowing and targeted decorations are always kept. 0 = vanilla distance.
	 */
	public int staticEntityRenderDistance = 96;

	/**
	 * Render-distance cap for billboard particles. Vanilla already frustum-culls
	 * particles (out of view = not rendered); this drops ones that are in view
	 * but far away. 0 = unlimited (only vanilla frustum cull).
	 */
	public int particleRenderDistance = 32;

	/**
	 * EXPERIMENTAL: enable the from-scratch custom terrain renderer (Plan C,
	 * a Sodium/Iris replacement under construction). Default OFF and not wired
	 * to anything that affects rendering yet - see plan.md. Do NOT enable with
	 * Sodium/Iris installed.
	 */
	// Plan C renderer is now the BUILT-IN renderer (no longer optional). The field is kept so existing
	// gates compile, but it's forced true at load and there's no UI toggle anymore.
	public boolean experimentalRenderer = true;

	/**
	 * EXPERIMENTAL renderer only: leaves closer than this many blocks keep vanilla
	 * "fancy" cutout transparency (you can see a little way into them); beyond this
	 * distance they render fully opaque, which removes the alpha-test + mipmap
	 * shimmer that distant leaves otherwise produce (ugly noise at 60+ / 200+ blocks).
	 * 0 = always fancy (no distance opacity).
	 * NOTE: keep this FAR (≈64). Too small (e.g. 3) turns nearly all leaves opaque, and opaque
	 * overlapping foliage z-fights — the tiny wind sway then flips the depth order every frame, so
	 * swaying leaves flicker where they intersect. Far away the sway is sub-pixel, so no flicker there.
	 */
	public int leavesOpaqueDistance = 64;

	/**
	 * EXPERIMENTAL renderer only (v0 section cache): sections that scroll out of range (e.g. on a
	 * teleport) keep their meshed geometry in a small bounded GPU cache instead of being freed. When
	 * you return to those coordinates — spawn, a /home, a hub you hop back to — the cached mesh draws
	 * INSTANTLY to fill the teleport hole while the server resends the chunks, and each section is
	 * swapped for the fresh server data the moment its chunk reloads. In-session only, dropped on world
	 * change. Bounded (~a few hundred sections) so it can never starve live terrain.
	 */
	public boolean sectionCache = true;

	/**
	 * EXPERIMENTAL renderer only (v1): persist the section cache to disk per server + dimension, so a
	 * spawn / home you log back into is already cached across sessions. Written on leave, loaded on join,
	 * and discarded when the enabled resource packs change (baked atlas UVs would otherwise be wrong).
	 * Requires {@link #sectionCache}. Off = the cache is RAM-only (in-session, like v0).
	 */
	public boolean sectionCacheDisk = true;

	/**
	 * EXPERIMENTAL renderer only: sample the block atlas with mipmaps so distant
	 * terrain is smooth instead of aliased/noisy. Uses NEAREST_MIPMAP_LINEAR (keeps the
	 * pixel-art look, only smooths the distance transition). Requires Mipmap Levels &gt; 0
	 * in Video Settings; falls back to plain nearest when mipmaps are off. Default on.
	 */
	public boolean terrainMipmaps = true;

	/**
	 * EXPERIMENTAL renderer only: anisotropic filtering level for the block atlas
	 * (1 = off, typical 2/4/8/16, clamped to the GPU max). Sharpens textures viewed at
	 * grazing angles (long floors/roads into the distance). Only active when mipmaps are on.
	 */
	public int terrainAnisotropy = 4;

	/**
	 * Positive mip LOD bias for the terrain atlas. A positive value chooses a coarser mip earlier,
	 * which is especially useful for resource packs with very large block-model textures.
	 * 0 = exact requested mip; 1..4 trades distant detail for lower texture bandwidth.
	 */
	public int terrainTextureLodBias = 0;
	/** Start/end distance for the gradual high-resolution resource-pack terrain texture LOD. */
	public int terrainTextureLodNear = 32;
	public int terrainTextureLodFar = 128;
	/** Generate and select lower-resolution variants for distant high-resolution entity/equipment textures. */
	public boolean entityTextureDistanceLod = true;
	public int entityTextureLodNear = 32;
	public int entityTextureLodFar = 128;

	/** EXPERIMENTAL renderer: gentle slow wind sway for grass/plants/leaves (water stays flat). */
	public boolean windSway = true;

	/** First-person hand sway: subtle bobbing of the held arm/item driven by player motion. */
	public boolean handSway = false;

	/** Smooth lighting strength (0..100): blend each vertex toward the average light of the blocks around
	 * its corner, so adjacent block light levels fade instead of stepping. 0 = vanilla flat per-block. */
	public int lightSmoothing = 100;

	/** Pre-warm item models (inventory + open container) a few per tick so first render doesn't spike. */
	public boolean prewarmItemModels = true;

	// --- toast filtering ---
	/** Show tutorial toasts (the corner notifications teaching new players). Default OFF. */
	public boolean toastTutorial = false;
	/** Show advancement toasts. Default ON. */
	public boolean toastAdvancement = true;
	/** Show the "Chat messages can't be verified" insecure-server toast. Default OFF. */
	public boolean toastChatInsecure = false;
	/** Show resource-pack load/copy failure toasts. Default ON. */
	public boolean toastResourcePack = true;

	// --- chat history ---
	/** Max chat messages kept (in memory + persisted to disk forever). Default 16 000. */
	public int chatHistorySize = 16000;
	/** Max sent messages / commands kept in the up-arrow history (persisted via command_history.txt). Default 64. */
	public int sentHistorySize = 64;

	/** Ambient waterfall particles: falling droplets along flowing water + rising mist at the base. */
	public boolean waterfallParticles = true;

	/** Water impact effects: splash when an entity/item falls into water + bubbles from underwater open chests. */
	public boolean waterSplash = true;

	/** Splash "ripples" on exposed water surfaces while it's raining. */
	public boolean rainRipples = true;

	/** OptiFine-format random entity textures (ETF replacement): optifine/random/entity rules. */
	public boolean etfRandomTextures = true;

	/** OptiFine CIT (custom item textures by name/enchant/damage) — items, armor, elytra. */
	public boolean citTextures = true;
	/** Log CIT rule/sprite misses and items rendering missingno — for diagnosing pack issues. */
	public boolean debugCit = true;
	/** Mirror block textures + pack-added blocks.json sources into the ITEMS atlas — old packs (pre-1.21.4)
	 * declare custom item textures only for the blocks atlas, which since the items-atlas split makes their
	 * item models bake against two atlases and fail (missingno). Costs a few MB of VRAM. */
	public boolean oldPackItemAtlasFix = true;

	/** Distance (blocks) under which the image stays pixel-sharp; FXAA fades in beyond it. 0 = everywhere. */

	/** Replace vanilla's flat cloud grid with the renderer's procedural two-layer fantasy clouds. */
	public boolean fantasyClouds = true;

	/** Treat every resource pack as version-compatible (old OptiFine packs load without warnings/stripping). */
	public boolean forceOldPackCompat = true;

	/** OptiFine-format emissive entity textures (_e suffix) — glowing overlay at full brightness. */
	public boolean etfEmissive = true;

	/** Max distance (blocks) an entity still casts a model shadow. 0 = unlimited. */
	public int entityShadowDistance = 32;

	/** Soft ground mist drifting during rain. */
	public boolean rainMist = true;

	/** Animated water surface: the whole water body slowly rises/falls (geometric, in the VS). */

	/** Custom (sliding box-blur) biome-tint blend — same result as vanilla but no chunk-load spike. */
	public boolean fastBiomeBlend = false;

	/** EXPERIMENTAL renderer: subtle day/night colour grade (warm day, cool night, full-moon lift). */
	public boolean dayNightTint = true;

	/** EXPERIMENTAL renderer: a held light-emitting block (torch, glowstone…) lights around the player, tinted by its colour. */
	public boolean dynamicHeldLight = true;

	/** EXPERIMENTAL renderer: ores (iron/gold/diamond/… but NOT coal) glow faintly in the dark. */
	public boolean glowingOres = true;

	/**
	 * Dynamic entity lighting (works regardless of the experimental renderer): a held light
	 * block, dropped light items, burning entities (fire / flaming arrows) and glow squid lift
	 * the block-light of nearby entities so they aren't pitch-black in the dark.
	 */
	public boolean dynamicLighting = true;

	/**
	 * EXPERIMENTAL renderer: coloured point lights cast onto the TERRAIN by placed light blocks
	 * (soul torch blue, glowstone, lava…), dropped light items and burning entities. The 16
	 * nearest sources to the camera are applied.
	 */
	public boolean terrainPointLights = true;



	/**
	 * Etapa A post-processing on the full world frame: ACES filmic tonemapping + bloom on
	 * bright/emissive pixels + vignette + chromatic aberration (the "Complementary" colour
	 * signature). Values below are percentages. postExposure compensates ACES (100 = neutral,
	 * higher = brighter).
	 */
	public boolean postProcessing = true;
public int postExposure = 100;
public int postBloom = 20;
public int postVignette = 7;
public int postSaturation = 105;
public int postGodRays = 58;
/** 0 final, 1 shafts, 2 depth, 3 shadow visibility, 4 water shadow, 5 extinction. */
public int postDebugView = 0;

public int waterBumpiness = 90;
public int waterReflection = 72;
public int waterRefraction = 58;

/** Legacy controls. New water path currently does not use foam/SSR. */
public int waterFoam = 0;
public int waterSsrSteps = 0;

public boolean celOutlines = false;
public int outlineStrength = 28;
public boolean fxaa = true;

public boolean weightedTransparency = true;
public boolean depthSafeTextDisplays = true;

public boolean sunShadows = true;

/** Legacy fallback resolution. */
public int shadowResolution = 2048;

/** Three-cascade directional shadow layout. */
public int shadowNearResolution = 4096;
public int shadowMidResolution = 3072;
public int shadowFarResolution = 2048;

public boolean adaptiveShadowQuality = true;
public int shadowGpuBudgetMs = 8;
public int shadowMinResolution = 1024;
public int pointShadowResolution = 512;

/**
 * Legacy user-facing base bias. The renderer derives final constant/normal bias
 * independently for each cascade from its texel size.
 */
public int shadowBias = 10;

/** NEAR cascade radius in chunks. Default = 48 blocks. */
public int shadowNearChunks = 3;

/** MID cascade radius in chunks. Default = 112 blocks. */
public int shadowMidChunks = 7;

/** FAR cascade radius in chunks. Default = 224 blocks. */
public int shadowDistanceChunks = 14;

public boolean shadowCasterCulling = false;
public boolean entityShadows = true;
public int shadowStrength = 76;

/** Legacy values retained for settings compatibility. */
public int shadowSharpDistance = 40;
public int shadowFarBlur = 2;
public int shadowCascadeBlend = 10;

public boolean pointLightShadows = true;
public int pointShadowPolicyVersion = 0;
public int pointShadowStrength = 60;

	/** EXPERIMENTAL renderer colour grading (%, 100 = neutral). Raw albedo×light looks flat/dark/
	 *  desaturated vs shader packs; these add brightness, contrast and saturation for a punchier look. */
	public int worldBrightness = 100;
	public int worldContrast = 106;
	public int worldSaturation = 106;

	// --- spec §22 fáze 1: measurement + adaptive frame budget ---
	/** Record per-frame wall-clock time into a ring buffer and expose p50/p95/p99/max. */
	public boolean frameProfilerEnabled = true;
	/** Record per-section pipeline event timestamps (received→meshed→uploaded→drawn). */
	// Timeline records concurrent-map entries and timestamps for every streamed section.  It is an
	// on-demand diagnostic (the FPS overlay exposes it when enabled), not normal gameplay work.
	public boolean chunkLoadTimelineEnabled = false;
	/** Master toggle for {@link com.moneyakshaders.client.FrameWorkBudget}. When false, hard caps (maxChunkUploadsPerFrame) apply. */
	public boolean frameBudgetEnabled = true;
	/** Target ms per frame the budget aims for (60 fps ≈ 16 ms). Rolling avg above this shrinks bucket allowances. */
	public int frameBudgetTargetMs = 14;
	/** Ceiling ms per frame the mesh-upload bucket may consume. Spec §15 orientace: 1-2 ms. */
	public int frameBudgetMeshUploadMs = 2;
	/** Ceiling ms per frame for render-thread immutable section extraction. Keeps incoming chunk bursts out of p99. */
	public int frameBudgetSnapshotMs = 5;
	/** Ceiling ms per frame the shadow-update bucket may consume. Spec §15 orientace: 0.5-1 ms. */
	public int frameBudgetShadowMs = 1;
	/** Ceiling ms per frame the light-apply bucket may consume. Spec §15 orientace: 0.5-1 ms. */
	public int frameBudgetLightApplyMs = 1;

	/**
	 * Spec §5.2: on fast camera rotation, expand the frustum by this many blocks in every direction
	 * for a few frames so sections that are about to enter view are drawn immediately instead of
	 * popping in. 0 = disabled. Decays to 0 within ~15 frames of the rotation stopping.
	 */
	public int frustumRotationPrefetchBlocks = 24;

	/**
	 * Hot path - called tens of thousands of times per second from every
	 * culler check, entity light lookup, particle spawn and render hook.
	 * Double-checked volatile read so the steady state never takes a lock
	 * (the method used to be {@code synchronized}, i.e. a monitor enter/exit
	 * on every single call).
	 */
	public static MoneyakShadersConfig get() {
		MoneyakShadersConfig local = instance;
		if (local != null) return local;

		synchronized (MoneyakShadersConfig.class) {
			local = instance;
			if (local != null) return local;

			local = load();
			loadCinematicFields(local);

			boolean changed = applyCinematicDefaults(local);
			changed |= sanitizeVisualFields(local);

			instance = local;
			if (changed) save();
			return local;
		}
	}

	private static boolean sanitizeVisualFields(MoneyakShadersConfig c) {
		boolean changed = false;

		changed |= set(c.atmosphereDensity, clamp(c.atmosphereDensity, 0, 200), v -> c.atmosphereDensity = v);
		changed |= set(c.atmosphereHorizon, clamp(c.atmosphereHorizon, 0, 200), v -> c.atmosphereHorizon = v);
		changed |= set(c.ambientStrength, clamp(c.ambientStrength, 0, 200), v -> c.ambientStrength = v);
		changed |= set(c.sunWarmth, clamp(c.sunWarmth, 0, 100), v -> c.sunWarmth = v);
		changed |= set(c.moonBrightness, clamp(c.moonBrightness, 0, 200), v -> c.moonBrightness = v);

		changed |= set(c.cloudCoverage, clamp(c.cloudCoverage, 0, 100), v -> c.cloudCoverage = v);
		changed |= set(c.cloudDensity, clamp(c.cloudDensity, 0, 100), v -> c.cloudDensity = v);
		changed |= set(c.cloudSilverLining, clamp(c.cloudSilverLining, 0, 100), v -> c.cloudSilverLining = v);
		changed |= set(c.cloudShadowStrength, clamp(c.cloudShadowStrength, 0, 100), v -> c.cloudShadowStrength = v);
		changed |= set(c.cloudSpeed, clamp(c.cloudSpeed, 0, 200), v -> c.cloudSpeed = v);

		changed |= set(c.postExposure, clamp(c.postExposure, 25, 250), v -> c.postExposure = v);
		changed |= set(c.postBloom, clamp(c.postBloom, 0, 200), v -> c.postBloom = v);
		changed |= set(c.postVignette, clamp(c.postVignette, 0, 100), v -> c.postVignette = v);
		changed |= set(c.postSaturation, clamp(c.postSaturation, 0, 200), v -> c.postSaturation = v);
		changed |= set(c.postGodRays, clamp(c.postGodRays, 0, 200), v -> c.postGodRays = v);
		changed |= set(c.postDebugView, clamp(c.postDebugView, 0, 5), v -> c.postDebugView = v);

		changed |= set(c.waterBumpiness, clamp(c.waterBumpiness, 0, 200), v -> c.waterBumpiness = v);
		changed |= set(c.waterReflection, clamp(c.waterReflection, 0, 200), v -> c.waterReflection = v);
		changed |= set(c.waterRefraction, clamp(c.waterRefraction, 0, 200), v -> c.waterRefraction = v);

		changed |= set(c.shadowNearResolution, clampResolution(c.shadowNearResolution, 1024, 8192), v -> c.shadowNearResolution = v);
		changed |= set(c.shadowMidResolution, clampResolution(c.shadowMidResolution, 1024, 8192), v -> c.shadowMidResolution = v);
		changed |= set(c.shadowFarResolution, clampResolution(c.shadowFarResolution, 512, 8192), v -> c.shadowFarResolution = v);
		changed |= set(c.shadowMinResolution, clampResolution(c.shadowMinResolution, 512, 4096), v -> c.shadowMinResolution = v);
		changed |= set(c.pointShadowResolution, clampResolution(c.pointShadowResolution, 128, 2048), v -> c.pointShadowResolution = v);

		changed |= set(c.shadowNearChunks, clamp(c.shadowNearChunks, 2, 8), v -> c.shadowNearChunks = v);
		changed |= set(c.shadowMidChunks, clamp(c.shadowMidChunks, c.shadowNearChunks + 1, 16), v -> c.shadowMidChunks = v);
		changed |= set(c.shadowDistanceChunks, clamp(c.shadowDistanceChunks, c.shadowMidChunks + 1, 32), v -> c.shadowDistanceChunks = v);

		changed |= set(c.shadowBias, clamp(c.shadowBias, 1, 64), v -> c.shadowBias = v);
		changed |= set(c.shadowStrength, clamp(c.shadowStrength, 0, 100), v -> c.shadowStrength = v);
		changed |= set(c.pointShadowStrength, clamp(c.pointShadowStrength, 0, 100), v -> c.pointShadowStrength = v);
		changed |= set(c.shadowGpuBudgetMs, clamp(c.shadowGpuBudgetMs, 1, 40), v -> c.shadowGpuBudgetMs = v);

		return changed;
	}

	private static int clampResolution(int value, int min, int max) {
		return clamp(value, min, max);
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private static boolean set(int oldValue, int newValue, java.util.function.IntConsumer setter) {
		if (oldValue == newValue) return false;
		setter.accept(newValue);
		return true;
	}

	private static void loadCinematicFields(MoneyakShadersConfig c) {
		Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
		if (!Files.isRegularFile(path)) return;

		Properties p = new Properties();
		try (InputStream in = Files.newInputStream(path)) {
			p.load(in);
		} catch (IOException ignored) {
			return;
		}

		c.cinematicPolicyVersion = readInt(p, "cinematicPolicyVersion", c.cinematicPolicyVersion);

		c.atmosphereDensity = readInt(p, "atmosphereDensity", c.atmosphereDensity);
		c.atmosphereHorizon = readInt(p, "atmosphereHorizon", c.atmosphereHorizon);
		c.ambientStrength = readInt(p, "ambientStrength", c.ambientStrength);
		c.sunWarmth = readInt(p, "sunWarmth", c.sunWarmth);
		c.moonBrightness = readInt(p, "moonBrightness", c.moonBrightness);
		c.shadowSoftness = readInt(p, "shadowSoftness", c.shadowSoftness);

		c.waterTransparency = readInt(p, "waterTransparency", c.waterTransparency);
		c.waterAbsorption = readInt(p, "waterAbsorption", c.waterAbsorption);
		c.waterSpecular = readInt(p, "waterSpecular", c.waterSpecular);

		c.biomeBlendRadius = readInt(p, "biomeBlendRadius", c.biomeBlendRadius);
		c.biomeTintVibrance = readInt(p, "biomeTintVibrance", c.biomeTintVibrance);

		c.cloudCoverage = readInt(p, "cloudCoverage", c.cloudCoverage);
		c.cloudDensity = readInt(p, "cloudDensity", c.cloudDensity);
		c.cloudSilverLining = readInt(p, "cloudSilverLining", c.cloudSilverLining);
		c.cloudShadowStrength = readInt(p, "cloudShadowStrength", c.cloudShadowStrength);
		c.cloudSpeed = readInt(p, "cloudSpeed", c.cloudSpeed);

		c.shadowMidResolution = readInt(p, "shadowMidResolution", c.shadowMidResolution);
		c.shadowMidChunks = readInt(p, "shadowMidChunks", c.shadowMidChunks);
	}

	private static boolean applyCinematicDefaults(MoneyakShadersConfig c) {
		boolean changed = false;

		if (c.cinematicPolicyVersion < 1) {
			c.fantasyClouds = true;
			c.fastBiomeBlend = true;
			c.dayNightTint = true;
			c.postProcessing = true;
			c.sunShadows = true;

			c.postExposure = 98;
			c.postBloom = 22;
			c.postVignette = 8;
			c.postSaturation = 108;
			c.postGodRays = 68;

			c.worldBrightness = 98;
			c.worldContrast = 108;
			c.worldSaturation = 108;

			c.waterBumpiness = 92;
			c.waterReflection = 72;
			c.waterRefraction = 58;
			c.waterFoam = 32;
			c.waterSsrSteps = 0;

			c.shadowStrength = 74;
			c.shadowSharpDistance = 36;
			c.shadowFarBlur = 2;
			c.shadowCascadeBlend = 10;
			c.shadowNearResolution = Math.max(c.shadowNearResolution, 4096);
			c.shadowFarResolution = Math.max(c.shadowFarResolution, 2048);

			c.cinematicPolicyVersion = 1;
			changed = true;
		}

		if (c.cinematicPolicyVersion < 2) {
			boolean oldShadowDefaults =
					c.shadowNearChunks == 6
					&& c.shadowDistanceChunks == 12
					&& c.shadowNearResolution == 4096
					&& c.shadowFarResolution == 2048;

			boolean oldPostDefaults =
					c.postExposure == 98
					&& c.postBloom == 22
					&& c.postVignette == 8
					&& c.postSaturation == 108
					&& c.postGodRays == 68;

			boolean oldWaterDefaults =
					c.waterBumpiness == 92
					&& c.waterReflection == 72
					&& c.waterRefraction == 58
					&& c.waterFoam == 32
					&& c.waterSsrSteps == 0;

			if (oldShadowDefaults) {
				c.shadowNearChunks = 3;
				c.shadowMidChunks = 7;
				c.shadowDistanceChunks = 14;
				c.shadowNearResolution = 4096;
				c.shadowMidResolution = 3072;
				c.shadowFarResolution = 2048;
				c.shadowBias = 10;
				c.shadowStrength = 76;
			}

			if (oldPostDefaults) {
				c.postExposure = 100;
				c.postBloom = 20;
				c.postVignette = 7;
				c.postSaturation = 105;
				c.postGodRays = 58;
			}

			if (oldWaterDefaults) {
				c.waterBumpiness = 90;
				c.waterReflection = 72;
				c.waterRefraction = 58;
				c.waterFoam = 0;
				c.waterSsrSteps = 0;
			}

			c.cinematicPolicyVersion = 2;
			changed = true;
		}

		return changed;
	}

	public int effectiveBackgroundThreads() {
		if (backgroundThreads > 0) {
			return Math.min(MAX_THREADS, backgroundThreads);
		}

		int cpus = cpuCount();
		int defaultThreads = Math.max(1, cpus - 1);
		if (autoBalanceBackgroundThreads && FabricLoader.getInstance().getEnvironmentType() == net.fabricmc.api.EnvType.CLIENT) {
			int reserved = effectiveChunkBuilderThreads();
			// The client render/game thread needs a CPU while a fresh world streams in.  The old
			// calculation used every core for background + mesh workers (and UtilMixin accidentally
			// ignored it altogether), so a 16-core client ran 15 shared + 8 mesh threads.  Keep one
			// core out of the pools.  The async light dispatcher is itself a single additional
			// thread, so reserve one more slot for it during a packet/light burst.
			int balanced = Math.max(1, cpus - reserved - 2);
			return Math.min(defaultThreads, balanced);
		}
		return defaultThreads;
	}

	/**
	 * Worker pools are CPU-bound, so more threads than cores never finishes work faster — it only adds
	 * context switches and, critically, lets the pools preempt the RENDER thread. That shows up as
	 * exactly the symptom this mod exists to remove: chunks stream in fine, but the frame rate dips
	 * while they do. A hand-edited config asking for 32 + 32 + 16 threads on a 16-core machine is
	 * ~4× oversubscribed, so every effective count is clamped to what the hardware actually has.
	 */
	private static int cpuCount() {
		return Math.max(1, Runtime.getRuntime().availableProcessors());
	}

	public int effectiveChunkBuilderThreads() {
		int cpus = cpuCount();
		// Leave at least one core for the render thread and one for the game loop.
		int ceiling = Math.max(1, cpus - 2);
		// Immutable snapshot production is the bounded serial feeder. Flight recordings on a 16-core
		// client showed an 8-thread mesh pool consuming a 0-4 item queue while all six Worker-Main
		// threads stayed busy receiving/decoding chunks. In automatic mode, four fast mesh workers are
		// enough for the measured ~0.5-1.5 ms jobs and return four cores to the upstream shared pool.
		return chunkBuilderThreads > 0
				? Math.min(Math.min(MAX_THREADS, ceiling), chunkBuilderThreads)
				: Math.min(MAX_THREADS, Math.min(ceiling, Math.max(1, cpus / 4)));
	}

	public int effectiveChunkBuilderQueueLimit() {
		return chunkBuilderQueueLimit > 0 ? chunkBuilderQueueLimit : Integer.MAX_VALUE;
	}

	public int effectiveChunkBuilderThreadPriority() {
		return Math.max(Thread.MIN_PRIORITY,
				Math.min(Thread.MAX_PRIORITY, chunkBuilderThreadPriority));
	}

	public int effectiveBackgroundWorkerThreads() {
		// Outside the experimental renderer this pool shares only the normal client workload with
		// the dedicated vanilla chunk-builder replacement.  Its old ceiling is retained there.
		int ceiling = Math.max(2, cpuCount() - effectiveChunkBuilderThreads() - 1);
		if (autoBalanceBackgroundThreads
				&& FabricLoader.getInstance().getEnvironmentType() == net.fabricmc.api.EnvType.CLIENT
				&& experimentalRenderer && skipVanillaChunkBuilds) {
			// The experimental renderer owns a separate terrain pool and still needs Worker-Main for
			// packet decode/light work.  Generic cache/precompute jobs are deliberately limited to the
			// genuine remainder so they cannot delay the render-thread snapshot producer at world join.
			ceiling = Math.max(1, cpuCount() - effectiveChunkBuilderThreads()
					- effectiveBackgroundThreads() - 2);
		}
		return backgroundWorkerThreads > 0
				? Math.min(Math.min(MAX_THREADS, ceiling), backgroundWorkerThreads)
				: ceiling;
	}

	public int effectiveBackgroundQueueLimit() {
		return backgroundQueueLimit > 0 ? backgroundQueueLimit : Integer.MAX_VALUE;
	}

	private static MoneyakShadersConfig load() {
		MoneyakShadersConfig config = new MoneyakShadersConfig();
		Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
		Properties properties = new Properties();

		if (Files.isRegularFile(path)) {
			try (InputStream in = Files.newInputStream(path)) {
				properties.load(in);
			} catch (IOException ignored) {
				// fall through with defaults
			}
		}

		config.backgroundThreads = readInt(properties, "backgroundThreads", config.backgroundThreads);
		config.chunkBuilderThreads = readInt(properties, "chunkBuilderThreads", config.chunkBuilderThreads);
		config.chunkBuilderQueueLimit = readInt(properties, "chunkBuilderQueueLimit", config.chunkBuilderQueueLimit);
		config.chunkBuilderDropOldestWhenFull = readBoolean(properties, "chunkBuilderDropOldestWhenFull", config.chunkBuilderDropOldestWhenFull);
		config.chunkBuilderAsyncMode = readBoolean(properties, "chunkBuilderAsyncMode", config.chunkBuilderAsyncMode);
		config.chunkBuilderThreadPriority = readInt(properties, "chunkBuilderThreadPriority", config.chunkBuilderThreadPriority);
		config.backgroundWorkerThreads = readInt(properties, "backgroundWorkerThreads", config.backgroundWorkerThreads);
		config.backgroundQueueLimit = readInt(properties, "backgroundQueueLimit", config.backgroundQueueLimit);
		config.backgroundDropOldestWhenFull = readBoolean(properties, "backgroundDropOldestWhenFull", config.backgroundDropOldestWhenFull);
		config.chunkRebuildBackpressureThreshold = readInt(properties, "chunkRebuildBackpressureThreshold", config.chunkRebuildBackpressureThreshold);
		config.chunkRebuildDropLowPriorityWhenQueueHigh = readBoolean(properties, "chunkRebuildDropLowPriorityWhenQueueHigh", config.chunkRebuildDropLowPriorityWhenQueueHigh);
		config.chunkPrecomputePriorityDistance = readInt(properties, "chunkPrecomputePriorityDistance", config.chunkPrecomputePriorityDistance);
		config.renderDuplicateSkipWindowMs = readInt(properties, "renderDuplicateSkipWindowMs", config.renderDuplicateSkipWindowMs);
		config.sectionRenderBackpressureThreshold = readInt(properties, "sectionRenderBackpressureThreshold", config.sectionRenderBackpressureThreshold);
		config.sectionRenderDropLowPriorityWhenQueueHigh = readBoolean(properties, "sectionRenderDropLowPriorityWhenQueueHigh", config.sectionRenderDropLowPriorityWhenQueueHigh);
		config.autoBalanceBackgroundThreads = readBoolean(properties, "autoBalanceBackgroundThreads", config.autoBalanceBackgroundThreads);
		config.enableTeleportationGuard = readBoolean(properties, "enableTeleportationGuard", config.enableTeleportationGuard);
		config.disableAggressiveGlassCulling = readBoolean(properties, "disableAggressiveGlassCulling", config.disableAggressiveGlassCulling);
		config.disableGlassLOD = readBoolean(properties, "disableGlassLOD", config.disableGlassLOD);
		config.enableUnusedSceneCulling = readBoolean(properties, "enableUnusedSceneCulling", config.enableUnusedSceneCulling);
		config.fpsHudEnabled = readBoolean(properties, "fpsHudEnabled", config.fpsHudEnabled);
		config.lightingEngineThreads = readInt(properties, "lightingEngineThreads", config.lightingEngineThreads);
		config.lightingQueueLimit = readInt(properties, "lightingQueueLimit", config.lightingQueueLimit);
		config.lightingSubmissionsPerSecond = readInt(properties, "lightingSubmissionsPerSecond", config.lightingSubmissionsPerSecond);
		config.animatedTasksPerSecond = readInt(properties, "animatedTasksPerSecond", config.animatedTasksPerSecond);
		config.animatedTextureUploadPixelsPerTick = readInt(properties, "animatedTextureUploadPixelsPerTick",
				config.animatedTextureUploadPixelsPerTick);
		config.waterTasksPerSecond = readInt(properties, "waterTasksPerSecond", config.waterTasksPerSecond);
		config.enableBlockFaceCulling = readBoolean(properties, "enableBlockFaceCulling", config.enableBlockFaceCulling);
		config.enableTextureAggregation = readBoolean(properties, "enableTextureAggregation", config.enableTextureAggregation);
		config.debugBlockOptimization = readBoolean(properties, "debugBlockOptimization", config.debugBlockOptimization);
		config.lightUpdateIntervalMs = readInt(properties, "lightUpdateIntervalMs", config.lightUpdateIntervalMs);
		config.brightnessCacheSize = readInt(properties, "brightnessCacheSize", config.brightnessCacheSize);
		config.entityLightCacheMs = readInt(properties, "entityLightCacheMs", config.entityLightCacheMs);
		config.entityAnimationLod = readBoolean(properties, "entityAnimationLod", config.entityAnimationLod);
		config.entityAnimationNearDistance = readInt(properties, "entityAnimationNearDistance", config.entityAnimationNearDistance);
		config.entityAnimationMidDistance = readInt(properties, "entityAnimationMidDistance", config.entityAnimationMidDistance);
		config.entityAnimationMidFps = readInt(properties, "entityAnimationMidFps", config.entityAnimationMidFps);
		config.entityAnimationFarFps = readInt(properties, "entityAnimationFarFps", config.entityAnimationFarFps);
		config.particleSpawnDistanceLimit = readInt(properties, "particleSpawnDistanceLimit", config.particleSpawnDistanceLimit);
		config.asyncLightUpdates = readBoolean(properties, "asyncLightUpdates", config.asyncLightUpdates);
		config.lightRenderBudget = readInt(properties, "lightRenderBudget", config.lightRenderBudget);
		config.blockEntityRenderDistance = readInt(properties, "blockEntityRenderDistance", config.blockEntityRenderDistance);
		config.blockEntityFrustumCull = readBoolean(properties, "blockEntityFrustumCull", config.blockEntityFrustumCull);
		config.blockEntityBake = readBoolean(properties, "blockEntityBake", config.blockEntityBake);
		config.occlusionCulling = readBoolean(properties, "occlusionCulling", config.occlusionCulling);
		config.sortOpaqueFrontToBack = readBoolean(properties, "sortOpaqueFrontToBack", config.sortOpaqueFrontToBack);
		config.skipVanillaChunkBuilds = readBoolean(properties, "skipVanillaChunkBuilds", config.skipVanillaChunkBuilds);
		config.occlusionMeshing = readBoolean(properties, "occlusionMeshing", config.occlusionMeshing);
		config.deferUndergroundSections = readBoolean(properties, "deferUndergroundSections", config.deferUndergroundSections);
		config.undergroundDeferDepth = readInt(properties, "undergroundDeferDepth", config.undergroundDeferDepth);
		config.undergroundSleepMode = properties.getProperty("undergroundSleepMode", config.undergroundSleepMode);
		config.undergroundActiveRadiusSections = readInt(properties, "undergroundActiveRadiusSections", config.undergroundActiveRadiusSections);
		config.undergroundEvictionDelayMs = readInt(properties, "undergroundEvictionDelayMs", config.undergroundEvictionDelayMs);
		config.undergroundSleepGpuBudgetMb = readInt(properties, "undergroundSleepGpuBudgetMb", config.undergroundSleepGpuBudgetMb);
		config.depthPrePass = readBoolean(properties, "depthPrePass", config.depthPrePass);
		config.debugAutoScreenshot = readBoolean(properties, "debugAutoScreenshot", config.debugAutoScreenshot);
		config.blockEntityOcclusionCulling = readBoolean(properties, "blockEntityOcclusionCulling", config.blockEntityOcclusionCulling);
		config.blockEntityOcclusionRecheckMs = readInt(properties, "blockEntityOcclusionRecheckMs", config.blockEntityOcclusionRecheckMs);
		config.blockEntityOcclusionRaycastsPerFrame = readInt(properties, "blockEntityOcclusionRaycastsPerFrame", config.blockEntityOcclusionRaycastsPerFrame);
		config.disableEntityShadows = readBoolean(properties, "disableEntityShadows", config.disableEntityShadows);
		config.forceAsyncChunkRebuilds = readBoolean(properties, "forceAsyncChunkRebuilds", config.forceAsyncChunkRebuilds);
		config.debugStats = readBoolean(properties, "debugStats", config.debugStats);
		config.entityOcclusionCulling = readBoolean(properties, "entityOcclusionCulling", config.entityOcclusionCulling);
		config.entityCullingMinDistance = readInt(properties, "entityCullingMinDistance", config.entityCullingMinDistance);
		config.entityOcclusionRecheckMs = readInt(properties, "entityOcclusionRecheckMs", config.entityOcclusionRecheckMs);
		config.entityOcclusionRaycastsPerFrame = readInt(properties, "entityOcclusionRaycastsPerFrame", config.entityOcclusionRaycastsPerFrame);
		config.maxActiveParticles = readInt(properties, "maxActiveParticles", config.maxActiveParticles);
		config.maxChunkUploadsPerFrame = readInt(properties, "maxChunkUploadsPerFrame", config.maxChunkUploadsPerFrame);
		config.maxChunkUploadBytesPerFrame = readInt(properties, "maxChunkUploadBytesPerFrame",
				config.maxChunkUploadBytesPerFrame);
		config.maxReadySectionMeshes = readInt(properties, "maxReadySectionMeshes", config.maxReadySectionMeshes);
		config.sectionSnapshotCellsPerFrame = readInt(properties, "sectionSnapshotCellsPerFrame", config.sectionSnapshotCellsPerFrame);
		config.maxPendingSectionSnapshots = readInt(properties, "maxPendingSectionSnapshots", config.maxPendingSectionSnapshots);
		config.maxPooledSectionSnapshots = readInt(properties, "maxPooledSectionSnapshots", config.maxPooledSectionSnapshots);
		config.fixLargeModelCulling = readBoolean(properties, "fixLargeModelCulling", config.fixLargeModelCulling);
		config.largeModelCullMargin = readInt(properties, "largeModelCullMargin", config.largeModelCullMargin);
		config.itemRenderDistance = readInt(properties, "itemRenderDistance", config.itemRenderDistance);
		config.xpOrbRenderDistance = readInt(properties, "xpOrbRenderDistance", config.xpOrbRenderDistance);
		config.projectileRenderDistance = readInt(properties, "projectileRenderDistance", config.projectileRenderDistance);
		config.mobRenderDistance = readInt(properties, "mobRenderDistance", config.mobRenderDistance);
		config.staticEntityRenderDistance = readInt(properties, "staticEntityRenderDistance", config.staticEntityRenderDistance);
		config.particleRenderDistance = readInt(properties, "particleRenderDistance", config.particleRenderDistance);
		// Plan C is the built-in renderer; the legacy properties value is ignored.
		config.experimentalRenderer = true;
		config.leavesOpaqueDistance = readInt(properties, "leavesOpaqueDistance", config.leavesOpaqueDistance);
		config.sectionCache = readBoolean(properties, "sectionCache", config.sectionCache);
		config.sectionCacheDisk = readBoolean(properties, "sectionCacheDisk", config.sectionCacheDisk);
		config.terrainMipmaps = readBoolean(properties, "terrainMipmaps", config.terrainMipmaps);
		config.terrainAnisotropy = readInt(properties, "terrainAnisotropy", config.terrainAnisotropy);
		config.terrainTextureLodBias = readInt(properties, "terrainTextureLodBias", config.terrainTextureLodBias);
		config.terrainTextureLodNear = readInt(properties, "terrainTextureLodNear", config.terrainTextureLodNear);
		config.terrainTextureLodFar = readInt(properties, "terrainTextureLodFar", config.terrainTextureLodFar);
		config.entityTextureDistanceLod = readBoolean(properties, "entityTextureDistanceLod", config.entityTextureDistanceLod);
		config.entityTextureLodNear = readInt(properties, "entityTextureLodNear", config.entityTextureLodNear);
		config.entityTextureLodFar = readInt(properties, "entityTextureLodFar", config.entityTextureLodFar);
		config.windSway = readBoolean(properties, "windSway", config.windSway);
		config.handSway = readBoolean(properties, "handSway", config.handSway);
		config.lightSmoothing = readInt(properties, "lightSmoothing", config.lightSmoothing);
		config.prewarmItemModels = readBoolean(properties, "prewarmItemModels", config.prewarmItemModels);
		config.toastTutorial = readBoolean(properties, "toastTutorial", config.toastTutorial);
		config.toastAdvancement = readBoolean(properties, "toastAdvancement", config.toastAdvancement);
		config.toastChatInsecure = readBoolean(properties, "toastChatInsecure", config.toastChatInsecure);
		config.toastResourcePack = readBoolean(properties, "toastResourcePack", config.toastResourcePack);
		config.chatHistorySize = readInt(properties, "chatHistorySize", config.chatHistorySize);
		config.sentHistorySize = readInt(properties, "sentHistorySize", config.sentHistorySize);
		config.waterfallParticles = readBoolean(properties, "waterfallParticles", config.waterfallParticles);
		config.waterSplash = readBoolean(properties, "waterSplash", config.waterSplash);
		config.rainRipples = readBoolean(properties, "rainRipples", config.rainRipples);
		config.rainMist = readBoolean(properties, "rainMist", config.rainMist);
		config.etfRandomTextures = readBoolean(properties, "etfRandomTextures", config.etfRandomTextures);
		config.forceOldPackCompat = readBoolean(properties, "forceOldPackCompat", config.forceOldPackCompat);
		config.citTextures = readBoolean(properties, "citTextures", config.citTextures);
		config.debugCit = readBoolean(properties, "debugCit", config.debugCit);
		config.oldPackItemAtlasFix = readBoolean(properties, "oldPackItemAtlasFix", config.oldPackItemAtlasFix);
		config.fantasyClouds = readBoolean(properties, "fantasyClouds", config.fantasyClouds);
		config.etfEmissive = readBoolean(properties, "etfEmissive", config.etfEmissive);
		config.entityShadowDistance = readInt(properties, "entityShadowDistance", config.entityShadowDistance);
		config.dayNightTint = readBoolean(properties, "dayNightTint", config.dayNightTint);
		config.dynamicHeldLight = readBoolean(properties, "dynamicHeldLight", config.dynamicHeldLight);
		config.glowingOres = readBoolean(properties, "glowingOres", config.glowingOres);
		config.dynamicLighting = readBoolean(properties, "dynamicLighting", config.dynamicLighting);
		config.terrainPointLights = readBoolean(properties, "terrainPointLights", config.terrainPointLights);
		config.postProcessing = readBoolean(properties, "postProcessing", config.postProcessing);
		config.postExposure = readInt(properties, "postExposure", config.postExposure);
		config.postBloom = readInt(properties, "postBloom", config.postBloom);
		config.postVignette = readInt(properties, "postVignette", config.postVignette);
		config.postSaturation = readInt(properties, "postSaturation", config.postSaturation);
		config.postGodRays = readInt(properties, "postGodRays", config.postGodRays);
		config.postDebugView = readInt(properties, "postDebugView", config.postDebugView);
		config.waterBumpiness = readInt(properties, "waterBumpiness", config.waterBumpiness);
		config.waterReflection = readInt(properties, "waterReflection", config.waterReflection);
		config.waterRefraction = readInt(properties, "waterRefraction", config.waterRefraction);
		config.waterFoam = readInt(properties, "waterFoam", config.waterFoam);
		config.waterSsrSteps = readInt(properties, "waterSsrSteps", config.waterSsrSteps);
		config.celOutlines = readBoolean(properties, "celOutlines", config.celOutlines);
		config.fxaa = readBoolean(properties, "fxaa", config.fxaa);
		config.weightedTransparency = readBoolean(properties, "weightedTransparency", config.weightedTransparency);
		config.depthSafeTextDisplays = readBoolean(properties, "depthSafeTextDisplays", config.depthSafeTextDisplays);
		config.outlineStrength = readInt(properties, "outlineStrength", config.outlineStrength);
		config.sunShadows = readBoolean(properties, "sunShadows", config.sunShadows);
		config.shadowResolution = readInt(properties, "shadowResolution", config.shadowResolution);
		config.shadowNearResolution = readInt(properties, "shadowNearResolution", config.shadowNearResolution);
		config.shadowFarResolution = readInt(properties, "shadowFarResolution", config.shadowFarResolution);
		config.adaptiveShadowQuality = readBoolean(properties, "adaptiveShadowQuality", config.adaptiveShadowQuality);
		config.shadowGpuBudgetMs = readInt(properties, "shadowGpuBudgetMs", config.shadowGpuBudgetMs);
		config.shadowMinResolution = readInt(properties, "shadowMinResolution", config.shadowMinResolution);
		config.pointShadowResolution = readInt(properties, "pointShadowResolution", config.pointShadowResolution);
		config.shadowBias = readInt(properties, "shadowBias", config.shadowBias);
		config.shadowNearChunks = readInt(properties, "shadowNearChunks", config.shadowNearChunks);
		config.entityShadows = readBoolean(properties, "entityShadows", config.entityShadows);
		config.shadowDistanceChunks = readInt(properties, "shadowDistanceChunks", config.shadowDistanceChunks);
		config.shadowStrength = readInt(properties, "shadowStrength", config.shadowStrength);
		config.shadowSharpDistance = readInt(properties, "shadowSharpDistance", config.shadowSharpDistance);
		config.shadowFarBlur = readInt(properties, "shadowFarBlur", config.shadowFarBlur);
		config.shadowCascadeBlend = readInt(properties, "shadowCascadeBlend", config.shadowCascadeBlend);
		config.pointLightShadows = readBoolean(properties, "pointLightShadows", config.pointLightShadows);
		config.pointShadowPolicyVersion = readInt(properties, "pointShadowPolicyVersion", config.pointShadowPolicyVersion);
		if (config.pointShadowPolicyVersion < 2) {
			config.pointLightShadows = true;
			config.pointShadowPolicyVersion = 2;
		}
		config.pointShadowStrength = readInt(properties, "pointShadowStrength", config.pointShadowStrength);
		config.worldBrightness = readInt(properties, "worldBrightness", config.worldBrightness);
		config.worldContrast = readInt(properties, "worldContrast", config.worldContrast);
		config.worldSaturation = readInt(properties, "worldSaturation", config.worldSaturation);
		config.frameProfilerEnabled = readBoolean(properties, "frameProfilerEnabled", config.frameProfilerEnabled);
		config.chunkLoadTimelineEnabled = readBoolean(properties, "chunkLoadTimelineEnabled", config.chunkLoadTimelineEnabled);
		config.frameBudgetEnabled = readBoolean(properties, "frameBudgetEnabled", config.frameBudgetEnabled);
		config.frameBudgetTargetMs = readInt(properties, "frameBudgetTargetMs", config.frameBudgetTargetMs);
		config.frameBudgetMeshUploadMs = readInt(properties, "frameBudgetMeshUploadMs", config.frameBudgetMeshUploadMs);
		config.frameBudgetSnapshotMs = readInt(properties, "frameBudgetSnapshotMs", config.frameBudgetSnapshotMs);
		config.frameBudgetShadowMs = readInt(properties, "frameBudgetShadowMs", config.frameBudgetShadowMs);
		config.frameBudgetLightApplyMs = readInt(properties, "frameBudgetLightApplyMs", config.frameBudgetLightApplyMs);
		config.frustumRotationPrefetchBlocks = readInt(properties, "frustumRotationPrefetchBlocks", config.frustumRotationPrefetchBlocks);
		config.globalQualityProfile = properties.getProperty("globalQualityProfile", config.globalQualityProfile).trim();
		config.visualPolicyVersion = readInt(properties, "visualPolicyVersion", config.visualPolicyVersion);
		if (config.visualPolicyVersion < 1) {
			boolean untouchedOldDefaults = config.postExposure == 105 && config.postBloom == 16
					&& config.postVignette == 16 && config.postSaturation == 118 && config.postGodRays == 10
					&& config.shadowDistanceChunks == 8 && config.shadowStrength == 55
					&& config.worldBrightness == 106 && config.worldContrast == 108 && config.worldSaturation == 116;
			if (untouchedOldDefaults) {
				config.postExposure = 100; config.postBloom = 18; config.postVignette = 10; config.postSaturation = 104; config.postGodRays = 55;
				config.shadowDistanceChunks = 12; config.shadowStrength = 68;
				config.worldBrightness = 100; config.worldContrast = 106; config.worldSaturation = 106;
			}
			config.visualPolicyVersion = 1;
		}
		config.chunkStreamingPolicyVersion = readInt(properties, "chunkStreamingPolicyVersion", config.chunkStreamingPolicyVersion);
		if (config.chunkStreamingPolicyVersion < 1) {
			// Older builds preserved profile-era 1-3 ms budgets and tiny queues indefinitely. Lift only
			// throughput controls (still time-budgeted) so existing multiplayer installations do not have
			// to manually reset their whole visual profile to receive the streaming fix.
			config.sectionSnapshotCellsPerFrame = Math.max(config.sectionSnapshotCellsPerFrame, 24_576);
			config.maxPendingSectionSnapshots = Math.max(config.maxPendingSectionSnapshots, 20);
			config.maxPooledSectionSnapshots = Math.max(config.maxPooledSectionSnapshots, 20);
			config.maxChunkUploadsPerFrame = Math.max(config.maxChunkUploadsPerFrame, 16);
			config.maxReadySectionMeshes = Math.max(config.maxReadySectionMeshes, 32);
			config.frameBudgetSnapshotMs = Math.max(config.frameBudgetSnapshotMs, 5);
			config.chunkStreamingPolicyVersion = 1;
		}
		if (config.chunkStreamingPolicyVersion < 2) {
			// The custom renderer snapshots immutable worker input on the render thread.  Five ms fed
			// roughly one section per frame, leaving most meshing workers idle and making normal chunk
			// streaming visibly slower than vanilla.  These are bounded time/queue limits, not a visual
			// quality reduction; the adaptive governor still contracts them under sustained p95/p99 load.
			config.sectionSnapshotCellsPerFrame = Math.max(config.sectionSnapshotCellsPerFrame, 49_152);
			config.maxPendingSectionSnapshots = Math.max(config.maxPendingSectionSnapshots, 48);
			config.maxPooledSectionSnapshots = Math.max(config.maxPooledSectionSnapshots, 32);
			config.maxReadySectionMeshes = Math.max(config.maxReadySectionMeshes, 64);
			config.frameBudgetSnapshotMs = Math.max(config.frameBudgetSnapshotMs, 10);
			config.chunkStreamingPolicyVersion = 2;
		}
		if (config.chunkStreamingPolicyVersion < 3) {
			// Neighbour/light arrivals now guarantee a coherent follow-up mesh even if the first
			// snapshot was already on a worker. Deeper bounded queues can therefore improve real
			// multiplayer throughput without leaving a provisional light/tint mesh on screen.
			// FrameWorkBudget still contracts these allowances under sustained p95/p99 pressure.
			config.sectionSnapshotCellsPerFrame = Math.max(config.sectionSnapshotCellsPerFrame, 98_304);
			config.maxPendingSectionSnapshots = Math.max(config.maxPendingSectionSnapshots, 64);
			config.maxPooledSectionSnapshots = Math.max(config.maxPooledSectionSnapshots, 48);
			config.maxChunkUploadsPerFrame = Math.max(config.maxChunkUploadsPerFrame, 96);
			config.maxReadySectionMeshes = Math.max(config.maxReadySectionMeshes, 96);
			config.frameBudgetSnapshotMs = Math.max(config.frameBudgetSnapshotMs, 12);
			config.frameBudgetMeshUploadMs = Math.max(config.frameBudgetMeshUploadMs, 3);
			config.chunkStreamingPolicyVersion = 3;
		}
		if (config.chunkStreamingPolicyVersion < 4) {
			// A full immutable input is about 13k cells.  The previous queue limits kept 12 mesh
			// workers idle behind a shallow render-thread producer, which made normal multiplayer
			// streaming much slower than vanilla even on hardware with ample headroom.  These are
			// still governed by FrameWorkBudget, and all queues remain finite.
			config.sectionSnapshotCellsPerFrame = Math.max(config.sectionSnapshotCellsPerFrame, 196_608);
			config.maxPendingSectionSnapshots = Math.max(config.maxPendingSectionSnapshots, 128);
			config.maxPooledSectionSnapshots = Math.max(config.maxPooledSectionSnapshots, 96);
			config.maxChunkUploadsPerFrame = Math.max(config.maxChunkUploadsPerFrame, 128);
			config.maxReadySectionMeshes = Math.max(config.maxReadySectionMeshes, 128);
			config.frameBudgetSnapshotMs = Math.max(config.frameBudgetSnapshotMs, 14);
			config.frameBudgetMeshUploadMs = Math.max(config.frameBudgetMeshUploadMs, 4);
			// Existing installs inherited this expensive instrumentation from an early diagnostic
			// default.  Users who actively profile can turn it back on in the properties file.
			config.chunkLoadTimelineEnabled = false;
			config.chunkStreamingPolicyVersion = 4;
		}
		if (config.chunkStreamingPolicyVersion < 5) {
			// Surface admission is now event-driven and ordered per received column, so a deeper bounded
			// producer no longer multiplies expensive full-ring scans. Keep enough immutable inputs and
			// ready results in flight to feed modern mesh pools; FrameWorkBudget remains the hard per-frame
			// latency guard and contracts the 8 ms snapshot allowance when p95/p99 rises.
			config.sectionSnapshotCellsPerFrame = Math.max(config.sectionSnapshotCellsPerFrame, 196_608);
			config.maxPendingSectionSnapshots = Math.max(config.maxPendingSectionSnapshots, 128);
			config.maxPooledSectionSnapshots = Math.max(config.maxPooledSectionSnapshots, 96);
			config.maxChunkUploadsPerFrame = Math.max(config.maxChunkUploadsPerFrame, 128);
			config.maxReadySectionMeshes = Math.max(config.maxReadySectionMeshes, 128);
			config.frameBudgetSnapshotMs = Math.max(config.frameBudgetSnapshotMs, 8);
			config.frameBudgetMeshUploadMs = Math.max(config.frameBudgetMeshUploadMs, 4);
			config.chunkStreamingPolicyVersion = 5;
		}
		if (config.chunkStreamingPolicyVersion < 6) {
			// Closed underground geometry is now strictly on-demand. The heightmap envelope still retains
			// ravines, ocean floors and cliff walls which can be seen from above, while the small immediate
			// player safety cube prevents a just-opened floor from exposing an unmeshed void.
			config.deferUndergroundSections = true;
			config.undergroundDeferDepth = 0;
			config.undergroundSleepGpuBudgetMb = 0;
			config.chunkStreamingPolicyVersion = 6;
		}

		// Write the file back so users can discover the available options.
		properties.setProperty("backgroundThreads", Integer.toString(config.backgroundThreads));
		properties.setProperty("chunkBuilderThreads", Integer.toString(config.chunkBuilderThreads));
		properties.setProperty("chunkBuilderQueueLimit", Integer.toString(config.chunkBuilderQueueLimit));
		properties.setProperty("chunkBuilderDropOldestWhenFull", Boolean.toString(config.chunkBuilderDropOldestWhenFull));
		properties.setProperty("chunkBuilderAsyncMode", Boolean.toString(config.chunkBuilderAsyncMode));
		properties.setProperty("chunkBuilderThreadPriority", Integer.toString(config.chunkBuilderThreadPriority));
		properties.setProperty("backgroundWorkerThreads", Integer.toString(config.backgroundWorkerThreads));
		properties.setProperty("backgroundQueueLimit", Integer.toString(config.backgroundQueueLimit));
		properties.setProperty("backgroundDropOldestWhenFull", Boolean.toString(config.backgroundDropOldestWhenFull));
		properties.setProperty("chunkRebuildBackpressureThreshold", Integer.toString(config.chunkRebuildBackpressureThreshold));
		properties.setProperty("chunkRebuildDropLowPriorityWhenQueueHigh", Boolean.toString(config.chunkRebuildDropLowPriorityWhenQueueHigh));
		properties.setProperty("chunkPrecomputePriorityDistance", Integer.toString(config.chunkPrecomputePriorityDistance));
		properties.setProperty("renderDuplicateSkipWindowMs", Integer.toString(config.renderDuplicateSkipWindowMs));
		properties.setProperty("sectionRenderBackpressureThreshold", Integer.toString(config.sectionRenderBackpressureThreshold));
		properties.setProperty("sectionRenderDropLowPriorityWhenQueueHigh", Boolean.toString(config.sectionRenderDropLowPriorityWhenQueueHigh));
		properties.setProperty("autoBalanceBackgroundThreads", Boolean.toString(config.autoBalanceBackgroundThreads));
		properties.setProperty("enableTeleportationGuard", Boolean.toString(config.enableTeleportationGuard));
		properties.setProperty("disableAggressiveGlassCulling", Boolean.toString(config.disableAggressiveGlassCulling));
		properties.setProperty("disableGlassLOD", Boolean.toString(config.disableGlassLOD));
		properties.setProperty("enableUnusedSceneCulling", Boolean.toString(config.enableUnusedSceneCulling));
		properties.setProperty("fpsHudEnabled", Boolean.toString(config.fpsHudEnabled));
		properties.setProperty("lightingEngineThreads", Integer.toString(config.lightingEngineThreads));
		properties.setProperty("lightingQueueLimit", Integer.toString(config.lightingQueueLimit));
		properties.setProperty("lightingSubmissionsPerSecond", Integer.toString(config.lightingSubmissionsPerSecond));
		properties.setProperty("animatedTasksPerSecond", Integer.toString(config.animatedTasksPerSecond));
		properties.setProperty("animatedTextureUploadPixelsPerTick",
				Integer.toString(config.animatedTextureUploadPixelsPerTick));
		properties.setProperty("waterTasksPerSecond", Integer.toString(config.waterTasksPerSecond));
		properties.setProperty("enableBlockFaceCulling", Boolean.toString(config.enableBlockFaceCulling));
		properties.setProperty("enableTextureAggregation", Boolean.toString(config.enableTextureAggregation));
		properties.setProperty("debugBlockOptimization", Boolean.toString(config.debugBlockOptimization));
		properties.setProperty("lightUpdateIntervalMs", Integer.toString(config.lightUpdateIntervalMs));
		properties.setProperty("brightnessCacheSize", Integer.toString(config.brightnessCacheSize));
		properties.setProperty("entityLightCacheMs", Integer.toString(config.entityLightCacheMs));
		properties.setProperty("entityAnimationLod", Boolean.toString(config.entityAnimationLod));
		properties.setProperty("entityAnimationNearDistance", Integer.toString(config.entityAnimationNearDistance));
		properties.setProperty("entityAnimationMidDistance", Integer.toString(config.entityAnimationMidDistance));
		properties.setProperty("entityAnimationMidFps", Integer.toString(config.entityAnimationMidFps));
		properties.setProperty("entityAnimationFarFps", Integer.toString(config.entityAnimationFarFps));
		properties.setProperty("particleSpawnDistanceLimit", Integer.toString(config.particleSpawnDistanceLimit));
		properties.setProperty("asyncLightUpdates", Boolean.toString(config.asyncLightUpdates));
		properties.setProperty("lightRenderBudget", Integer.toString(config.lightRenderBudget));
		properties.setProperty("blockEntityRenderDistance", Integer.toString(config.blockEntityRenderDistance));
		properties.setProperty("blockEntityFrustumCull", Boolean.toString(config.blockEntityFrustumCull));
		properties.setProperty("blockEntityBake", Boolean.toString(config.blockEntityBake));
		properties.setProperty("occlusionCulling", Boolean.toString(config.occlusionCulling));
		properties.setProperty("sortOpaqueFrontToBack", Boolean.toString(config.sortOpaqueFrontToBack));
		properties.setProperty("skipVanillaChunkBuilds", Boolean.toString(config.skipVanillaChunkBuilds));
		properties.setProperty("occlusionMeshing", Boolean.toString(config.occlusionMeshing));
		properties.setProperty("deferUndergroundSections", Boolean.toString(config.deferUndergroundSections));
		properties.setProperty("undergroundDeferDepth", Integer.toString(config.undergroundDeferDepth));
		properties.setProperty("undergroundSleepMode", config.undergroundSleepMode);
		properties.setProperty("undergroundActiveRadiusSections", Integer.toString(config.undergroundActiveRadiusSections));
		properties.setProperty("undergroundEvictionDelayMs", Integer.toString(config.undergroundEvictionDelayMs));
		properties.setProperty("undergroundSleepGpuBudgetMb", Integer.toString(config.undergroundSleepGpuBudgetMb));
		properties.setProperty("depthPrePass", Boolean.toString(config.depthPrePass));
		properties.setProperty("debugAutoScreenshot", Boolean.toString(config.debugAutoScreenshot));
		properties.setProperty("blockEntityOcclusionCulling", Boolean.toString(config.blockEntityOcclusionCulling));
		properties.setProperty("blockEntityOcclusionRecheckMs", Integer.toString(config.blockEntityOcclusionRecheckMs));
		properties.setProperty("blockEntityOcclusionRaycastsPerFrame", Integer.toString(config.blockEntityOcclusionRaycastsPerFrame));
		properties.setProperty("disableEntityShadows", Boolean.toString(config.disableEntityShadows));
		properties.setProperty("forceAsyncChunkRebuilds", Boolean.toString(config.forceAsyncChunkRebuilds));
		properties.setProperty("debugStats", Boolean.toString(config.debugStats));
		properties.setProperty("entityOcclusionCulling", Boolean.toString(config.entityOcclusionCulling));
		properties.setProperty("entityCullingMinDistance", Integer.toString(config.entityCullingMinDistance));
		properties.setProperty("entityOcclusionRecheckMs", Integer.toString(config.entityOcclusionRecheckMs));
		properties.setProperty("entityOcclusionRaycastsPerFrame", Integer.toString(config.entityOcclusionRaycastsPerFrame));
		properties.setProperty("maxActiveParticles", Integer.toString(config.maxActiveParticles));
		properties.setProperty("maxChunkUploadsPerFrame", Integer.toString(config.maxChunkUploadsPerFrame));
		properties.setProperty("maxChunkUploadBytesPerFrame", Integer.toString(config.maxChunkUploadBytesPerFrame));
		properties.setProperty("maxReadySectionMeshes", Integer.toString(config.maxReadySectionMeshes));
		properties.setProperty("sectionSnapshotCellsPerFrame", Integer.toString(config.sectionSnapshotCellsPerFrame));
		properties.setProperty("maxPendingSectionSnapshots", Integer.toString(config.maxPendingSectionSnapshots));
		properties.setProperty("maxPooledSectionSnapshots", Integer.toString(config.maxPooledSectionSnapshots));
		properties.setProperty("visualPolicyVersion", Integer.toString(config.visualPolicyVersion));
		properties.setProperty("chunkStreamingPolicyVersion", Integer.toString(config.chunkStreamingPolicyVersion));
		properties.setProperty("fixLargeModelCulling", Boolean.toString(config.fixLargeModelCulling));
		properties.setProperty("largeModelCullMargin", Integer.toString(config.largeModelCullMargin));
		properties.setProperty("itemRenderDistance", Integer.toString(config.itemRenderDistance));
		properties.setProperty("xpOrbRenderDistance", Integer.toString(config.xpOrbRenderDistance));
		properties.setProperty("projectileRenderDistance", Integer.toString(config.projectileRenderDistance));
		properties.setProperty("mobRenderDistance", Integer.toString(config.mobRenderDistance));
		properties.setProperty("staticEntityRenderDistance", Integer.toString(config.staticEntityRenderDistance));
		properties.setProperty("particleRenderDistance", Integer.toString(config.particleRenderDistance));
		properties.setProperty("experimentalRenderer", Boolean.toString(config.experimentalRenderer));
		properties.setProperty("leavesOpaqueDistance", Integer.toString(config.leavesOpaqueDistance));
		properties.setProperty("sectionCache", Boolean.toString(config.sectionCache));
		properties.setProperty("sectionCacheDisk", Boolean.toString(config.sectionCacheDisk));
		properties.setProperty("terrainMipmaps", Boolean.toString(config.terrainMipmaps));
		properties.setProperty("terrainAnisotropy", Integer.toString(config.terrainAnisotropy));
		properties.setProperty("terrainTextureLodBias", Integer.toString(config.terrainTextureLodBias));
		properties.setProperty("terrainTextureLodNear", Integer.toString(config.terrainTextureLodNear));
		properties.setProperty("terrainTextureLodFar", Integer.toString(config.terrainTextureLodFar));
		properties.setProperty("entityTextureDistanceLod", Boolean.toString(config.entityTextureDistanceLod));
		properties.setProperty("entityTextureLodNear", Integer.toString(config.entityTextureLodNear));
		properties.setProperty("entityTextureLodFar", Integer.toString(config.entityTextureLodFar));
		properties.setProperty("windSway", Boolean.toString(config.windSway));
		properties.setProperty("handSway", Boolean.toString(config.handSway));
		properties.setProperty("lightSmoothing", Integer.toString(config.lightSmoothing));
		properties.setProperty("prewarmItemModels", Boolean.toString(config.prewarmItemModels));
		properties.setProperty("toastTutorial", Boolean.toString(config.toastTutorial));
		properties.setProperty("toastAdvancement", Boolean.toString(config.toastAdvancement));
		properties.setProperty("toastChatInsecure", Boolean.toString(config.toastChatInsecure));
		properties.setProperty("toastResourcePack", Boolean.toString(config.toastResourcePack));
		properties.setProperty("chatHistorySize", Integer.toString(config.chatHistorySize));
		properties.setProperty("sentHistorySize", Integer.toString(config.sentHistorySize));
		properties.setProperty("waterfallParticles", Boolean.toString(config.waterfallParticles));
		properties.setProperty("waterSplash", Boolean.toString(config.waterSplash));
		properties.setProperty("rainRipples", Boolean.toString(config.rainRipples));
		properties.setProperty("rainMist", Boolean.toString(config.rainMist));
		properties.setProperty("etfRandomTextures", Boolean.toString(config.etfRandomTextures));
		properties.setProperty("forceOldPackCompat", Boolean.toString(config.forceOldPackCompat));
		properties.setProperty("citTextures", Boolean.toString(config.citTextures));
		properties.setProperty("debugCit", Boolean.toString(config.debugCit));
		properties.setProperty("oldPackItemAtlasFix", Boolean.toString(config.oldPackItemAtlasFix));
		properties.remove("aaNearDistance");
		properties.setProperty("fantasyClouds", Boolean.toString(config.fantasyClouds));
		properties.setProperty("etfEmissive", Boolean.toString(config.etfEmissive));
		properties.setProperty("entityShadowDistance", Integer.toString(config.entityShadowDistance));
		// The old geometric water-wave option was removed: moving the actual surface by millimetres
		// caused temporal aliasing from high viewpoints. The animated atlas still provides water motion.
		properties.remove("waterWaves");
		properties.setProperty("dayNightTint", Boolean.toString(config.dayNightTint));
		properties.setProperty("dynamicHeldLight", Boolean.toString(config.dynamicHeldLight));
		properties.setProperty("glowingOres", Boolean.toString(config.glowingOres));
		properties.setProperty("dynamicLighting", Boolean.toString(config.dynamicLighting));
		properties.setProperty("terrainPointLights", Boolean.toString(config.terrainPointLights));
		properties.setProperty("postProcessing", Boolean.toString(config.postProcessing));
		properties.setProperty("postExposure", Integer.toString(config.postExposure));
		properties.setProperty("postBloom", Integer.toString(config.postBloom));
		properties.setProperty("postVignette", Integer.toString(config.postVignette));
		properties.remove("postChromatic");
		properties.setProperty("postSaturation", Integer.toString(config.postSaturation));
		properties.setProperty("postGodRays", Integer.toString(config.postGodRays));
		properties.setProperty("postDebugView", Integer.toString(config.postDebugView));
		properties.setProperty("waterBumpiness", Integer.toString(config.waterBumpiness));
		properties.setProperty("waterReflection", Integer.toString(config.waterReflection));
		properties.setProperty("waterRefraction", Integer.toString(config.waterRefraction));
		properties.setProperty("waterFoam", Integer.toString(config.waterFoam));
		properties.setProperty("waterSsrSteps", Integer.toString(config.waterSsrSteps));
		properties.setProperty("celOutlines", Boolean.toString(config.celOutlines));
		properties.setProperty("fxaa", Boolean.toString(config.fxaa));
		properties.setProperty("weightedTransparency", Boolean.toString(config.weightedTransparency));
		properties.setProperty("depthSafeTextDisplays", Boolean.toString(config.depthSafeTextDisplays));
		properties.setProperty("outlineStrength", Integer.toString(config.outlineStrength));
		properties.setProperty("sunShadows", Boolean.toString(config.sunShadows));
		properties.setProperty("shadowResolution", Integer.toString(config.shadowResolution));
		properties.setProperty("shadowNearResolution", Integer.toString(config.shadowNearResolution));
		properties.setProperty("shadowFarResolution", Integer.toString(config.shadowFarResolution));
		properties.setProperty("adaptiveShadowQuality", Boolean.toString(config.adaptiveShadowQuality));
		properties.setProperty("shadowGpuBudgetMs", Integer.toString(config.shadowGpuBudgetMs));
		properties.setProperty("shadowMinResolution", Integer.toString(config.shadowMinResolution));
		properties.setProperty("pointShadowResolution", Integer.toString(config.pointShadowResolution));
		properties.setProperty("shadowBias", Integer.toString(config.shadowBias));
		properties.setProperty("shadowNearChunks", Integer.toString(config.shadowNearChunks));
		properties.setProperty("entityShadows", Boolean.toString(config.entityShadows));
		properties.setProperty("shadowDistanceChunks", Integer.toString(config.shadowDistanceChunks));
		properties.setProperty("shadowStrength", Integer.toString(config.shadowStrength));
		properties.setProperty("shadowSharpDistance", Integer.toString(config.shadowSharpDistance));
		properties.setProperty("shadowFarBlur", Integer.toString(config.shadowFarBlur));
		properties.setProperty("shadowCascadeBlend", Integer.toString(config.shadowCascadeBlend));
		properties.setProperty("pointLightShadows", Boolean.toString(config.pointLightShadows));
		properties.setProperty("pointShadowPolicyVersion", Integer.toString(config.pointShadowPolicyVersion));
		properties.setProperty("pointShadowStrength", Integer.toString(config.pointShadowStrength));
		properties.setProperty("worldBrightness", Integer.toString(config.worldBrightness));
		properties.setProperty("worldContrast", Integer.toString(config.worldContrast));
		properties.setProperty("worldSaturation", Integer.toString(config.worldSaturation));
		properties.setProperty("frameProfilerEnabled", Boolean.toString(config.frameProfilerEnabled));
		properties.setProperty("chunkLoadTimelineEnabled", Boolean.toString(config.chunkLoadTimelineEnabled));
		properties.setProperty("frameBudgetEnabled", Boolean.toString(config.frameBudgetEnabled));
		properties.setProperty("frameBudgetTargetMs", Integer.toString(config.frameBudgetTargetMs));
		properties.setProperty("frameBudgetMeshUploadMs", Integer.toString(config.frameBudgetMeshUploadMs));
		properties.setProperty("frameBudgetSnapshotMs", Integer.toString(config.frameBudgetSnapshotMs));
		properties.setProperty("frameBudgetShadowMs", Integer.toString(config.frameBudgetShadowMs));
		properties.setProperty("frameBudgetLightApplyMs", Integer.toString(config.frameBudgetLightApplyMs));
		properties.setProperty("frustumRotationPrefetchBlocks", Integer.toString(config.frustumRotationPrefetchBlocks));

		try {
			Files.createDirectories(path.getParent());
			try (OutputStream out = Files.newOutputStream(path)) {
				properties.store(out, "Optimized Loading - 0 means auto (see README), max 255");
			}
		} catch (IOException ignored) {
			// not fatal, defaults still apply
		}

		return config;
	}

	/**
	 * Persist the current live config to {@code moneyakshaders.properties}. Called when the
	 * in-game settings screen closes (the screen mutates the singleton's fields live, this
	 * writes them through). Reflects over the public instance fields so it stays in sync with
	 * {@link #load} without a second hand-maintained property list.
	 */
	public static void save() {
		MoneyakShadersConfig config = instance;
		if (config == null) {
			return;
		}
		Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
		Properties properties = new Properties();
		for (Field f : MoneyakShadersConfig.class.getDeclaredFields()) {
			int mods = f.getModifiers();
			if (!Modifier.isPublic(mods) || Modifier.isStatic(mods)) {
				continue;
			}
			try {
				properties.setProperty(f.getName(), String.valueOf(f.get(config)));
			} catch (IllegalAccessException ignored) {
				// skip
			}
		}
		try {
			Files.createDirectories(path.getParent());
			try (OutputStream out = Files.newOutputStream(path)) {
				properties.store(out, "Optimized Loading - edited in-game");
			}
		} catch (IOException ignored) {
			// not fatal
		}
	}

	private static boolean readBoolean(Properties properties, String key, boolean fallback) {
		String value = properties.getProperty(key);
		return value == null ? fallback : Boolean.parseBoolean(value.trim());
	}

	private static int readInt(Properties properties, String key, int fallback) {
		String value = properties.getProperty(key);
		if (value == null) {
			return fallback;
		}
		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}
}
