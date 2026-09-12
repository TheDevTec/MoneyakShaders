package com.moneyakshaders.render;

import java.util.List;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
	import java.util.concurrent.atomic.AtomicInteger;
	import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.world.BlockRenderView;

/**
 * Phase 2.3 — off-thread section meshing.
 *
 * <p>The render thread submits sections; daemon worker threads run
 * {@link McSectionMesher#mesh} and put results on a queue; the render thread
 * drains completed results each frame and uploads them to the GPU (GL
 * operations are render-thread-only). Uses a fixed thread pool so meshing is
 * always parallel but never floods the system.
 *
 * <p>Every submission receives a generation token. The token stays in {@link #inFlight} until the
 * render thread accepts its completed result, so a cancelled task can never publish stale geometry
 * after the same packed section coordinate has been reused by a later multiplayer chunk packet.
 */
final class ChunkMeshExecutor {
	private static final int TOP_STREAM_PRIORITY = 1;
	record MeshResult(long key, long generation, long contentRevision, long lightGeneration, int missingColumnMask,
            SectionMeshData mesh, int bx, int by, int bz, int streamPriority, long readyNs) {}

	private static final int MESH_TIME_HISTORY = 120;
	private final long[] meshTimeSamplesNs = new long[MESH_TIME_HISTORY];
	private int meshTimeCount;
	private int meshTimeCursor;

    private static final AtomicInteger THREAD_IDX = new AtomicInteger();
	/** Occupies a key while an invalidated worker drains, preventing a double mesh burst. */
	private static final long CANCELLED_GENERATION = Long.MIN_VALUE;
    private final ThreadPoolExecutor pool;
	private final ThreadPoolExecutor editPool = BlockEditLane.executor();
	private final Semaphore editReadySlots = new Semaphore(BlockEditLane.READY);
	private final MeshWorkerBudget workerBudget = new MeshWorkerBudget();
    private final ConcurrentHashMap<Long, Long> inFlight = new ConcurrentHashMap<>();
	/**
	 * Allocation-free membership mirror for the render-thread admission hot path.  A
	 * ConcurrentHashMap<Long, ...>#containsKey(long) boxes the packed section key on every scan;
	 * during initial streaming that used to create millions of temporary Long instances.
	 * Worker removals are rare compared with render-thread reads, so a short synchronized
	 * primitive lookup is both cheaper and easier to keep exact than another boxed map.
	 */
	private final LongOpenHashSet inFlightKeys = new LongOpenHashSet();
	/** Missing 3×3 halo columns for a running immutable snapshot, keyed by target section. */
	private final ConcurrentHashMap<Long, Integer> inFlightMissingColumns = new ConcurrentHashMap<>();
    private final PriorityBlockingQueue<MeshResult> done = new PriorityBlockingQueue<>(32,
            java.util.Comparator.comparingInt(MeshResult::streamPriority).thenComparingLong(MeshResult::readyNs));
    private final Semaphore readySlots;
	/** Prevent background completions from occupying every RAM-ready slot before surface work arrives. */
	private final Semaphore backgroundReadySlots;
    // Monotonic sequence so tasks of equal priority keep FIFO order in the priority queue.
    private final AtomicLong seq = new AtomicLong();

    /**
     * A queued meshing task ordered by priority (lower = sooner), then submission order. Block
     * edits and the player's own section submit at priority 0 so they jump ahead of the bulk
     * ring-load tasks (priority 1) — otherwise a fresh edit waited behind hundreds of pending
     * far-chunk meshes, which is the visible place/break delay.
     */
    private static final class Task implements Runnable, Comparable<Task> {
        final int priority;
        final long order;
        final Runnable body;

        Task(int priority, long order, Runnable body) {
            this.priority = priority;
            this.order = order;
            this.body = body;
        }

        @Override
        public int compareTo(Task o) {
            int c = Integer.compare(priority, o.priority);
            return c != 0 ? c : Long.compare(order, o.order);
        }

