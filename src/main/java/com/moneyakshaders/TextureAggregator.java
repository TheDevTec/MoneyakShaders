package com.moneyakshaders;

import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;

import java.util.*;

/**
 * Texture aggregator for grouping similar adjacent blocks.
 * Allows efficient batch rendering of blocks with similar textures/materials.
 * Reduces draw calls by grouping compatible blocks together.
 */
public class TextureAggregator {
    private final Map<String, Set<BlockPos>> materialGroups = new HashMap<>();
    private final BlockView world;
    private final ChunkBlockOptimizer.ChunkData chunkData;

    public TextureAggregator(BlockView world, ChunkBlockOptimizer.ChunkData chunkData) {
        this.world = world;
        this.chunkData = chunkData;
    }

    /**
     * Scan chunk and group blocks by material/texture type.
     * Returns mapping of material category -> set of block positions.
     */
    public Map<String, Set<BlockPos>> aggregateBlocks(BlockPos chunkPos) {
        materialGroups.clear();

        // Scan all blocks in the chunk
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    BlockPos blockPos = chunkPos.add(x, y, z);
                    if (world == null) continue;

                    Block block = world.getBlockState(blockPos).getBlock();
                    if (block == null) continue;

                    // Skip air and leaves
                    if (isSkippable(block)) continue;

                    // Categorize and group
                    String material = BlockFaceCulling.getMaterialCategory(block);
                    materialGroups.computeIfAbsent(material, k -> new HashSet<>()).add(blockPos);
                }
            }
        }

        return materialGroups;
    }

    /**
     * Find adjacent similar blocks (for mesh merging).
     * Returns set of block positions adjacent to the given block that have compatible texture.
     */
    public Set<BlockPos> findAdjacentSimilarBlocks(BlockPos blockPos, Block block) {
        Set<BlockPos> adjacent = new HashSet<>();

        // Check all 6 directions
        for (int dir = 0; dir < 6; dir++) {
            if (BlockFaceCulling.isAdjacentToSimilarBlock(world, blockPos, block, dir)) {
                BlockPos adjPos = getAdjacentPos(blockPos, dir);
                if (adjPos != null) {
                    adjacent.add(adjPos);
                }
            }
        }

        return adjacent;
    }

    /**
     * Check if a contiguous region of similar blocks can be merged.
     * Returns true if the region is large enough to benefit from merging.
     */
    public boolean isWorthMerging(Set<BlockPos> region) {
        // Merge if region has at least 4 similar blocks (2x2 face)
        return region.size() >= 4;
    }

    /**
     * Compute a hash for a material group to allow caching.
     */
    public String getGroupHash(String material, Set<BlockPos> positions) {
        // Simple hash based on material and positions count
        return material + "_" + positions.size() + "_" + positions.hashCode();
    }

    /**
     * Get adjacent block position in a specific direction.
     */
    private static BlockPos getAdjacentPos(BlockPos blockPos, int direction) {
        return switch (direction) {
            case 0 -> blockPos.down();
            case 1 -> blockPos.up();
            case 2 -> blockPos.north();
            case 3 -> blockPos.south();
            case 4 -> blockPos.west();
            case 5 -> blockPos.east();
            default -> null;
        };
    }

    /**
     * Check if block should be skipped during aggregation.
     */
    private static boolean isSkippable(Block block) {
        String name = block.getName().getString().toLowerCase();
        return name.contains("air") || 
               name.contains("leaves") || 
               name.contains("water") ||
               name.contains("lava") ||
               name.contains("glass") ||
               name.contains("tall_grass") ||
               name.contains("flower") ||
               name.contains("vine");
    }

    /**
     * Get statistics about material distribution in chunk.
     */
    public String getStats() {
        StringBuilder sb = new StringBuilder();
        sb.append("Material Distribution: ");
        
        materialGroups.forEach((material, blocks) -> {
            sb.append(material).append("=").append(blocks.size()).append(" ");
        });
        
        return sb.toString();
    }

    /**
     * Container for chunk block optimization data.
     */
    public static class AggregationResult {
        public final Map<String, Set<BlockPos>> materialGroups;
        public final int totalProcessed;
        public final int totalSkipped;

        public AggregationResult(Map<String, Set<BlockPos>> materialGroups, int totalProcessed, int totalSkipped) {
            this.materialGroups = materialGroups;
            this.totalProcessed = totalProcessed;
            this.totalSkipped = totalSkipped;
        }

        public void log(org.slf4j.Logger logger) {
            logger.debug("Chunk aggregation - Processed: {}, Skipped: {}, Material groups: {}", 
                    totalProcessed, totalSkipped, materialGroups.size());
        }
    }
}
