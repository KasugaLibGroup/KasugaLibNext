package lib.kasuga.rendering.effect.particle.fluid.minecraft;

import java.util.BitSet;

/** Cell-center-aligned normalized box rasterization shared by Minecraft collision adapters. */
final class FluidCellRasterizer {
    private FluidCellRasterizer() {
    }

    static void markBox(
            BitSet destination,
            int resolution,
            double minimumX, double minimumY, double minimumZ,
            double maximumX, double maximumY, double maximumZ
    ) {
        if (maximumX <= 0 || maximumY <= 0 || maximumZ <= 0
                || minimumX >= 1 || minimumY >= 1 || minimumZ >= 1) {
            return;
        }
        int minimumCellX = minimumCell(minimumX, maximumX, resolution);
        int minimumCellY = minimumCell(minimumY, maximumY, resolution);
        int minimumCellZ = minimumCell(minimumZ, maximumZ, resolution);
        int maximumCellX = maximumCell(minimumX, maximumX, resolution);
        int maximumCellY = maximumCell(minimumY, maximumY, resolution);
        int maximumCellZ = maximumCell(minimumZ, maximumZ, resolution);
        int resolutionSquared = resolution * resolution;
        for (int z = minimumCellZ; z <= maximumCellZ; z++) {
            for (int y = minimumCellY; y <= maximumCellY; y++) {
                int row = y * resolution + z * resolutionSquared;
                destination.set(row + minimumCellX, row + maximumCellX + 1);
            }
        }
    }

    private static int minimumCell(double minimum, double maximum, int resolution) {
        int firstCenter = (int) Math.ceil(minimum * resolution - 0.5);
        int lastCenter = (int) Math.ceil(maximum * resolution - 0.5) - 1;
        if (firstCenter > lastCenter) return nearestCell(minimum, maximum, resolution);
        return clamp(firstCenter, resolution);
    }

    private static int maximumCell(double minimum, double maximum, int resolution) {
        int firstCenter = (int) Math.ceil(minimum * resolution - 0.5);
        int lastCenter = (int) Math.ceil(maximum * resolution - 0.5) - 1;
        if (firstCenter > lastCenter) return nearestCell(minimum, maximum, resolution);
        return clamp(lastCenter, resolution);
    }

    private static int nearestCell(double minimum, double maximum, int resolution) {
        return clamp((int) Math.floor((minimum + maximum) * 0.5 * resolution), resolution);
    }

    private static int clamp(int cell, int resolution) {
        return Math.max(0, Math.min(resolution - 1, cell));
    }
}
