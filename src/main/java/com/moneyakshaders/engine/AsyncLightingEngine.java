package com.moneyakshaders.engine;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.moneyakshaders.MoneyakShaders;
import com.moneyakshaders.MoneyakShadersConfig;

import net.minecraft.util.math.ChunkPos;

/**
 * Lightweight async lighting engine scaffold.
 * Handles queued lighting-related tasks off the main thread with backpressure.
 */
public final class AsyncLightingEngine {
    private static volatile AsyncLightingEngine instance;
    private final ThreadPoolExecutor highPriorityExecutor;
    private final ThreadPoolExecutor normalExecutor;
    private final ScheduledExecutorService refillScheduler;
    private final AtomicInteger submissionTokens = new AtomicInteger(0);
    private final int submissionsPerSecond;

    private AsyncLightingEngine() {
        MoneyakShadersConfig cfg = MoneyakShadersConfig.get();
        // Clamped to a quarter of the CPU. This pool runs alongside the mesh and background pools, and
        // an over-provisioned config (16 light threads on a 16-core machine, on top of everything else)
        // preempts the render thread — the frame dips while chunks load even though the work itself
        // finishes fine. More threads than cores never completes CPU-bound work sooner anyway.
        int cpus = Math.max(1, Runtime.getRuntime().availableProcessors());
        int ceiling = Math.max(1, cpus / 4);
        int threads = cfg.lightingEngineThreads > 0
            ? Math.min(ceiling, cfg.lightingEngineThreads)
            : ceiling;
        int queueLimit = cfg.lightingQueueLimit > 0 ? cfg.lightingQueueLimit : Integer.MAX_VALUE;
        this.submissionsPerSecond = cfg.lightingSubmissionsPerSecond > 0 ? cfg.lightingSubmissionsPerSecond : 0;

        AtomicInteger counter = new AtomicInteger(1);
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "MoneyakShaders-Lighting-" + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        };

        // Split threads between high-priority and normal pools (favor normal work)
        int highThreads = Math.max(1, threads / 4);
        int normalThreads = Math.max(1, threads - highThreads);

        if (queueLimit == Integer.MAX_VALUE) {
            highPriorityExecutor = new ThreadPoolExecutor(highThreads, highThreads, 60, TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(), r -> {
                Thread t = new Thread(r, "MoneyakShaders-Lighting-HP-" + counter.getAndIncrement());
                t.setDaemon(true);
                return t;
                }, new ThreadPoolExecutor.DiscardPolicy());
            normalExecutor = new ThreadPoolExecutor(normalThreads, normalThreads, 60, TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(), r -> {
                Thread t = new Thread(r, "MoneyakShaders-Lighting-N-" + counter.getAndIncrement());
                t.setDaemon(true);
                return t;
                }, new ThreadPoolExecutor.DiscardPolicy());
            highPriorityExecutor.allowCoreThreadTimeOut(true);
            normalExecutor.allowCoreThreadTimeOut(true);
            MoneyakShaders.LOGGER.info("[Optimized Loading] Lighting engine created with {} threads (HP {}) and unbounded queues",
                threads, highThreads);
        } else {
            // Reserve a smaller high-priority queue to favor responsiveness
            int highQueue = Math.max(2, queueLimit / 8);
            int normalQueue = Math.max(2, queueLimit - highQueue);
            highPriorityExecutor = new ThreadPoolExecutor(highThreads, highThreads, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(highQueue), r -> {
                Thread t = new Thread(r, "MoneyakShaders-Lighting-HP-" + counter.getAndIncrement());
                t.setDaemon(true);
                return t;
                }, new ThreadPoolExecutor.DiscardPolicy());
            normalExecutor = new ThreadPoolExecutor(normalThreads, normalThreads, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(normalQueue), r -> {
                Thread t = new Thread(r, "MoneyakShaders-Lighting-N-" + counter.getAndIncrement());
                t.setDaemon(true);
                return t;
                }, new ThreadPoolExecutor.DiscardPolicy());
            highPriorityExecutor.allowCoreThreadTimeOut(true);
            normalExecutor.allowCoreThreadTimeOut(true);
            MoneyakShaders.LOGGER.info("[Optimized Loading] Lighting engine created with {} threads (HP {}) and queue {}",
                threads, highThreads, queueLimit);
        }

        // Setup a simple per-second refill token bucket for submissions, if enabled
        if (this.submissionsPerSecond > 0) {
            submissionTokens.set(this.submissionsPerSecond);
            refillScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MoneyakShaders-Lighting-Refill");
            t.setDaemon(true);
            return t;
            });
            refillScheduler.scheduleAtFixedRate(() -> submissionTokens.set(this.submissionsPerSecond), 1, 1, TimeUnit.SECONDS);
            MoneyakShaders.LOGGER.info("[Optimized Loading] Lighting submissions rate limited to {}/s",
                this.submissionsPerSecond);
        } else {
            refillScheduler = null;
            submissionTokens.set(Integer.MAX_VALUE);
        }
    }

    public static AsyncLightingEngine getInstance() {
        AsyncLightingEngine r = instance;
        if (r == null) {
            synchronized (AsyncLightingEngine.class) {
                r = instance;
                if (r == null) r = instance = new AsyncLightingEngine();
            }
        }
        return r;
    }

    public void submitLightingTask(ChunkPos chunkPos, Runnable task) {
        MoneyakShadersConfig cfg = MoneyakShadersConfig.get();

        // Rate limiting check
        if (this.submissionsPerSecond > 0) {
            int left = submissionTokens.getAndDecrement();
            if (left <= 0) {
                return; // drop submission under rate limit
            }
        }

        // Backpressure: if both queues combined are large, drop distant tasks
        int combinedQueue = highPriorityExecutor.getQueue().size() + normalExecutor.getQueue().size();
        if (cfg.chunkRebuildDropLowPriorityWhenQueueHigh && combinedQueue >= cfg.chunkRebuildBackpressureThreshold) {
            if (chunkPos != null) {
                int maxDistance = cfg.chunkPrecomputePriorityDistance;
                if (maxDistance > 0) {
                    try {
                        net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
                        if (client != null && client.player != null) {
                            int px = client.player.getBlockX() >> 4;
                            int pz = client.player.getBlockZ() >> 4;
                            if (Math.abs(px - chunkPos.x) > maxDistance || Math.abs(pz - chunkPos.z) > maxDistance) {
                                return; // drop
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        // Priority decision: near-player tasks go to highPriorityExecutor
        boolean highPriority = false;
        if (chunkPos != null) {
            try {
                net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
                if (client != null && client.player != null) {
                    int px = client.player.getBlockX() >> 4;
                    int pz = client.player.getBlockZ() >> 4;
                    int dist = Math.max(Math.abs(px - chunkPos.x), Math.abs(pz - chunkPos.z));
                    if (dist <= cfg.chunkPrecomputePriorityDistance) highPriority = true;
                }
            } catch (Throwable ignored) {
            }
        }

        try {
            if (highPriority) highPriorityExecutor.execute(task);
            else normalExecutor.execute(task);
        } catch (Throwable t) {
            // swallow rejection
        }
    }

    public int getQueueSize() {
        return highPriorityExecutor.getQueue().size() + normalExecutor.getQueue().size();
    }
}
