package com.moneyakshaders.client;

import java.util.concurrent.atomic.AtomicLong;

import com.moneyakshaders.MoneyakShaders;
import com.moneyakshaders.MoneyakShadersConfig;

/**
 * Central per-second activity counters for every active subsystem, dumped as
 * one log line per second (config: debugStats). Lets us see exactly what is
 * doing work at any moment instead of guessing.
 *
 * <p>Counters are plain atomic adds on the existing hot paths (nanoseconds);
 * only the once-per-second dump is gated. {@link #maybeDump()} is called from
 * whichever thread ticks first each second (light thread and/or overlay).
 */
public final class DebugStats {
	public static final AtomicLong lightEnqueued = new AtomicLong();
	public static final AtomicLong lightUpdateCalls = new AtomicLong();
	public static final AtomicLong lightUpdatesDone = new AtomicLong();
	public static final AtomicLong lightHasUpdatesTrue = new AtomicLong();
	public static final AtomicLong lightTrampolines = new AtomicLong();
	public static final AtomicLong scheduleRenderCalls = new AtomicLong();
	public static final AtomicLong scheduleRenderSkipped = new AtomicLong();
	public static final AtomicLong meshSubmitted = new AtomicLong();
	public static final AtomicLong syncRedirects = new AtomicLong();
	public static final AtomicLong beChecks = new AtomicLong();
	public static final AtomicLong beRaycasts = new AtomicLong();
	public static final AtomicLong beCulled = new AtomicLong();
	public static final AtomicLong entLightHit = new AtomicLong();
	public static final AtomicLong entLightMiss = new AtomicLong();
	public static final AtomicLong particlesDropped = new AtomicLong();
	public static final AtomicLong entChecks = new AtomicLong();
	public static final AtomicLong entRaycasts = new AtomicLong();
	public static final AtomicLong entCulled = new AtomicLong();
	public static final AtomicLong particlesCapped = new AtomicLong();
	public static final AtomicLong entityBakeHit = new AtomicLong();
	public static final AtomicLong entityBakeCapture = new AtomicLong();
	public static final AtomicLong entityBakeFallback = new AtomicLong();
	/** Actual old/new block-state notifications observed by the custom renderer. */
	public static final AtomicLong blockUpdates = new AtomicLong();
	/** Completed worker meshes rejected because a newer server block update won the revision race. */
	public static final AtomicLong staleMeshes = new AtomicLong();
	/** RAM-ready meshes retained for a later frame while the GPU arena is being reclaimed. */
	public static final AtomicLong arenaUploadRetries = new AtomicLong();
	/** Unique block entities handed to getRenderState after chunk/global de-duplication. */
	public static final AtomicLong beFed = new AtomicLong();
	/** Block entities that survived culling and produced a render state. */
	public static final AtomicLong beStates = new AtomicLong();
	public static final AtomicLong staticArmorReplays = new AtomicLong();
	public static final AtomicLong staticItemFrameReplays = new AtomicLong();
	public static final AtomicLong staticDisplayReplays = new AtomicLong();
	/** Cached entity vertices submitted for drawing (not captures). */
	public static final AtomicLong staticReplayVertices = new AtomicLong();
	/** Cached entity vertices also walked to feed model-shaped shadows. */
	public static final AtomicLong staticShadowVertices = new AtomicLong();
	/** Cached vertices handled by the translation-only replay fast path. */
	public static final AtomicLong staticFastReplayVertices = new AtomicLong();
	/** Cached vertices that still required a full position/normal matrix transform. */
	public static final AtomicLong staticMatrixReplayVertices = new AtomicLong();
	public static final AtomicLong remeshBiomeTier = new AtomicLong();
	public static final AtomicLong remeshCacheValidation = new AtomicLong();
	public static final AtomicLong remeshBlockEditRelight = new AtomicLong();
	public static final AtomicLong remeshProvisionalRelight = new AtomicLong();
	public static final AtomicLong remeshLightFanout = new AtomicLong();
	public static final AtomicLong remeshUndergroundWake = new AtomicLong();
	public static final AtomicLong remeshAtlas = new AtomicLong();
	/** Large blended buffers repaired from their previous exact order. */
	public static final AtomicLong translucentSortReused = new AtomicLong();
	/** Temporal repairs abandoned after excessive movement and completed by radix. */
	public static final AtomicLong translucentSortFallback = new AtomicLong();
	/** Full radix sorts, including cold buffers and temporal fallbacks. */
	public static final AtomicLong translucentSortRadix = new AtomicLong();
	/** Index moves performed by successful bounded temporal repairs. */
	public static final AtomicLong translucentSortMoves = new AtomicLong();
	/** Cached vertices appended through one contiguous BufferBuilder reservation. */
	public static final AtomicLong staticBulkReplayVertices = new AtomicLong();
	/** Small blended buffers sorted without clearing radix buckets. */
	public static final AtomicLong translucentSortSmall = new AtomicLong();
	/** Animated vanilla/RP cuboids submitted through the live-matrix bulk path. */
	public static final AtomicLong animatedBulkCuboids = new AtomicLong();
	public static final AtomicLong animatedBulkVertices = new AtomicLong();
	/** Fresh model pose calculations and cached ModelPart-transform restores. */
	public static final AtomicLong animationPoseUpdates = new AtomicLong();
	public static final AtomicLong animationPoseReplays = new AtomicLong();
	public static final AtomicLong animationPoseWarmups = new AtomicLong();
	public static final AtomicLong animationPoseUpdateNanos = new AtomicLong();
	public static final AtomicLong animationPoseReplayNanos = new AtomicLong();
	/** Coloured-light scopes that reached a direct RenderPass command with no generic vertex tint hook. */
	public static final AtomicLong entityTintLayeredBypass = new AtomicLong();

