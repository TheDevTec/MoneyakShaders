package com.moneyakshaders;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

/**
 * Integration bridge for chunk block optimization.
 * Coordinates block culling and texture aggregation with other async tasks.
 */
public class ChunkBlockOptimizationBridge {
    private static volatile ChunkBlockOptimizationBridge instance;

    private ChunkBlockOptimizationBridge() {}

    public static ChunkBlockOptimizationBridge getInstance() {
        if (instance == null) {
            synchronized (ChunkBlockOptimizationBridge.class) {
                if (instance == null) instance = new ChunkBlockOptimizationBridge();
            }
        }
        return instance;
    }

    /**
     * Queue block optimization for a chunk.
     * Should be called asynchronously to avoid blocking mesh building.
     */
    public void optimizeChunkAsync(World world, ChunkPos chunkPos) {
        MoneyakShadersConfig config = MoneyakShadersConfig.get();
        if (!config.enableBlockFaceCulling && !config.enableTextureAggregation) {
            return;
        }

        if (world == null) return;

        try {
            Chunk chunk = world.getChunk(chunkPos.x, chunkPos.z, net.minecraft.world.chunk.ChunkStatus.FULL, false);
            if (chunk != null) {
                BlockPos blockPos = chunkPos.getStartPos();
                ChunkBlockOptimizer.getInstance().optimizeChunk(world, blockPos);
            }
        } catch (Throwable e) {
            // Fail silently - not critical if optimization doesn't run
        }
    }

    /**
     * Check if block optimization is enabled.
     */
    public boolean isEnabled() {
        MoneyakShadersConfig config = MoneyakShadersConfig.get();
        return config.enableBlockFaceCulling || config.enableTextureAggregation;
    }

    /**
     * Clear all cached optimization data.
     */
    public void clearCache() {
        ChunkBlockOptimizer.getInstance().clearCache();
    }

    /**
     * Get optimization statistics.
     */
    public ChunkBlockOptimizer.OptimizationStats getStats() {
        ChunkBlockOptimizer.OptimizationStats stats = new ChunkBlockOptimizer.OptimizationStats();
        // Could be extended to accumulate real stats from the optimizer
        return stats;
    }
}