        @Override
        public void run() {
            body.run();
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    ChunkMeshExecutor(int threads) {
		int configuredReady = com.moneyakshaders.MoneyakShadersConfig.get().maxReadySectionMeshes;
		int readyLimit = configuredReady > 0 ? configuredReady
				: Math.max(threads * 2, Math.max(1,
						com.moneyakshaders.MoneyakShadersConfig.get().maxChunkUploadsPerFrame) * 4);
		readySlots = new Semaphore(readyLimit);
		int reservedForTopLanes = Math.max(1, readyLimit / 4);
		backgroundReadySlots = new Semaphore(Math.max(1, readyLimit - reservedForTopLanes));
        pool = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                (PriorityBlockingQueue) new PriorityBlockingQueue<Task>(),
                r -> {
                    Thread t = new Thread(r, "chunk-mesher-" + THREAD_IDX.getAndIncrement());
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY - 1); // don't steal from the render thread
                    return t;
                });
    }

    /**
     * Submit a section for async meshing. No-op (returns false) if this key is
     * already in-flight — dedup so a section isn't double-meshed. {@code highPriority}
     * (block edits, player section) jumps ahead of bulk ring loads.
     */
    boolean submit(long key, BlockRenderView world, BlockRenderManager brm,
            BlockColors colors, int bx, int by, int bz, boolean highPriority) {
		// PriorityBlockingQueue is deliberately unbounded, so enforce the configured ceiling before
		// publishing the key. A full queue means the section stays eligible for a later frame; it never
		// turns a fast server chunk burst into an ever-growing heap of task and closure allocations.
		int streamPriority = highPriority ? 0 : 3;
		if (!hasWorkerQueueCapacity(streamPriority)) return false;
        long generation = seq.getAndIncrement();
        if (!putInFlightIfAbsent(key, generation)) {
            return false; // already queued or running
        }
		inFlightMissingColumns.remove(key);
        if (com.moneyakshaders.MoneyakShadersConfig.get().chunkLoadTimelineEnabled) {
            com.moneyakshaders.client.ChunkLoadTimeline.mark(key,
                    com.moneyakshaders.client.ChunkLoadTimeline.PHASE_MESH_QUEUED);
        }
        pool.execute(new Task(streamPriority, generation, () -> {
            long meshStartNs = System.nanoTime();
            try {
                if (!ownsGeneration(key, generation)) return;
                boolean timeline = com.moneyakshaders.MoneyakShadersConfig.get().chunkLoadTimelineEnabled;
                if (timeline) {
                    com.moneyakshaders.client.ChunkLoadTimeline.mark(key,
                            com.moneyakshaders.client.ChunkLoadTimeline.PHASE_MESH_STARTED);
                }
                SectionMeshData mesh = McSectionMesher.mesh(world, brm, colors, bx, by, bz);
				mesh.prepareUploadMetadata();
                if (timeline) {
                    com.moneyakshaders.client.ChunkLoadTimeline.mark(key,
                            com.moneyakshaders.client.ChunkLoadTimeline.PHASE_MESH_DONE);
                }
                // Keep the generation registered until the render thread drains this result. A cancelled
                // or superseded task may finish normally, but drainTo will reject it by token instead of
                // letting it overwrite a newer mesh for the same section coordinate.
				publishCompleted(key, generation, 0L, -1L, 0, mesh, bx, by, bz, streamPriority);
            } catch (Throwable failure) {
                failTask(key, generation, failure);
            } finally {
                recordMeshTime(System.nanoTime() - meshStartNs);
            }
        }));
        return true;
    }

    /** Same bounded worker path, but with a fully immutable render-thread snapshot. */
    boolean submit(long key, SectionInputSnapshot snapshot, BlockRenderManager brm,
            BlockColors colors, int bx, int by, int bz, int streamPriority, long contentRevision) {
		boolean accepted = submit(key, (BlockRenderView) snapshot, brm, colors, bx, by, bz, streamPriority,
				contentRevision, snapshot.renderLightGeneration, snapshot.missingColumnMask,
                snapshot::recycle);
        return accepted;
    }

