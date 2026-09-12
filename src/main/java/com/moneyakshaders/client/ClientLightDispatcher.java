package com.moneyakshaders.client;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import com.moneyakshaders.MoneyakShaders;
import com.moneyakshaders.MoneyakShadersConfig;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.chunk.light.LightingProvider;

/**
 * Takes over the client light engine's threading, the same way the dedicated
 * server runs lighting: every mutation (block change, section data from chunk
 * packets, column enable/disable, ...) is recorded into a FIFO queue instead
 * of touching the engine's internal (non-thread-safe) queues directly, and a
 * single dedicated "MoneyakShaders-Light" thread replays them and runs the
 * propagation passes. The render thread never blocks on light again - its
 * only cost is a queue add.
 *
 * <p>Light reads (mesh building, entity light, F3) stay lock-free and racy
 * exactly like vanilla server lighting: readers may see light that is up to
 * a few milliseconds stale, never corrupted structures, because all writes
 * happen on the one light thread.
 *
 * <p>The vanilla propagation algorithm itself is untouched - this replaces
 * the "when and where does the CPU pay for it" part, not the math.
 */
public final class ClientLightDispatcher {
	private static final ConcurrentLinkedQueue<Runnable> QUEUE = new ConcurrentLinkedQueue<>();

	/**
	 * Allocation-free fast lane for {@code checkBlock}, which is the overwhelming majority of deflected
	 * light calls — measured up to ~13 400/s while a world streams in.
	 *
	 * <p>Routing it through {@link #QUEUE} cost THREE allocations per call: an immutable copy of the
	 * {@link BlockPos}, the capturing lambda, and the {@code ConcurrentLinkedQueue} node. Here the
	 * position travels as a packed {@code long} and is replayed through one reusable mutable position,
	 * so the whole path allocates nothing at all.
	 *
	 * <p>The other five deflected calls (section status, column enabled, propagate, section data,
	 * retain data) are rare and structural, so they stay on the general {@code Runnable} queue, which
	 * is drained FIRST. That does mean a {@code checkBlock} can be applied slightly out of order
	 * relative to a structural call. It is harmless: a check against a column that was just enabled or
	 * disabled is either a no-op or redone by the structural call itself, and this class already
	 * documents that light reads may be a few milliseconds stale. What is never reordered is one
	 * checkBlock against another — the FIFO preserves that.
	 */
	private static final it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue CHECK_BLOCKS =
			new it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue();
	/**
	 * Keys already present in {@link #CHECK_BLOCKS}. Chunk packets and propagation often schedule the
	 * same block repeatedly before the light worker receives a timeslice; one final check is enough,
	 * so coalescing here removes redundant queue traffic without losing a later state change.
	 */
	private static final it.unimi.dsi.fastutil.longs.LongOpenHashSet CHECK_BLOCKS_PENDING =
			new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
	private static final Object CHECK_BLOCKS_LOCK = new Object();
	/** Light-thread only: one batch lifted out of {@link #CHECK_BLOCKS}, reused so the drain allocates nothing. */
	private static final it.unimi.dsi.fastutil.longs.LongArrayList CHECK_SCRATCH =
			new it.unimi.dsi.fastutil.longs.LongArrayList();
	/** Light-thread only: the single position object every replayed checkBlock is fed through. */
	private static final net.minecraft.util.math.BlockPos.Mutable CHECK_POS =
			new net.minecraft.util.math.BlockPos.Mutable();
	private static final long IDLE_PARK_NANOS = 5_000_000L; // 5 ms
	private static final int DRAIN_CHUNK = 4096;