	private static final AtomicLong[] COUNTERS = {
			lightEnqueued, lightUpdateCalls, lightUpdatesDone, lightHasUpdatesTrue, lightTrampolines,
			scheduleRenderCalls, scheduleRenderSkipped, meshSubmitted, syncRedirects,
			beChecks, beRaycasts, beCulled, entLightHit, entLightMiss, particlesDropped,
			entChecks, entRaycasts, entCulled, particlesCapped,
			entityBakeHit, entityBakeCapture, entityBakeFallback,
			blockUpdates, staleMeshes, arenaUploadRetries,
			beFed, beStates, staticArmorReplays, staticItemFrameReplays, staticDisplayReplays,
			staticReplayVertices, staticShadowVertices, staticFastReplayVertices, staticMatrixReplayVertices,
			remeshBiomeTier, remeshCacheValidation, remeshBlockEditRelight, remeshProvisionalRelight,
			remeshLightFanout, remeshUndergroundWake, remeshAtlas,
			translucentSortReused, translucentSortFallback, translucentSortRadix, translucentSortMoves,
			staticBulkReplayVertices, translucentSortSmall,
			animatedBulkCuboids, animatedBulkVertices,
			animationPoseUpdates, animationPoseReplays, animationPoseWarmups,
			animationPoseUpdateNanos, animationPoseReplayNanos, entityTintLayeredBypass
	};
	private static final long[] LAST = new long[COUNTERS.length];

	private static final Object LOCK = new Object();
	private static long lastDumpMs = System.currentTimeMillis();

	private DebugStats() {
	}

	public static void maybeDump() {
		if (!MoneyakShadersConfig.get().debugStats) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now - lastDumpMs < 15_000L) {
			return;
		}
		synchronized (LOCK) {
			long dt = now - lastDumpMs;
			if (dt < 15_000L) {
				return;
			}
			long[] rate = new long[COUNTERS.length];
			for (int i = 0; i < COUNTERS.length; i++) {
				long cur = COUNTERS[i].get();
				rate[i] = (cur - LAST[i]) * 1000L / dt;
				LAST[i] = cur;
			}
			lastDumpMs = now;

			MoneyakShaders.LOGGER.info(
					"[OL-DEBUG/s] light{{enq={} calls={} done={} hasUpd={} q={} tramp={}}} render{{sched={} skip={} meshSub={} meshQ={} syncRedir={}}} blocks{{recv={} stale={} arenaRetry={}}} be{{fed={} chk={} state={} ray={} cull={}}} ent{{chk={} ray={} cull={} bakeHit={} bakeNew={} bakeFallback={} tintBypass={}}} static{{armor={} frame={} display={} verts={} shadowV={} fastV={} matrixV={} bulkV={}}} anim{{cuboids={} verts={} poseFull={} poseReplay={} warm={} fullAvgUs={} replayAvgUs={} cache={}}} remesh{{biome={} cache={} editLight={} provisional={} light={} wake={} atlas={}}} entL{{hit={} miss={}}} part{{drop={} cap={}}} sort{{small={} reuse={} fallback={} radix={} moves={}}}",
					rate[0], rate[1], rate[2], rate[3], ClientLightDispatcher.getQueueSize(), rate[4],
					rate[5], rate[6], rate[7], ChunkMeshExecutor.getQueueSize(), rate[8],
					rate[22], rate[23], rate[24],
					rate[25], rate[9], rate[26], rate[10], rate[11],
					rate[15], rate[16], rate[17], rate[19], rate[20], rate[21], rate[54],
					rate[27], rate[28], rate[29], rate[30], rate[31], rate[32], rate[33], rate[45],
					rate[47], rate[48], rate[49], rate[50], rate[51],
					avgMicros(rate[52], rate[49]), avgMicros(rate[53], rate[50]), EntityLODOptimizer.poseCacheSize(),
					rate[34], rate[35], rate[36], rate[37], rate[38], rate[39], rate[40],
					rate[12], rate[13], rate[14], rate[18],
					rate[46], rate[41], rate[42], rate[43], rate[44]);
		}
	}

	private static double avgMicros(long nanosPerSecond, long operationsPerSecond) {
		return operationsPerSecond <= 0L ? 0.0 : nanosPerSecond / (operationsPerSecond * 1000.0);
	}
}