	private boolean submit(long key, BlockRenderView world, BlockRenderManager brm,
			BlockColors colors, int bx, int by, int bz, int streamPriority, long contentRevision,
			long lightGeneration,
			int missingColumnMask, Runnable releaseInput) {
		if (!hasWorkerQueueCapacity(streamPriority)) return false;
		long generation = seq.getAndIncrement();
		if (!putInFlightIfAbsent(key, generation)) return false;
		if (missingColumnMask == 0) inFlightMissingColumns.remove(key);
		else inFlightMissingColumns.put(key, missingColumnMask);
		if (com.moneyakshaders.MoneyakShadersConfig.get().chunkLoadTimelineEnabled) {
			com.moneyakshaders.client.ChunkLoadTimeline.mark(key,
					com.moneyakshaders.client.ChunkLoadTimeline.PHASE_MESH_QUEUED);
		}
		(streamPriority < 0 ? editPool : pool).execute(new Task(streamPriority, generation, () -> {
			long meshStartNs = System.nanoTime();
			boolean inputReleased = false;
			try {
				if (!ownsGeneration(key, generation)) return;
				boolean timeline = com.moneyakshaders.MoneyakShadersConfig.get().chunkLoadTimelineEnabled;
				if (timeline) com.moneyakshaders.client.ChunkLoadTimeline.mark(key,
						com.moneyakshaders.client.ChunkLoadTimeline.PHASE_MESH_STARTED);
				SectionMeshData mesh = McSectionMesher.mesh(world, brm, colors, bx, by, bz);
				mesh.prepareUploadMetadata();
				if (timeline) com.moneyakshaders.client.ChunkLoadTimeline.mark(key,
						com.moneyakshaders.client.ChunkLoadTimeline.PHASE_MESH_DONE);
				// The mesh owns its output. Return copied world cells before a full GPU-ready queue
				// blocks this worker, otherwise upload backpressure pins one snapshot per worker.
				inputReleased = true;
				if (releaseInput != null) releaseInput.run();
				recordMeshTime(System.nanoTime() - meshStartNs);
				publishCompleted(key, generation, contentRevision, lightGeneration, missingColumnMask, mesh, bx, by, bz,
						streamPriority);
			} catch (Throwable failure) {
				failTask(key, generation, failure);
			} finally {
				if (!inputReleased) {
					recordMeshTime(System.nanoTime() - meshStartNs);
					if (releaseInput != null) releaseInput.run();
				}
			}
		}));
		return true;
	}

	/** Reserve the final quarter of queued worker capacity for urgent and surface meshes. */
	private boolean hasWorkerQueueCapacity(int streamPriority) {
		if (streamPriority < 0) return editPool.getQueue().remainingCapacity() > 0;
		int queueLimit = com.moneyakshaders.MoneyakShadersConfig.get().chunkBuilderQueueLimit;
		if (queueLimit <= 0) return true;
		int queued = pool.getQueue().size();
		if (queued >= queueLimit) return false;
		if (streamPriority <= TOP_STREAM_PRIORITY) return true;
		return queued < Math.max(1, queueLimit * 3 / 4);
	}

	private synchronized void recordMeshTime(long elapsedNs) {
		meshTimeSamplesNs[meshTimeCursor++ % MESH_TIME_HISTORY] = elapsedNs;
		if (meshTimeCount < MESH_TIME_HISTORY) meshTimeCount++;
	}

	double meshP50Ms() { return meshPercentileMs(0.50); }
	double meshP95Ms() { return meshPercentileMs(0.95); }
	double meshP99Ms() { return meshPercentileMs(0.99); }

	private synchronized double meshPercentileMs(double percentile) {
		if (meshTimeCount == 0) return 0.0;
		long[] sorted = Arrays.copyOf(meshTimeSamplesNs, meshTimeCount);
		Arrays.sort(sorted);
		return sorted[Math.min(meshTimeCount - 1,
				(int) Math.floor((meshTimeCount - 1) * percentile))] / 1_000_000.0;
	}