	// Light-update render budgeting: onLightUpdate fires (often thousands of times during a chunk-load
	// light burst) on the light thread and must re-schedule a section render on the MAIN thread. Instead
	// of one client.execute() trampoline per update — a per-frame flood of lambdas — we coalesce the
	// affected section keys into this deduped set and drain a bounded number per frame on the render
	// thread. Dedup alone collapses the burst (a section hit by many updates → one re-mesh); the budget
	// then spreads what remains so a chunk load can't stall a frame.
	// Primitive, not Set<Long>: onLightUpdate fires up to ~40k times a second while a world streams
	// in, and a boxed set allocated one Long per call — 40k short-lived objects per second of pure GC
	// pressure feeding straight into the frame stutter. fastutil has no concurrent long set, so the
	// two participants (light thread adds, render thread drains once per frame) share a plain lock:
	// an add is a few nanoseconds and the drain is bounded by its budget, so contention is negligible.
	private static final it.unimi.dsi.fastutil.longs.LongOpenHashSet PENDING_RENDER =
			new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
	private static final Object PENDING_RENDER_LOCK = new Object();
	/** Render-thread scratch: keys chosen by one drain, reused so the drain itself allocates nothing. */
	private static final it.unimi.dsi.fastutil.longs.LongArrayList DRAIN_SCRATCH =
			new it.unimi.dsi.fastutil.longs.LongArrayList();

	private static volatile LightingProvider clientProvider;
	private static volatile Thread lightThread;
	/**
	 * Publishes a coherent light-engine state to immutable section extraction.
	 * The light worker owns every mutation under the write lock; the render
	 * thread holds the read lock only while copying one bounded snapshot slice.
	 */
	private static final ReentrantReadWriteLock LIGHT_STATE_LOCK = new ReentrantReadWriteLock();
	/** Incremented after each published async-light mutation batch. */
	private static final AtomicLong LIGHT_STATE_GENERATION = new AtomicLong();
	/**
	 * Monotonic revision for custom-renderer snapshots. Unlike the legacy provider generation this
	 * advances for every light notification that may invalidate a terrain mesh.
	 */
	/** Per-light-section revision for custom snapshot validation; guarded by PENDING_RENDER_LOCK. */
	private static final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap RENDER_LIGHT_REVISIONS =
			new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();

	// Diagnostics: lets the overlay prove the light engine is event-driven.
	private static final AtomicLong ENQUEUED = new AtomicLong();
	private static final AtomicLong UPDATES = new AtomicLong();
	private static long rateWindowStartMs = System.currentTimeMillis();
	private static long enqueuedAtWindowStart;
	private static long updatesAtWindowStart;
	private static volatile int enqueuedPerSec;
	private static volatile int updatesPerSec;
	private static final AtomicLong RENDER_LIGHT_SEQUENCE =
			new AtomicLong();
	private ClientLightDispatcher() {
	}

	/** Called when a new ClientChunkManager (= new client world) is created. */
	public static void register(LightingProvider provider) {
		QUEUE.clear();
		synchronized (CHECK_BLOCKS_LOCK) {
			CHECK_BLOCKS.clear();
			CHECK_BLOCKS_PENDING.clear();
		}
		synchronized (PENDING_RENDER_LOCK) {
			PENDING_RENDER.clear();
			RENDER_LIGHT_REVISIONS.clear();
		}
		clientProvider = provider;
		if (asyncActive()) {
			ensureThread();
			MoneyakShaders.LOGGER.info("[Optimized Loading] Async light engine attached to new client world");
		}
	}

	/**
	 * True when a call on this provider should be recorded and replayed on the
	 * light thread instead of running inline. Only ever true for the client
	 * world's provider - the server's ServerLightingProvider (own mailbox
	 * thread model) and LightingProvider.DEFAULT are never registered.
	 */
	public static boolean shouldDeflect(LightingProvider provider) {
		return provider == clientProvider
				&& provider != null
				&& Thread.currentThread() != lightThread
				&& asyncActive();
	}

