package lib.kasuga.rendering.models.mc.scene;

import net.minecraft.core.BlockPos;

import java.util.Collection;
import java.util.Optional;

/**
 * Tight non-air block extents in source-local coordinates.
 *
 * @param length block-grid extent on X
 * @param width block-grid extent on Z
 * @param height block-grid extent on Y
 */
public record BlockDimensions(int length, int width, int height) {
    public BlockDimensions {
        if (length <= 0 || width <= 0 || height <= 0)
            throw new IllegalArgumentException("Block dimensions must be positive");
    }

    /** Largest of the three block-grid extents. */
    public int longestAxis() {
        return Math.max(length, Math.max(width, height));
    }

    static Optional<BlockDimensions> fromBlocks(Collection<BlockPos> blocks) {
        if (blocks.isEmpty()) return Optional.empty();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos pos : blocks) {
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        return Optional.of(new BlockDimensions(
                Math.toIntExact((long) maxX - minX + 1),
                Math.toIntExact((long) maxZ - minZ + 1),
                Math.toIntExact((long) maxY - minY + 1)));
    }
}
