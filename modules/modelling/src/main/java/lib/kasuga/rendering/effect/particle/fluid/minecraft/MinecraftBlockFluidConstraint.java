package lib.kasuga.rendering.effect.particle.fluid.minecraft;

import lib.kasuga.rendering.effect.particle.fluid.FluidConstraint3D;
import lib.kasuga.rendering.effect.particle.fluid.FluidConstraintContext3D;
import lib.kasuga.rendering.effect.particle.fluid.FluidTracerCollision3D;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.BitSet;
import java.util.Objects;

/**
 * Client-world adapter that rasterizes Minecraft block collision shapes into a fluid solid mask.
 *
 * <p>World scanning is cached independently from solver iterations. The constraint contains no
 * rendering API state and can be removed when running the same simulation outside Minecraft.</p>
 */
public final class MinecraftBlockFluidConstraint
        implements FluidConstraint3D, FluidTracerCollision3D {
    private static final int AXIS_X = 0;
    private static final int AXIS_Y = 1;
    private static final int AXIS_Z = 2;
    private static final double COLLISION_EPSILON = 1.0e-7;

    private final Vector3f center = new Vector3f();
    private final Vector3f halfExtents;
    private final int refreshIntervalTicks;
    private final BlockPos.MutableBlockPos mutableBlockPosition = new BlockPos.MutableBlockPos();
    private final Shapes.DoubleLineConsumer collisionConsumer = this::markCollisionBox;
    private final Shapes.DoubleLineConsumer tracerCollisionConsumer = this::resolveTracerCollisionBox;
    private final BitSet cachedSolids = new BitSet();

    @Nullable
    private ClientLevel level;
    @Nullable
    private ClientLevel cachedLevel;
    private boolean dirty = true;
    private int cachedResolution;
    private long lastRefreshTick = Long.MIN_VALUE;
    private int currentBlockX;
    private int currentBlockY;
    private int currentBlockZ;
    private double volumeMinimumX;
    private double volumeMinimumY;
    private double volumeMinimumZ;
    private double volumeSizeX;
    private double volumeSizeY;
    private double volumeSizeZ;
    private int scannedBlockCount;
    private int tracerAxis;
    private double tracerMovement;
    private double tracerMinimumX;
    private double tracerMinimumY;
    private double tracerMinimumZ;
    private double tracerMaximumX;
    private double tracerMaximumY;
    private double tracerMaximumZ;
    private int tracerScanMinimumX;
    private int tracerScanMinimumY;
    private int tracerScanMinimumZ;
    private int tracerScanMaximumX;
    private int tracerScanMaximumY;
    private int tracerScanMaximumZ;

    public MinecraftBlockFluidConstraint(Vector3f halfExtents, int refreshIntervalTicks) {
        this.halfExtents = new Vector3f(Objects.requireNonNull(halfExtents, "halfExtents"));
        if (this.halfExtents.x <= 0 || this.halfExtents.y <= 0 || this.halfExtents.z <= 0) {
            throw new IllegalArgumentException("halfExtents must be positive");
        }
        if (refreshIntervalTicks <= 0) {
            throw new IllegalArgumentException("refreshIntervalTicks must be positive");
        }
        this.refreshIntervalTicks = refreshIntervalTicks;
    }

    public synchronized void level(@Nullable ClientLevel value) {
        if (level == value) return;
        level = value;
        dirty = true;
    }

    public synchronized void center(Vector3f value) {
        Objects.requireNonNull(value, "value");
        if (center.equals(value)) return;
        center.set(value);
        dirty = true;
    }

    public synchronized void invalidate() {
        dirty = true;
    }

    public synchronized int cachedSolidCellCount() {
        return cachedSolids.cardinality();
    }

    public synchronized int scannedBlockCount() {
        return scannedBlockCount;
    }

    @Override
    public synchronized void apply(FluidConstraintContext3D context) {
        ClientLevel currentLevel = level;
        if (currentLevel == null) return;
        long gameTime = currentLevel.getGameTime();
        if (dirty
                || cachedLevel != currentLevel
                || cachedResolution != context.resolution()
                || gameTime < lastRefreshTick
                || gameTime - lastRefreshTick >= refreshIntervalTicks) {
            rebuild(currentLevel, context.resolution(), gameTime);
        }

        int resolutionSquared = cachedResolution * cachedResolution;
        for (int cell = cachedSolids.nextSetBit(0); cell >= 0;
             cell = cachedSolids.nextSetBit(cell + 1)) {
            int z = cell / resolutionSquared;
            int remainder = cell - z * resolutionSquared;
            int y = remainder / cachedResolution;
            int x = remainder - y * cachedResolution;
            context.solid(x, y, z, true);
        }
    }

    /**
     * Resolves a swept tracer cube directly against block collision shapes in loaded chunks.
     * This query is independent of the cached fluid-grid volume and therefore follows tracers
     * through the world for as long as they remain active.
     */
    @Override
    public synchronized void resolve(
            Vector3f previousPosition,
            Vector3f position,
            Vector3f velocity,
            float radius
    ) {
        Objects.requireNonNull(previousPosition, "previousPosition");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(velocity, "velocity");
        if (!finite(previousPosition) || !finite(position) || !finite(velocity)) {
            throw new IllegalArgumentException("Tracer position and velocity must be finite");
        }
        if (!Float.isFinite(radius) || radius <= 0) {
            throw new IllegalArgumentException("Tracer radius must be finite and positive");
        }
        ClientLevel currentLevel = level;
        if (currentLevel == null) return;

        double movementX = position.x - previousPosition.x;
        double movementY = position.y - previousPosition.y;
        double movementZ = position.z - previousPosition.z;
        if (Math.abs(movementX) < COLLISION_EPSILON
                && Math.abs(movementY) < COLLISION_EPSILON
                && Math.abs(movementZ) < COLLISION_EPSILON) {
            return;
        }

        tracerMinimumX = previousPosition.x - radius;
        tracerMinimumY = previousPosition.y - radius;
        tracerMinimumZ = previousPosition.z - radius;
        tracerMaximumX = previousPosition.x + radius;
        tracerMaximumY = previousPosition.y + radius;
        tracerMaximumZ = previousPosition.z + radius;
        tracerScanMinimumX = Mth.floor(Math.min(tracerMinimumX, tracerMinimumX + movementX));
        tracerScanMinimumY = Mth.floor(Math.min(tracerMinimumY, tracerMinimumY + movementY));
        tracerScanMinimumZ = Mth.floor(Math.min(tracerMinimumZ, tracerMinimumZ + movementZ));
        tracerScanMaximumX = Mth.floor(Math.nextDown(
                Math.max(tracerMaximumX, tracerMaximumX + movementX)
        ));
        tracerScanMaximumY = Mth.floor(Math.nextDown(
                Math.max(tracerMaximumY, tracerMaximumY + movementY)
        ));
        tracerScanMaximumZ = Mth.floor(Math.nextDown(
                Math.max(tracerMaximumZ, tracerMaximumZ + movementZ)
        ));

        double resolvedY = resolveAxis(currentLevel, AXIS_Y, movementY);
        moveTracerBounds(AXIS_Y, resolvedY);
        double resolvedX;
        double resolvedZ;
        if (Math.abs(movementX) >= Math.abs(movementZ)) {
            resolvedX = resolveAxis(currentLevel, AXIS_X, movementX);
            moveTracerBounds(AXIS_X, resolvedX);
            resolvedZ = resolveAxis(currentLevel, AXIS_Z, movementZ);
        } else {
            resolvedZ = resolveAxis(currentLevel, AXIS_Z, movementZ);
            moveTracerBounds(AXIS_Z, resolvedZ);
            resolvedX = resolveAxis(currentLevel, AXIS_X, movementX);
        }

        position.set(
                (float) (previousPosition.x + resolvedX),
                (float) (previousPosition.y + resolvedY),
                (float) (previousPosition.z + resolvedZ)
        );
        if (Math.abs(resolvedX - movementX) > COLLISION_EPSILON) velocity.x = 0;
        if (Math.abs(resolvedY - movementY) > COLLISION_EPSILON) velocity.y = 0;
        if (Math.abs(resolvedZ - movementZ) > COLLISION_EPSILON) velocity.z = 0;
    }

    private double resolveAxis(ClientLevel currentLevel, int axis, double movement) {
        if (Math.abs(movement) < COLLISION_EPSILON) return movement;
        tracerAxis = axis;
        tracerMovement = movement;
        for (int z = tracerScanMinimumZ; z <= tracerScanMaximumZ; z++) {
            for (int y = tracerScanMinimumY; y <= tracerScanMaximumY; y++) {
                for (int x = tracerScanMinimumX; x <= tracerScanMaximumX; x++) {
                    mutableBlockPosition.set(x, y, z);
                    if (!currentLevel.hasChunkAt(mutableBlockPosition)) continue;
                    VoxelShape collision = currentLevel.getBlockState(mutableBlockPosition)
                            .getCollisionShape(currentLevel, mutableBlockPosition);
                    if (collision.isEmpty()) continue;
                    currentBlockX = x;
                    currentBlockY = y;
                    currentBlockZ = z;
                    collision.forAllBoxes(tracerCollisionConsumer);
                }
            }
        }
        return tracerMovement;
    }

    private void resolveTracerCollisionBox(
            double minimumX, double minimumY, double minimumZ,
            double maximumX, double maximumY, double maximumZ
    ) {
        double obstacleMinimumX = currentBlockX + minimumX;
        double obstacleMinimumY = currentBlockY + minimumY;
        double obstacleMinimumZ = currentBlockZ + minimumZ;
        double obstacleMaximumX = currentBlockX + maximumX;
        double obstacleMaximumY = currentBlockY + maximumY;
        double obstacleMaximumZ = currentBlockZ + maximumZ;
        switch (tracerAxis) {
            case AXIS_X -> {
                if (!overlaps(tracerMinimumY, tracerMaximumY, obstacleMinimumY, obstacleMaximumY)
                        || !overlaps(tracerMinimumZ, tracerMaximumZ, obstacleMinimumZ, obstacleMaximumZ)) {
                    return;
                }
                tracerMovement = clipMovement(
                        tracerMinimumX, tracerMaximumX,
                        obstacleMinimumX, obstacleMaximumX,
                        tracerMovement
                );
            }
            case AXIS_Y -> {
                if (!overlaps(tracerMinimumX, tracerMaximumX, obstacleMinimumX, obstacleMaximumX)
                        || !overlaps(tracerMinimumZ, tracerMaximumZ, obstacleMinimumZ, obstacleMaximumZ)) {
                    return;
                }
                tracerMovement = clipMovement(
                        tracerMinimumY, tracerMaximumY,
                        obstacleMinimumY, obstacleMaximumY,
                        tracerMovement
                );
            }
            case AXIS_Z -> {
                if (!overlaps(tracerMinimumX, tracerMaximumX, obstacleMinimumX, obstacleMaximumX)
                        || !overlaps(tracerMinimumY, tracerMaximumY, obstacleMinimumY, obstacleMaximumY)) {
                    return;
                }
                tracerMovement = clipMovement(
                        tracerMinimumZ, tracerMaximumZ,
                        obstacleMinimumZ, obstacleMaximumZ,
                        tracerMovement
                );
            }
            default -> throw new IllegalStateException("Unknown collision axis " + tracerAxis);
        }
    }

    private void moveTracerBounds(int axis, double movement) {
        switch (axis) {
            case AXIS_X -> {
                tracerMinimumX += movement;
                tracerMaximumX += movement;
            }
            case AXIS_Y -> {
                tracerMinimumY += movement;
                tracerMaximumY += movement;
            }
            case AXIS_Z -> {
                tracerMinimumZ += movement;
                tracerMaximumZ += movement;
            }
            default -> throw new IllegalStateException("Unknown collision axis " + axis);
        }
    }

    static double clipMovement(
            double movingMinimum,
            double movingMaximum,
            double obstacleMinimum,
            double obstacleMaximum,
            double movement
    ) {
        if (movement > 0 && movingMaximum <= obstacleMinimum + COLLISION_EPSILON) {
            return Math.min(movement, Math.max(0, obstacleMinimum - movingMaximum));
        }
        if (movement < 0 && movingMinimum >= obstacleMaximum - COLLISION_EPSILON) {
            return Math.max(movement, Math.min(0, obstacleMaximum - movingMinimum));
        }
        return movement;
    }

    private static boolean overlaps(
            double firstMinimum,
            double firstMaximum,
            double secondMinimum,
            double secondMaximum
    ) {
        return firstMaximum > secondMinimum + COLLISION_EPSILON
                && firstMinimum < secondMaximum - COLLISION_EPSILON;
    }

    private static boolean finite(Vector3f value) {
        return Float.isFinite(value.x) && Float.isFinite(value.y) && Float.isFinite(value.z);
    }

    private void rebuild(ClientLevel currentLevel, int resolution, long gameTime) {
        cachedSolids.clear();
        cachedResolution = resolution;
        cachedLevel = currentLevel;
        lastRefreshTick = gameTime;
        dirty = false;
        scannedBlockCount = 0;

        volumeMinimumX = center.x - halfExtents.x;
        volumeMinimumY = center.y - halfExtents.y;
        volumeMinimumZ = center.z - halfExtents.z;
        volumeSizeX = halfExtents.x * 2.0;
        volumeSizeY = halfExtents.y * 2.0;
        volumeSizeZ = halfExtents.z * 2.0;
        double maximumX = volumeMinimumX + volumeSizeX;
        double maximumY = volumeMinimumY + volumeSizeY;
        double maximumZ = volumeMinimumZ + volumeSizeZ;

        int minimumBlockX = Mth.floor(volumeMinimumX);
        int minimumBlockY = Mth.floor(volumeMinimumY);
        int minimumBlockZ = Mth.floor(volumeMinimumZ);
        int maximumBlockX = Mth.floor(Math.nextDown(maximumX));
        int maximumBlockY = Mth.floor(Math.nextDown(maximumY));
        int maximumBlockZ = Mth.floor(Math.nextDown(maximumZ));

        for (int z = minimumBlockZ; z <= maximumBlockZ; z++) {
            for (int y = minimumBlockY; y <= maximumBlockY; y++) {
                for (int x = minimumBlockX; x <= maximumBlockX; x++) {
                    mutableBlockPosition.set(x, y, z);
                    if (!currentLevel.hasChunkAt(mutableBlockPosition)) continue;
                    scannedBlockCount++;
                    BlockState state = currentLevel.getBlockState(mutableBlockPosition);
                    VoxelShape collision = state.getCollisionShape(currentLevel, mutableBlockPosition);
                    if (collision.isEmpty()) continue;
                    currentBlockX = x;
                    currentBlockY = y;
                    currentBlockZ = z;
                    collision.forAllBoxes(collisionConsumer);
                }
            }
        }
    }

    private void markCollisionBox(
            double minimumX, double minimumY, double minimumZ,
            double maximumX, double maximumY, double maximumZ
    ) {
        markNormalizedBox(
                (currentBlockX + minimumX - volumeMinimumX) / volumeSizeX,
                (currentBlockY + minimumY - volumeMinimumY) / volumeSizeY,
                (currentBlockZ + minimumZ - volumeMinimumZ) / volumeSizeZ,
                (currentBlockX + maximumX - volumeMinimumX) / volumeSizeX,
                (currentBlockY + maximumY - volumeMinimumY) / volumeSizeY,
                (currentBlockZ + maximumZ - volumeMinimumZ) / volumeSizeZ
        );
    }

    private void markNormalizedBox(
            double minimumX, double minimumY, double minimumZ,
            double maximumX, double maximumY, double maximumZ
    ) {
        FluidCellRasterizer.markBox(
                cachedSolids,
                cachedResolution,
                minimumX, minimumY, minimumZ,
                maximumX, maximumY, maximumZ
        );
    }
}