	/**
	 * True whenever async lighting is enabled, so enabled-columns reads take the shared lock. Gating
	 * on the config flag ALONE (not also {@code clientProvider != null}) is deliberate: during world
	 * load there is a window where the light thread is already replaying write-locked mutations but
	 * {@code clientProvider} isn't published yet — gating on it left that read unlocked and let the
	 * render thread probe a half-resized {@code LongOpenHashSet} (the ArrayIndexOutOfBounds crash).
	 */
	public static boolean asyncActive() {
		MoneyakShadersConfig config = MoneyakShadersConfig.get();
		// The client light provider also mutates structures the experimental renderer snapshots.
		// Keeping those writes on the render thread is the only coherent boundary for its meshes.
		return config.asyncLightUpdates && !config.experimentalRenderer;
	}

	/**
	 * Safe async path for the custom terrain renderer. The live Minecraft light provider publishes on
	 * the client thread; its changed sections are then coalesced here and rebuilt by the immutable
	 * snapshot/mesh worker pipeline. Workers never read or mutate the provider itself.
	 */
	public static boolean customRendererAsyncActive() {
		MoneyakShadersConfig config = MoneyakShadersConfig.get();
		return config.asyncLightUpdates && config.experimentalRenderer;
	}

	/**
	 * Acquire a bounded read window for section snapshot extraction.
	 *
	 * @return {@code true} when a matching {@link #unlockSnapshotRead()} is required
	 */
	public static boolean lockSnapshotRead() {
		if (!asyncActive()) {
			return false;
		}
		LIGHT_STATE_LOCK.readLock().lock();
		return true;
	}

	/** Release the read window opened by {@link #lockSnapshotRead()}. */
	public static void unlockSnapshotRead() {
		LIGHT_STATE_LOCK.readLock().unlock();
	}

	/** Read only while holding the snapshot read lock. */
	public static long lightStateGeneration() {
		return LIGHT_STATE_GENERATION.get();
	}

	/** Signature of the 3x3x3 light neighbourhood which can physically reach one terrain section. */
	public static long renderLightRevisionSignature(
			int sectionX,
			int sectionY,
			int sectionZ) {

		synchronized (PENDING_RENDER_LOCK) {
			long signature = 0x9E3779B97F4A7C15L;

			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {

						long revision =
								RENDER_LIGHT_REVISIONS
										.getOrDefault(
												ChunkSectionPos.asLong(
														sectionX + dx,
														sectionY + dy,
														sectionZ + dz),
												0L);

						signature =
								(signature ^ revision)
										* 0x100000001B3L;
					}
				}
			}

