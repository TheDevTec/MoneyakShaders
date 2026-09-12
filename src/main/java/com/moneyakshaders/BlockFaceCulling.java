package com.moneyakshaders;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;

/**
 * Block face culling optimization.
 * Determines if a block face should be rendered based on adjacent blocks.
 * Prevents rendering faces that are completely hidden by adjacent solid blocks.
 */
public class BlockFaceCulling {
    private static final boolean DEBUG = false;

    /**
     * Check if a block face should be culled (not rendered).
     * A face is culled if the adjacent block is opaque and solid.
     *
     * @param world The block view
     * @param blockPos Position of the block
     * @param direction Direction to check (0=down, 1=up, 2=north, 3=south, 4=west, 5=east)
     * @return true if the face should be culled (not rendered)
     */
    public static boolean shouldCullFace(BlockView world, BlockPos blockPos, int direction) {
        if (world == null) return false;

        BlockPos adjPos = getAdjacentPos(blockPos, direction);
        if (adjPos == null) return false;

        Block adjBlock = world.getBlockState(adjPos).getBlock();
        if (adjBlock == null) return false;

        // Don't cull air blocks - they're transparent
        if (adjBlock == Blocks.AIR) return false;

        // Don't cull water/lava - they have special rendering
        if (isLiquid(adjBlock)) return false;

        // Cull if adjacent block is opaque and solid
        return isOpaqueAndSolid(adjBlock);
    }

    /**
     * Check if a block face is adjacent to a similar/same block (for texture aggregation).
     * Used to merge similar adjacent blocks into one mesh group.
     *
     * @param world The block view
     * @param blockPos Position of the block
     * @param block The block to check
     * @param direction Direction to check
     * @return true if adjacent block is similar (same or very similar texture)
     */
    public static boolean isAdjacentToSimilarBlock(BlockView world, BlockPos blockPos, Block block, int direction) {
        if (world == null) return false;

        BlockPos adjPos = getAdjacentPos(blockPos, direction);
        if (adjPos == null) return false;

        Block adjBlock = world.getBlockState(adjPos).getBlock();
        if (adjBlock == null) return false;

        // Check if blocks are identical
        if (adjBlock == block) return true;

        // Check texture similarity (simplified: same material type)
        return isTextureCompatible(block, adjBlock);
    }

    /**
     * Get adjacent block position in a specific direction.
     * @param blockPos Base position
     * @param direction 0=down, 1=up, 2=north, 3=south, 4=west, 5=east
     * @return Adjacent position or null if invalid
     */
    private static BlockPos getAdjacentPos(BlockPos blockPos, int direction) {
        return switch (direction) {
            case 0 -> blockPos.down();   // DOWN
            case 1 -> blockPos.up();     // UP
            case 2 -> blockPos.north();  // NORTH
            case 3 -> blockPos.south();  // SOUTH
            case 4 -> blockPos.west();   // WEST
            case 5 -> blockPos.east();   // EAST
            default -> null;
        };
    }

    /**
     * Check if a block is opaque and solid (blocks light/rendering).
     */
    private static boolean isOpaqueAndSolid(Block block) {
        if (block == null || block == Blocks.AIR) return false;
        
        // Stone, dirt, grass, cobblestone, etc.
        String name = block.getName().getString().toLowerCase();
        
        // Rock/stone blocks
        if (name.contains("stone") || name.contains("granite") || name.contains("diorite") 
            || name.contains("andesite") || name.contains("dirt") || name.contains("grass")
            || name.contains("sand") || name.contains("gravel") || name.contains("clay")
            || name.contains("ore") || name.contains("concrete") || name.contains("terracotta")) {
            return true;
        }
        
        // Wood, logs, planks
        if (name.contains("wood") || name.contains("log") || name.contains("plank")
            || name.contains("stem")) {
            return true;
        }
        
        // Metal blocks
        if (name.contains("iron") || name.contains("gold") || name.contains("copper")
            || name.contains("diamond") || name.contains("emerald") || name.contains("netherite")
            || name.contains("metal")) {
            return true;
        }
        
        // Bricks and masonry
        if (name.contains("brick") || name.contains("slate") || name.contains("cobble")
            || name.contains("mossy") || name.contains("cracked")) {
            return true;
        }
        
        // Blocks that are definitely opaque
        return block.getDefaultState().isOpaque() && !isLiquid(block);
    }

