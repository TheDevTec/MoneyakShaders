package com.moneyakshaders.render;

import java.util.concurrent.*;

/** Small isolated path for geometry changes around the player, independent of bulk streaming. */
final class BlockEditLane {
    static final int PRIORITY = -1;
    static final int QUEUED = 8;
    static final int READY = 4;
    static final long PREPARATION_NS = 600_000L;

    static ThreadPoolExecutor executor() {
        ThreadPoolExecutor result = new ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUED), task -> {
                    Thread thread = new Thread(task, "chunk-block-edit");
                    thread.setDaemon(true);
                    return thread;
                });
        result.allowCoreThreadTimeOut(true);
        return result;
    }
}