			return signature;
		}
	}

		public static int getQueueSize() {
		return QUEUE.size();
	}

	/** Light thread: a section's light changed and needs a render reschedule. Coalesced (deduped). */
	public static void queueLightRender(long sectionKey) {
		if (!MoneyakShadersConfig.get().experimentalRenderer)
			return;

		synchronized (PENDING_RENDER_LOCK) {
			RENDER_LIGHT_REVISIONS.put(
					sectionKey,
					RENDER_LIGHT_SEQUENCE.incrementAndGet());

			PENDING_RENDER.add(sectionKey);
		}
	}

	/**
	 * Invalidates custom-renderer snapshots immediately for a real block edit, without putting the
	 * section into the general provider-notification queue. The renderer owns a separate delayed
	 * edit-relight lane which runs after vanilla propagation has settled; mixing that guarantee into
	 * {@link #PENDING_RENDER} let hundreds of sleeping underground notifications delay a visible edit.
	 */
	public static void noteBlockEditLightChange(long sectionKey) {
		if (!MoneyakShadersConfig.get().experimentalRenderer)
			return;

		synchronized (PENDING_RENDER_LOCK) {
			RENDER_LIGHT_REVISIONS.put(
					sectionKey,
					RENDER_LIGHT_SEQUENCE.incrementAndGet());
		}
	}

	public static int pendingRenderCount() {
		synchronized (PENDING_RENDER_LOCK) {
			return PENDING_RENDER.size();
		}
	}

	/** Forget light-remesh bookkeeping when the custom renderer evicts a section from its live area. */
	public static void forgetRenderSection(long lightSectionKey) {
		/*
		* A terrain section disappearing does NOT imply that the light section
		* at the same coordinate is unused. Up to 26 neighbouring terrain
		* sections may still consume it.
		*/
		if (com.moneyakshaders.render.ExperimentalSectionRender
				.hasResidentLightConsumer(lightSectionKey)
				|| com.moneyakshaders.render.ExperimentalSectionRender
						.hasPendingLightConsumer(lightSectionKey)) {
			return;
		}

		synchronized (PENDING_RENDER_LOCK) {
			PENDING_RENDER.remove(lightSectionKey);
			RENDER_LIGHT_REVISIONS.remove(lightSectionKey);
		}
	}

		/**
		 * Render thread: apply up to {@code budget} coalesced light-render updates (0 = all). Each goes
	 * STRAIGHT to the experimental renderer's async dirty queue — NOT through vanilla's
	 * {@code scheduleChunkRender}: vanilla would also rebuild ITS section geometry (pure waste — vanilla
	 * terrain isn't drawn and block entities sample light live), and the old path let light bursts grab
	 * the synchronous mesh budget, stalling frames while moving/turning during chunk loading.
	 */
	public static void drainLightRenderUpdates(net.minecraft.client.render.WorldRenderer worldRenderer, int ignoredBudget) {
		boolean tracked = com.moneyakshaders.MoneyakShadersConfig.get().frameBudgetEnabled;
		if (tracked) FrameWorkBudget.startBucket(FrameWorkBudget.BUCKET_LIGHT_APPLY);
		try {
			drainLightRenderUpdatesBody(worldRenderer);
		} finally {
			if (tracked) FrameWorkBudget.endBucket(FrameWorkBudget.BUCKET_LIGHT_APPLY);
		}
	}

	private static void drainLightRenderUpdatesBody(net.minecraft.client.render.WorldRenderer worldRenderer) {
		net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
		int playerCx = client != null && client.player != null ? client.player.getBlockX() >> 4 : 0;
		int playerCz = client != null && client.player != null ? client.player.getBlockZ() >> 4 : 0;
		int retainRadius = client != null
				? Math.max(client.options.getClampedViewDistance(), MoneyakShadersConfig.get().shadowDistanceChunks) + 2
				: 2;
		// Move every currently relevant notification in one coherent drain. There is deliberately no
		// per-frame quota, settle delay or section cooldown here: those three mechanisms let adjacent
		// chunks display different light generations. Deduplication remains, and the renderer defers an
		// entire invisible surface/underground domain instead of partially applying a visible one.
		DRAIN_SCRATCH.clear();
		synchronized (PENDING_RENDER_LOCK) {
			if (PENDING_RENDER.isEmpty()) return;
			for (it.unimi.dsi.fastutil.longs.LongIterator lit = PENDING_RENDER.iterator(); lit.hasNext(); ) {
				long key = lit.nextLong();
				int sectionX = net.minecraft.util.math.ChunkSectionPos.unpackX(key);
				int sectionZ = net.minecraft.util.math.ChunkSectionPos.unpackZ(key);
				if (!com.moneyakshaders.render.ExperimentalSectionRender
						.hasResidentLightConsumer(key)) {
					// Light data belongs to a light section, but a terrain mesh samples a one-cell halo.
					// Therefore any of the surrounding 3x3x3 terrain sections can consume this key even
					// when there is no GPU mesh at the exact light-section coordinate. Dropping on exact
					// residency left underwater floors and chunk-boundary faces permanently dark until an
					// unrelated block edit rebuilt their geometry. Only discard when NO neighbour consumes
					// the light section; an already-running consumer is protected by the revision signature.
					lit.remove();
					if (!com.moneyakshaders.render.ExperimentalSectionRender.hasPendingLightConsumer(key)) {
						RENDER_LIGHT_REVISIONS.remove(key);
					}
					continue;
				}
				if (Math.abs(sectionX - playerCx) > retainRadius || Math.abs(sectionZ - playerCz) > retainRadius) {
					// A streamed-away section will be snapshotted from current light when its column is
					// received again. Retaining its old request made this set grow without bound during
					// flight (115k live keys in the captured session) and every new event rescanned it.
					lit.remove();
					RENDER_LIGHT_REVISIONS.remove(key);
					continue;
				}
				lit.remove();
				DRAIN_SCRATCH.add(key);
			}
		}
		for (int applied = 0; applied < DRAIN_SCRATCH.size(); applied++) {
			long key = DRAIN_SCRATCH.getLong(applied);
			if (com.moneyakshaders.render.ExperimentalSectionRender.shouldDeferLightRefresh(key)) {
				// The renderer records the dirty bit on each sleeping consumer. Keeping this light section
				// in the global queue as well made every invisible update re-enter the queue forever (844
				// stable entries in the live capture). A sleeping mesh is refreshed once, when it wakes.
				continue;
			}
			com.moneyakshaders.render.ExperimentalSectionRender.markLightDirtyAsync(
					net.minecraft.util.math.ChunkSectionPos.unpackX(key),
					net.minecraft.util.math.ChunkSectionPos.unpackY(key),
					net.minecraft.util.math.ChunkSectionPos.unpackZ(key));
		}
	}

	/**
	 * Fast lane for {@code checkBlock}: the position travels packed, with no lambda and no BlockPos
	 * copy. See {@link #CHECK_BLOCKS}.
	 */
	public static void enqueueCheckBlock(long packedPos) {
		// ENQUEUED drives the always-available FPS overlay rate, so it stays. The second counter only
		// feeds the once-a-second OL-DEBUG dump — at ~13k calls/s that CAS is not worth paying for
		// when nothing reads it.
		ENQUEUED.incrementAndGet();
		if (MoneyakShadersConfig.get().debugStats) {
			DebugStats.lightEnqueued.incrementAndGet();
		}
		synchronized (CHECK_BLOCKS_LOCK) {
			if (!CHECK_BLOCKS_PENDING.add(packedPos)) {
				return; // an equivalent re-check is already waiting for the light worker
			}
			CHECK_BLOCKS.enqueue(packedPos);
		}
		Thread thread = lightThread;
		if (thread != null) {
			LockSupport.unpark(thread);
		} else {
			ensureThread();
		}
	}

	/**
	 * Light thread: replay one bounded batch of packed checkBlock positions. Returns how many ran.
	 * The batch is lifted out under a single lock acquisition and replayed outside it, so producers
	 * never wait on the light engine's own work.
	 */
	private static int drainCheckBlocks(LightingProvider provider) {
		if (provider == null) {
			return 0; // no world yet — leave them queued rather than dropping light updates
		}
		CHECK_SCRATCH.clear();
		synchronized (CHECK_BLOCKS_LOCK) {
			int n = Math.min(CHECK_BLOCKS.size(), DRAIN_CHUNK);
			for (int i = 0; i < n; i++) {
				long key = CHECK_BLOCKS.dequeueLong();
				// Remove before replay. If a new packet changes this block while replay runs, it is
				// admitted as a fresh request for the next batch instead of being accidentally folded
				// into an already-consumed state.
				CHECK_BLOCKS_PENDING.remove(key);
				CHECK_SCRATCH.add(key);
			}
		}
		int size = CHECK_SCRATCH.size();
		for (int i = 0; i < size; i++) {
			long p = CHECK_SCRATCH.getLong(i);
			// Re-entering checkBlock ON this thread makes the mixin pass straight through to vanilla,
			// which only reads the position (it packs it back to a long immediately), so one shared
			// mutable position is safe here.
			provider.checkBlock(CHECK_POS.set(
					net.minecraft.util.math.BlockPos.unpackLongX(p),
					net.minecraft.util.math.BlockPos.unpackLongY(p),
					net.minecraft.util.math.BlockPos.unpackLongZ(p)));
		}
		return size;
	}

	public static void enqueue(Runnable replay) {
		ENQUEUED.incrementAndGet();
		if (MoneyakShadersConfig.get().debugStats) {
			DebugStats.lightEnqueued.incrementAndGet();
		}
		QUEUE.add(replay);
		Thread thread = lightThread;
		if (thread != null) {
			LockSupport.unpark(thread);
		} else {
			ensureThread();
		}
	}

	/** Light mutations queued per second (block changes, section data, ...). */
	public static int getEnqueuedPerSec() {
		refreshRates();
		return enqueuedPerSec;
	}

	/** Light propagation updates actually performed per second. */
	public static int getUpdatesPerSec() {
		refreshRates();
		return updatesPerSec;
	}

	private static synchronized void refreshRates() {
		long now = System.currentTimeMillis();
		long dt = now - rateWindowStartMs;
		if (dt < 1000L) {
			return;
		}
		long enq = ENQUEUED.get();
		long upd = UPDATES.get();
		enqueuedPerSec = (int) ((enq - enqueuedAtWindowStart) * 1000L / dt);
		updatesPerSec = (int) ((upd - updatesAtWindowStart) * 1000L / dt);
		enqueuedAtWindowStart = enq;
		updatesAtWindowStart = upd;
		rateWindowStartMs = now;
	}

	private static synchronized void ensureThread() {
		if (lightThread == null) {
			Thread thread = new Thread(ClientLightDispatcher::run, "MoneyakShaders-Light");
			thread.setDaemon(true);
			thread.setPriority(Thread.NORM_PRIORITY - 1);
			// Publish before start so shouldDeflect sees it from the thread itself.
			lightThread = thread;
			thread.start();
		}
	}

	private static void run() {
		MoneyakShaders.LOGGER.info("[Optimized Loading] Client light thread started");
		while (true) {
			try {
				// The counters are useful while diagnosing a light storm, but each is a
				// contended atomic operation when chunks arrive on the network thread.
				// Read the immutable runtime setting once per propagation batch instead.
				boolean debugStats = MoneyakShadersConfig.get().debugStats;
				LightingProvider provider = clientProvider;
				boolean replayedAny = false;
				int done = 0;
				LIGHT_STATE_LOCK.writeLock().lock();
				try {
					Runnable task;
					int drained = 0;
					while ((task = QUEUE.poll()) != null) {
						// Replaying invokes the same public LightingProvider method;
						// on this thread the mixin passes straight through to vanilla.
						task.run();
						replayedAny = true;
						if (++drained >= DRAIN_CHUNK) {
							break; // run a propagation pass before draining more
						}
					}
					// Structural calls above go first; the packed checkBlock fast lane follows.
					if (drainCheckBlocks(provider) > 0) {
						replayedAny = true;
					}
					boolean hasUpd = provider != null && provider.hasUpdates();
					if (debugStats && hasUpd) {
						DebugStats.lightHasUpdatesTrue.incrementAndGet();
					}
					if (provider != null && (replayedAny || hasUpd)) {
						if (debugStats) {
							DebugStats.lightUpdateCalls.incrementAndGet();
						}
						done = provider.doLightUpdates();
						if (done > 0) {
							UPDATES.addAndGet(done);
							if (debugStats) {
								DebugStats.lightUpdatesDone.addAndGet(done);
							}
						}
					}
					if (replayedAny || done > 0) {
						LIGHT_STATE_GENERATION.incrementAndGet();
					}
				} finally {
					LIGHT_STATE_LOCK.writeLock().unlock();
				}
				DebugStats.maybeDump();
				if (QUEUE.isEmpty()) {
					LockSupport.parkNanos(IDLE_PARK_NANOS);
				}
			} catch (Throwable t) {
				MoneyakShaders.LOGGER.error("[Optimized Loading] Error on client light thread", t);
			}
		}
	}
}