    /**
     * Check if blocks have compatible textures (same material type).
     * Allows aggregation of similar blocks for mesh grouping.
     */
    private static boolean isTextureCompatible(Block block1, Block block2) {
        if (block1 == null || block2 == null) return false;
        if (block1 == block2) return true;

        String name1 = block1.getName().getString().toLowerCase();
        String name2 = block2.getName().getString().toLowerCase();

        // Same rock type (e.g., different stone variants)
        if ((name1.contains("stone") && name2.contains("stone")) ||
            (name1.contains("granite") && name2.contains("granite")) ||
            (name1.contains("diorite") && name2.contains("diorite")) ||
            (name1.contains("andesite") && name2.contains("andesite"))) {
            return true;
        }

        // Same wood type
        if ((name1.contains("oak") && name2.contains("oak")) ||
            (name1.contains("birch") && name2.contains("birch")) ||
            (name1.contains("spruce") && name2.contains("spruce")) ||
            (name1.contains("jungle") && name2.contains("jungle")) ||
            (name1.contains("acacia") && name2.contains("acacia")) ||
            (name1.contains("dark_oak") && name2.contains("dark_oak")) ||
            (name1.contains("mangrove") && name2.contains("mangrove")) ||
            (name1.contains("cherry") && name2.contains("cherry")) ||
            (name1.contains("bamboo") && name2.contains("bamboo"))) {
            return true;
        }

        // Same ore type
        if ((name1.contains("iron_ore") && name2.contains("iron_ore")) ||
            (name1.contains("gold_ore") && name2.contains("gold_ore")) ||
            (name1.contains("coal_ore") && name2.contains("coal_ore")) ||
            (name1.contains("diamond_ore") && name2.contains("diamond_ore")) ||
            (name1.contains("emerald_ore") && name2.contains("emerald_ore"))) {
            return true;
        }

        // Same soil type
        if ((name1.contains("dirt") && name2.contains("dirt")) ||
            (name1.contains("grass") && name2.contains("grass")) ||
            (name1.contains("sand") && name2.contains("sand")) ||
            (name1.contains("gravel") && name2.contains("gravel"))) {
            return true;
        }

        return false;
    }

    /**
     * Check if block is liquid (water/lava).
     */
    private static boolean isLiquid(Block block) {
        String name = block.getName().getString().toLowerCase();
        return name.contains("water") || name.contains("lava");
    }

    /**
     * Get material type category for a block.
     * Used for texture aggregation grouping.
     */
    public static String getMaterialCategory(Block block) {
        if (block == null) return "other";
        
        String name = block.getName().getString().toLowerCase();

        if (name.contains("stone")) return "stone";
        if (name.contains("granite")) return "granite";
        if (name.contains("diorite")) return "diorite";
        if (name.contains("andesite")) return "andesite";
        if (name.contains("dirt")) return "dirt";
        if (name.contains("grass")) return "grass";
        if (name.contains("sand")) return "sand";
        if (name.contains("gravel")) return "gravel";
        if (name.contains("wood") || name.contains("log") || name.contains("plank")) return "wood";
        if (name.contains("ore")) return "ore";
        if (name.contains("brick")) return "brick";
        if (name.contains("concrete")) return "concrete";
        if (name.contains("terracotta")) return "terracotta";
        if (name.contains("water")) return "water";
        if (name.contains("lava")) return "lava";
        
        return "other";
    }
}
