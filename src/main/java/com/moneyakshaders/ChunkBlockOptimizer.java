package com.moneyakshaders;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chunk block optimization coordinator.
 * Orchestrates face culling, texture aggregation, and other block-level optimizations.
 * Runs in background to precompute optimizations before rendering.
 */
public class ChunkBlockOptimizer {
    private static final Logger LOGGER = LoggerFactory.getLogger("moneyakshaders");
    private static volatile ChunkBlockOptimizer instance;

    private final Map<Long, ChunkData> optimizedChunks = new ConcurrentHashMap<>();
    private final MoneyakShadersConfig config = MoneyakShadersConfig.get();

    private ChunkBlockOptimizer() {}

    public static ChunkBlockOptimizer getInstance() {
        if (instance == null) {
            synchronized (ChunkBlockOptimizer.class) {
                if (instance == null) instance = new ChunkBlockOptimizer();
            }
        }
        return instance;
    }

    /**
     * Optimize a chunk's blocks for rendering.
     * Performs face culling and texture aggregation.
     * Should be called asynchronously before rendering the chunk.
     */
    public void optimizeChunk(BlockView world, BlockPos chunkPos) {
        if (world == null || chunkPos == null) return;

        long chunkKey = getChunkKey(chunkPos);
        if (optimizedChunks.containsKey(chunkKey)) {
            return; // Already optimized
        }

        try {
            ChunkData data = new ChunkData();

            // 1. Perform face culling analysis
            analyzeFaceCulling(world, chunkPos, data);

            // 2. Aggregate similar blocks
            aggregateTextures(world, chunkPos, data);

            // Cache the optimized data
            optimizedChunks.put(chunkKey, data);

            if (config.debugBlockOptimization) {
                LOGGER.debug("Chunk optimized at {}: {} cullable faces, {} material groups",
                        chunkPos, data.cullableFaces, data.materialGroups.size());
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to optimize chunk at {}", chunkPos, e);
        }
    }

    /**
     * Get cached optimization data for a chunk.
     */
    public ChunkData getOptimization(BlockPos chunkPos) {
        return optimizedChunks.get(getChunkKey(chunkPos));
    }

    /**
     * Clear optimization cache (e.g., when world is unloaded).
     */
    public void clearCache() {
        optimizedChunks.clear();
    }

    /**
     * Analyze which block faces can be culled in the chunk.
     */
    private void analyzeFaceCulling(BlockView world, BlockPos chunkPos, ChunkData data) {
        int cullableCount = 0;

        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    BlockPos blockPos = chunkPos.add(x, y, z);

                    // Check all 6 faces
                    for (int face = 0; face < 6; face++) {
                        if (BlockFaceCulling.shouldCullFace(world, blockPos, face)) {
                            cullableCount++;
                            data.recordCullableFace(x, y, z, face);
                        }
                    }
                }
            }
        }

        data.cullableFaces = cullableCount;
    }

    /**
     * Aggregate similar adjacent blocks by texture/material.
     */
    private void aggregateTextures(BlockView world, BlockPos chunkPos, ChunkData data) {
        TextureAggregator aggregator = new TextureAggregator(world, data);
        Map<String, Set<BlockPos>> groups = aggregator.aggregateBlocks(chunkPos);
        data.materialGroups = groups;
    }

    /**
     * Convert chunk coordinates to a unique key.
     */
    private long getChunkKey(BlockPos pos) {
        // Convert block pos to chunk pos and create a unique key
        long chunkX = pos.getX() >> 4;
        long chunkZ = pos.getZ() >> 4;
        return (chunkX & 0xFFFFFFFFL) | ((chunkZ & 0xFFFFFFFFL) << 32);
    }

    /**
     * Container for optimized chunk data.
     */
    public static class ChunkData {
        // Face culling data: 4096 blocks * 6 faces = 24576 bits max
        // Using byte array: 3 bytes per block (6 faces -> use nibbles)
        public byte[] culledFaces = new byte[2048]; // 4096 * 6 bits / 8

        // Number of cullable faces in this chunk
        public int cullableFaces = 0;

        // Material groups: material category -> set of block positions
        public Map<String, Set<BlockPos>> materialGroups = new ConcurrentHashMap<>();

        /**
         * Record a face that can be culled.
         * Stores which faces of which blocks don't need to be rendered.
         */
        public void recordCullableFace(int x, int y, int z, int face) {
            int blockIndex = (y << 8) | (z << 4) | x;
            int byteIndex = blockIndex >> 1; // 2 blocks per byte (4 bits each)
            int nibbleOffset = (blockIndex & 1) << 2; // 0 or 4
            
            byte faceMask = (byte) (1 << face);
            culledFaces[byteIndex] |= (faceMask << nibbleOffset);
        }

        /**
         * Check if a specific face should be culled.
         */
        public boolean isFaceCulled(int x, int y, int z, int face) {
            int blockIndex = (y << 8) | (z << 4) | x;
            int byteIndex = blockIndex >> 1;
            int nibbleOffset = (blockIndex & 1) << 2;
            
            byte faceMask = (byte) (1 << face);
            return (culledFaces[byteIndex] & (faceMask << nibbleOffset)) != 0;
        }

        /**
         * Get material groups summary.
         */
        public String getMaterialSummary() {
            StringBuilder sb = new StringBuilder();
            materialGroups.forEach((material, blocks) -> {
                sb.append(material).append(":").append(blocks.size()).append(" ");
            });
            return sb.toString();
        }
    }

    /**
     * Statistics tracker for optimization effectiveness.
     */
    public static class OptimizationStats {
        public int chunksOptimized = 0;
        public long totalCullableFaces = 0;
        public int totalMaterialGroups = 0;
        public long totalTextureAggregations = 0;

        public void accumulate(ChunkData data) {
            chunksOptimized++;
            totalCullableFaces += data.cullableFaces;
            totalMaterialGroups += data.materialGroups.size();
            data.materialGroups.values().forEach(set -> totalTextureAggregations += set.size());
        }

        public void logStats(Logger logger) {
            long culledPercentage = chunksOptimized > 0 ? 
                    (totalCullableFaces * 100) / (chunksOptimized * 24576L) : 0;
            
            logger.info("[Block Optimization] Optimized: {} chunks, Cullable faces: {} ({}%), " +
                    "Material groups: {}, Texture aggregations: {}",
                    chunksOptimized, totalCullableFaces, culledPercentage,
                    totalMaterialGroups, totalTextureAggregations);
        }
    }
}
