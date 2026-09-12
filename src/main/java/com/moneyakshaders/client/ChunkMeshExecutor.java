package com.moneyakshaders.client;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.moneyakshaders.MoneyakShaders;
import com.moneyakshaders.MoneyakShadersConfig;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.thread.NameableExecutor;

/**
 * Dedicated thread pool for chunk-mesh building.
 *
 * <p>Vanilla hands the {@code ChunkBuilder} the shared "Worker-Main" pool, so
 * while chunks load, mesh-building tasks queue up behind world generation and
 * lighting tasks (and vice versa). Keeping meshing on its own pool lets both
 * pipelines run at full tilt.
 *
 * <p>The pool is created once and intentionally never shut down: vanilla's
 * {@code ChunkBuilder.stop()} does not own its executor either (it is handed
 * the global worker pool), and the daemon threads die with the JVM.
 */
public final class ChunkMeshExecutor {
	private static volatile NameableExecutor instance;
	private static volatile java.util.concurrent.BlockingQueue<Runnable> meshQueue;
	private static volatile ForkJoinPool unboundedPool;

	private static volatile NameableExecutor backgroundInstance;
	private static volatile java.util.concurrent.BlockingQueue<Runnable> backgroundQueue;

	private ChunkMeshExecutor() {
	}

	public static NameableExecutor getOrCreate() {
		NameableExecutor result = instance;
		if (result == null) {
			synchronized (ChunkMeshExecutor.class) {
				result = instance;
				if (result == null) {
					result = instance = create();
				}
			}
		}
		return result;
	}

	private static final int DEFAULT_QUEUE_KEEP_ALIVE_SECONDS = 60;

	private static NameableExecutor create() {
		MoneyakShadersConfig config = MoneyakShadersConfig.get();
		int threads = config.effectiveChunkBuilderThreads();
		int threadPriority = config.effectiveChunkBuilderThreadPriority();
		boolean asyncMode = config.chunkBuilderAsyncMode;
		int queueLimit = config.effectiveChunkBuilderQueueLimit();
		boolean queueIsBounded = config.chunkBuilderQueueLimit > 0;
		AtomicInteger counter = new AtomicInteger(1);

		ThreadFactory threadFactory = r -> {
			Thread thread = new Thread(r, "MoneyakShaders-Mesh-" + counter.getAndIncrement());
			thread.setPriority(threadPriority);
			thread.setDaemon(true);
			return thread;
		};

		Object executor;
		if (queueIsBounded) {
			ArrayBlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(queueLimit);
			ThreadPoolExecutor pool = new ThreadPoolExecutor(
				threads,
				threads,
				DEFAULT_QUEUE_KEEP_ALIVE_SECONDS,
				TimeUnit.SECONDS,
				queue,
				threadFactory,
				config.chunkBuilderDropOldestWhenFull
					? new ThreadPoolExecutor.DiscardOldestPolicy()
					: new ThreadPoolExecutor.DiscardPolicy()
			);
			pool.allowCoreThreadTimeOut(true);
			executor = pool;
			meshQueue = queue;
			MoneyakShaders.LOGGER.info("[Optimized Loading] Chunk mesh building moved to a dedicated bounded pool with {} threads and {} queue limit (dropOldest={})",
				threads,
				queueLimit,
				config.chunkBuilderDropOldestWhenFull);
		} else {
			ForkJoinPool pool = new ForkJoinPool(threads, forkJoinPool -> {
				ForkJoinWorkerThread thread = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(forkJoinPool);
				thread.setName("MoneyakShaders-Mesh-" + counter.getAndIncrement());
				thread.setPriority(threadPriority);
				thread.setDaemon(true);
				return thread;
			}, (thread, throwable) -> MoneyakShaders.LOGGER.error("Uncaught exception in {}", thread.getName(), throwable), asyncMode);
			executor = pool;
			unboundedPool = pool;
			MoneyakShaders.LOGGER.info("[Optimized Loading] Chunk mesh building moved to a dedicated async pool with {} threads (priority={})",
				threads,
				threadPriority);
		}

		return new NameableExecutor((java.util.concurrent.ExecutorService) executor);
	}

	public static NameableExecutor getOrCreateBackground() {
		NameableExecutor result = backgroundInstance;
		if (result == null) {
			synchronized (ChunkMeshExecutor.class) {
				result = backgroundInstance;
				if (result == null) {
					result = backgroundInstance = createBackground();
				}
			}
		}
		return result;
	}

