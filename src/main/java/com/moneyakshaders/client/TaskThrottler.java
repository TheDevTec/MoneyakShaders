package com.moneyakshaders.client;

import com.moneyakshaders.MoneyakShadersConfig;

/**
 * Centralized throttling decisions for background tasks (animated textures, water).
 */
public final class TaskThrottler {
    private static final RateLimiter animatedLimiter;
    private static final RateLimiter waterLimiter;

    static {
        MoneyakShadersConfig cfg = MoneyakShadersConfig.get();
        animatedLimiter = new RateLimiter(cfg.animatedTasksPerSecond);
        waterLimiter = new RateLimiter(cfg.waterTasksPerSecond);
    }

    private TaskThrottler() {}

    public static boolean allowAnimatedTask() {
        return animatedLimiter.tryAcquire();
    }

    public static boolean allowWaterTask() {
        return waterLimiter.tryAcquire();
    }
}