	private void failTask(long key, long generation, Throwable failure) {
		if (removeInFlight(key, generation) || removeInFlight(key, CANCELLED_GENERATION)) {
			inFlightMissingColumns.remove(key);
			com.moneyakshaders.MoneyakShaders.LOGGER.warn(
					"[Optimized Loading] Section mesh failed at key {}; releasing its pipeline slot", key, failure);
		}
	}

    /**
     * Cancel a pending/running task. If the worker finishes before seeing the
     * cancellation its result will be discarded (see comment in {@link #submit}).
     */
    void cancel(long key) {
		removeInFlight(key);
		inFlightMissingColumns.remove(key);
    }

	/**
	 * Invalidates a changed section but keeps its key occupied until the already-running worker exits.
	 * This rejects a stale result without starting a second expensive mesh for the same section.
	 */
	void invalidate(long key) {
		inFlight.computeIfPresent(key, (ignored, generation) -> CANCELLED_GENERATION);
	}

    /**
     * Invalidate all work derived from a previous resource generation. Workers already executing are
     * allowed to finish, but their token is gone so {@link #publishCompleted} frees the native mesh
     * instead of exposing stale atlas UVs to the render thread.
     */
    void cancelAll() {
		clearInFlight();
		inFlightMissingColumns.clear();
    }

    boolean isInFlight(long key) {
		synchronized (inFlightKeys) {
			return inFlightKeys.contains(key);
		}
    }

	private boolean putInFlightIfAbsent(long key, long generation) {
		synchronized (inFlightKeys) {
			if (inFlight.putIfAbsent(key, generation) != null) return false;
			inFlightKeys.add(key);
			return true;
		}
	}

	private boolean removeInFlight(long key, long generation) {
		synchronized (inFlightKeys) {
			if (!inFlight.remove(key, generation)) return false;
			inFlightKeys.remove(key);
			return true;
		}
	}

	private Long removeInFlight(long key) {
		synchronized (inFlightKeys) {
			Long removed = inFlight.remove(key);
			if (removed != null) inFlightKeys.remove(key);
			return removed;
		}
	}

	private void clearInFlight() {
		synchronized (inFlightKeys) {
			inFlight.clear();
			inFlightKeys.clear();
		}
	}

	/** True only if this in-flight mesh captured the newly arrived column as absent. */
	boolean needsHaloRefresh(long key, int chunkX, int chunkZ) {
		Integer mask = inFlightMissingColumns.get(key);
		if (mask == null || mask.intValue() == 0) return false;
		int dx = chunkX - net.minecraft.util.math.ChunkSectionPos.unpackX(key) + 1;
		int dz = chunkZ - net.minecraft.util.math.ChunkSectionPos.unpackZ(key) + 1;
		return dx >= 0 && dx <= 2 && dz >= 0 && dz <= 2
				&& (mask.intValue() & (1 << (dx * 3 + dz))) != 0;
	}

    int inFlightCount() {
        return inFlight.size();
    }

	/** Called on the render thread; never rebuilds the pool or cancels accepted snapshots. */
	void updateWorkerBudget() {
		var cfg = com.moneyakshaders.MoneyakShadersConfig.get();
		int cpus = Runtime.getRuntime().availableProcessors();
		int maximum = cfg.chunkBuilderThreads > 0 ? cfg.effectiveChunkBuilderThreads()
				: Math.max(1, Math.min(16, (cpus - 2) / 2));
		int current = pool.getCorePoolSize();
		int desired = cfg.chunkBuilderThreads > 0 ? maximum : workerBudget.update(
				System.nanoTime() / 1_000_000L, current, maximum, pool.getQueue().size(),
				readySlots.availablePermits() == 0,
				com.moneyakshaders.client.FrameProfiler.avgMs(), cfg.frameBudgetEnabled
						? com.moneyakshaders.client.FrameWorkBudget.effectiveTargetMs() : cfg.frameBudgetTargetMs);
		if (desired == current) return;
		com.moneyakshaders.MoneyakShaders.LOGGER.info("[Plan C/GL] Mesh workers {} -> {} (queued={}, ready={})",
				current, desired, pool.getQueue().size(), done.size());
		if (desired > current) { pool.setMaximumPoolSize(desired); pool.setCorePoolSize(desired); }
		else { pool.setCorePoolSize(desired); pool.setMaximumPoolSize(desired); }
	}