	private static NameableExecutor createBackground() {
		MoneyakShadersConfig config = MoneyakShadersConfig.get();
		int threads = config.effectiveBackgroundWorkerThreads();
		int queueLimit = config.effectiveBackgroundQueueLimit();
		boolean queueIsBounded = config.backgroundQueueLimit > 0;
		AtomicInteger counter = new AtomicInteger(1);

		ThreadFactory threadFactory = r -> {
			Thread thread = new Thread(r, "MoneyakShaders-Background-" + counter.getAndIncrement());
			thread.setDaemon(true);
			return thread;
		};

		Object executor;
		if (queueIsBounded) {
			LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(queueLimit);
			ThreadPoolExecutor pool = new ThreadPoolExecutor(
				threads,
				threads,
				DEFAULT_QUEUE_KEEP_ALIVE_SECONDS,
				TimeUnit.SECONDS,
				queue,
				threadFactory,
				config.backgroundDropOldestWhenFull
					? new ThreadPoolExecutor.DiscardOldestPolicy()
					: new ThreadPoolExecutor.DiscardPolicy()
			);
			pool.allowCoreThreadTimeOut(true);
			executor = pool;
			backgroundQueue = queue;
			MoneyakShaders.LOGGER.info("[Optimized Loading] Background optimization moved to a dedicated pool with {} threads and {} queue limit (dropOldest={})",
				threads,
				queueLimit,
				config.backgroundDropOldestWhenFull);
		} else {
			LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
			ThreadPoolExecutor pool = new ThreadPoolExecutor(
				threads,
				threads,
				DEFAULT_QUEUE_KEEP_ALIVE_SECONDS,
				TimeUnit.SECONDS,
				queue,
				threadFactory,
				new ThreadPoolExecutor.DiscardPolicy()
			);
			pool.allowCoreThreadTimeOut(true);
			executor = pool;
			backgroundQueue = queue;
			MoneyakShaders.LOGGER.info("[Optimized Loading] Background optimization moved to a dedicated unbounded pool with {} threads",
				threads);
		}

		return new NameableExecutor((java.util.concurrent.ExecutorService) executor);
	}

	public static int getQueueSize() {
		java.util.concurrent.BlockingQueue<Runnable> queue = meshQueue;
		if (queue != null) {
			return queue.size();
		}

		ForkJoinPool pool = unboundedPool;
		if (pool != null) {
			return pool.getQueuedSubmissionCount();
		}

		return 0;
	}

	public static boolean isQueueAboveThreshold(int threshold) {
		return getQueueSize() >= threshold;
	}

	public static void execute(Runnable task) {
		if (MoneyakShadersConfig.get().debugStats) {
			DebugStats.meshSubmitted.incrementAndGet();
		}
		getOrCreate().execute(task);
	}

	public static void executeBackground(Runnable task) {
		getOrCreateBackground().execute(task);
	}

	public static boolean executeWithBackpressure(ChunkPos chunkPos, Runnable task) {
		MoneyakShadersConfig config = MoneyakShadersConfig.get();
		if (config.chunkRebuildDropLowPriorityWhenQueueHigh
				&& isQueueAboveThreshold(config.chunkRebuildBackpressureThreshold)
				&& isFarFromPlayer(chunkPos, config.chunkPrecomputePriorityDistance)) {
			return false;
		}

		execute(task);
		return true;
	}

	public static boolean executeWithBackpressure(Runnable task) {
		MoneyakShadersConfig config = MoneyakShadersConfig.get();
		if (config.chunkRebuildDropLowPriorityWhenQueueHigh
				&& isQueueAboveThreshold(config.chunkRebuildBackpressureThreshold)) {
			return false;
		}

		execute(task);
		return true;
	}

	private static boolean isFarFromPlayer(ChunkPos chunkPos, int maxDistance) {
		if (chunkPos == null || maxDistance <= 0) {
			return false;
		}

		ChunkPos playerChunk = getPlayerChunkPos();
		if (playerChunk == null) {
			return false;
		}

		return Math.abs(playerChunk.x - chunkPos.x) > maxDistance
				|| Math.abs(playerChunk.z - chunkPos.z) > maxDistance;
	}

	private static ChunkPos getPlayerChunkPos() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || client.player == null) {
			return null;
		}
		int chunkX = client.player.getBlockX() >> 4;
		int chunkZ = client.player.getBlockZ() >> 4;
		return new ChunkPos(chunkX, chunkZ);
	}
}
