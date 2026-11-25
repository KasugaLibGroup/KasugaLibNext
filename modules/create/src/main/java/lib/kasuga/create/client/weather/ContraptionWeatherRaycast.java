package lib.kasuga.create.client.weather;

import lib.kasuga.scene.ScenePose;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.OptionalDouble;

/**
 * Pure local-space 3D DDA voxel raycast used to find the highest precipitation-blocking
 * roof point along a vertical world column through rotated/scaled contraptions or sublevels.
 */
public final class ContraptionWeatherRaycast {
    private static final int MAX_DDA_STEPS = 256;
    private static final double EPSILON = 1e-9;

    private ContraptionWeatherRaycast() {}

    public static OptionalDouble raycastVertical(
            ScenePose pose,
            int minBX,
            int minBY,
            int minBZ,
            int maxBX,
            int maxBY,
            int maxBZ,
            BlockGetter blocks,
            double worldX,
            double minY,
            double maxY,
            double worldZ
    ) {
        if (maxY <= minY || minBX > maxBX || minBY > maxBY || minBZ > maxBZ) {
            return OptionalDouble.empty();
        }
        Vec3 localTop = pose.toLocal(new Vec3(worldX, maxY, worldZ));
        Vec3 localBottom = pose.toLocal(new Vec3(worldX, minY, worldZ));
        Vec3 localHit = raycastLocalSegment(
                localTop, localBottom,
                minBX, minBY, minBZ,
                maxBX, maxBY, maxBZ,
                blocks
        );
        if (localHit == null) {
            return OptionalDouble.empty();
        }
        double hitWorldY = pose.toWorld(localHit).y;
        if (hitWorldY >= minY - 1e-4 && hitWorldY <= maxY + 1e-4) {
            return OptionalDouble.of(Math.clamp(hitWorldY, minY, maxY));
        }
        return OptionalDouble.empty();
    }

    @Nullable
    public static Vec3 raycastLocalSegment(
            Vec3 start,
            Vec3 end,
            int minBX,
            int minBY,
            int minBZ,
            int maxBX,
            int maxBY,
            int maxBZ,
            BlockGetter blocks
    ) {
        double dx = end.x - start.x;
        double dy = end.y - start.y;
        double dz = end.z - start.z;

        double[] tRange = clipSegmentToBounds(
                start.x, start.y, start.z,
                dx, dy, dz,
                minBX, minBY, minBZ,
                maxBX + 1.0, maxBY + 1.0, maxBZ + 1.0
        );
        if (tRange == null) {
            return null;
        }

        double tStart = tRange[0];
        double tEnd = tRange[1];
        double nudge = Math.min(1e-6, (tEnd - tStart) * 0.25);
        double tProbe = tStart + nudge;

        double px = start.x + dx * tProbe;
        double py = start.y + dy * tProbe;
        double pz = start.z + dz * tProbe;

        int vx = Math.clamp(Mth.floor(px), minBX, maxBX);
        int vy = Math.clamp(Mth.floor(py), minBY, maxBY);
        int vz = Math.clamp(Mth.floor(pz), minBZ, maxBZ);

        int stepX = dx > EPSILON ? 1 : (dx < -EPSILON ? -1 : 0);
        int stepY = dy > EPSILON ? 1 : (dy < -EPSILON ? -1 : 0);
        int stepZ = dz > EPSILON ? 1 : (dz < -EPSILON ? -1 : 0);

        double tDeltaX = stepX != 0 ? Math.abs(1.0 / dx) : Double.POSITIVE_INFINITY;
        double tDeltaY = stepY != 0 ? Math.abs(1.0 / dy) : Double.POSITIVE_INFINITY;
        double tDeltaZ = stepZ != 0 ? Math.abs(1.0 / dz) : Double.POSITIVE_INFINITY;

        double tMaxX = nextBoundaryT(px, dx, vx, stepX, tProbe);
        double tMaxY = nextBoundaryT(py, dy, vy, stepY, tProbe);
        double tMaxZ = nextBoundaryT(pz, dz, vz, stepZ, tProbe);

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int step = 0; step < MAX_DDA_STEPS; step++) {
            if (vx >= minBX && vx <= maxBX && vy >= minBY && vy <= maxBY && vz >= minBZ && vz <= maxBZ) {
                cursor.set(vx, vy, vz);
                BlockState state = blocks.getBlockState(cursor);
                if (blocksPrecipitation(state, blocks, cursor)) {
                    VoxelShape shape = resolveShape(state, blocks, cursor);
                    BlockHitResult hit = shape.clip(start, end, cursor);
                    if (hit != null) {
                        return hit.getLocation();
                    }
                }
            }

            double nextT;
            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                nextT = tMaxX;
                vx += stepX;
                tMaxX += tDeltaX;
            } else if (tMaxY <= tMaxZ) {
                nextT = tMaxY;
                vy += stepY;
                tMaxY += tDeltaY;
            } else {
                nextT = tMaxZ;
                vz += stepZ;
                tMaxZ += tDeltaZ;
            }

            if (nextT > tEnd + 1e-6) {
                break;
            }
        }
        return null;
    }

    private static boolean blocksPrecipitation(BlockState state, BlockGetter blocks, BlockPos pos) {
        if (state.isAir()) {
            return false;
        }
        if (state.blocksMotion() || !state.getFluidState().isEmpty()) {
            return true;
        }
        return !state.getCollisionShape(blocks, pos).isEmpty();
    }

    private static VoxelShape resolveShape(BlockState state, BlockGetter blocks, BlockPos pos) {
        VoxelShape collision = state.getCollisionShape(blocks, pos);
        if (!collision.isEmpty()) {
            return collision;
        }
        VoxelShape visual = state.getShape(blocks, pos);
        return visual.isEmpty() ? Shapes.block() : visual;
    }

    private static double nextBoundaryT(double p, double d, int voxel, int step, double currentT) {
        if (step > 0) {
            return currentT + ((voxel + 1.0) - p) / d;
        }
        if (step < 0) {
            return currentT + (voxel - p) / d;
        }
        return Double.POSITIVE_INFINITY;
    }

    @Nullable
    private static double[] clipSegmentToBounds(
            double ox, double oy, double oz,
            double dx, double dy, double dz,
            double minX, double minY, double minZ,
            double maxX, double maxY, double maxZ
    ) {
        double tMin = 0.0;
        double tMax = 1.0;

        double[] o = {ox, oy, oz};
        double[] d = {dx, dy, dz};
        double[] lo = {minX, minY, minZ};
        double[] hi = {maxX, maxY, maxZ};

        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(d[axis]) < EPSILON) {
                if (o[axis] < lo[axis] || o[axis] > hi[axis]) {
                    return null;
                }
                continue;
            }
            double inv = 1.0 / d[axis];
            double t1 = (lo[axis] - o[axis]) * inv;
            double t2 = (hi[axis] - o[axis]) * inv;
            double axisMin = Math.min(t1, t2);
            double axisMax = Math.max(t1, t2);
            if (axisMin > tMin) tMin = axisMin;
            if (axisMax < tMax) tMax = axisMax;
            if (tMin > tMax) {
                return null;
            }
        }
        return new double[]{tMin, tMax};
    }
}