	/**
	 * Render-thread bookkeeping occasionally needs to retain a follow-up refresh for meshes whose
	 * neighbour chunk arrived after their immutable input was captured.  The set is tiny and concurrent;
	 * exposing keys avoids treating an in-flight mesh as if it were already visible geometry.
	 */
	void forEachInFlightKey(LongConsumer consumer) {
		for (Long key : inFlight.keySet()) {
			consumer.accept(key.longValue());
		}
	}

    /** True when nothing is queued/running and no completed mesh is waiting to upload. */
    boolean idle() {
        return inFlight.isEmpty() && done.isEmpty();
    }

    /** Drain at most {@code max} completed meshes into {@code sink}. Render thread only. */
    int drainTo(List<MeshResult> sink, int max) {
        int n = 0;
        MeshResult r;
        while (n < max && (r = done.poll()) != null) {
            if (removeInFlight(r.key(), r.generation())) {
				inFlightMissingColumns.remove(r.key());
                sink.add(r);
                n++;
            } else {
				removeInFlight(r.key(), CANCELLED_GENERATION);
				inFlightMissingColumns.remove(r.key());
                r.mesh().free(); // cancelled or superseded while the worker was meshing
				releaseReadyResultSlot(r.streamPriority());
            }
        }
        return n;
    }

	/** Called by the render thread after an accepted RAM-ready mesh is uploaded or discarded. */
	void releaseReadyResultSlot(int streamPriority) {
		if (streamPriority < 0) { editReadySlots.release(); return; }
		readySlots.release();
		if (streamPriority > TOP_STREAM_PRIORITY) backgroundReadySlots.release();
	}

	private void publishCompleted(long key, long generation, long contentRevision, long lightGeneration,
			int missingColumnMask,
			SectionMeshData mesh, int bx, int by, int bz, int streamPriority) {
		Semaphore completionSlots = streamPriority < 0 ? editReadySlots : readySlots;
		while (ownsGeneration(key, generation)) {
			boolean backgroundPermit = false;
			try {
				if (streamPriority > TOP_STREAM_PRIORITY) {
					if (!backgroundReadySlots.tryAcquire(25L, TimeUnit.MILLISECONDS)) continue;
					backgroundPermit = true;
				}
				if (!completionSlots.tryAcquire(25L, TimeUnit.MILLISECONDS)) {
					if (backgroundPermit) backgroundReadySlots.release();
					// The GPU upload queue is full.  Waiting in 2 ms slices wakes every mesh worker
					// hundreds of times per second during a chunk burst; use a longer interruptible
					// wait, then re-check cancellation before publishing the completed mesh.
					continue;
				}
			} catch (InterruptedException interrupted) {
				if (backgroundPermit) backgroundReadySlots.release();
				Thread.currentThread().interrupt();
				removeInFlight(key, generation);
				inFlightMissingColumns.remove(key);
				mesh.free();
				return;
			}
			if (ownsGeneration(key, generation)) {
				done.add(new MeshResult(key, generation, contentRevision, lightGeneration, missingColumnMask, mesh, bx, by, bz,
						streamPriority, System.nanoTime()));
				return;
			}
			completionSlots.release();
			if (backgroundPermit) backgroundReadySlots.release();
		}
		removeInFlight(key, CANCELLED_GENERATION);
		inFlightMissingColumns.remove(key);
		mesh.free(); // cancelled while this worker waited for RAM-ready capacity
	}

	/** Avoid creating a Long for every 2 ms ready-slot retry under an upload backlog. */
	private boolean ownsGeneration(long key, long generation) {
		Long current = inFlight.get(key);
		return current != null && current.longValue() == generation;
	}

    void shutdown() {
        pool.shutdownNow();
		editPool.shutdownNow();
    }
}
