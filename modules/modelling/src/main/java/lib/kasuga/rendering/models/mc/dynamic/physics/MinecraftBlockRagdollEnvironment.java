package lib.kasuga.rendering.models.mc.dynamic.physics;

import lib.kasuga.client.ClientBlockUpdateHooks;
import lib.kasuga.rendering.models.uml.dynamic.physics.core.CollisionEnvironment;
import lib.kasuga.rendering.models.uml.dynamic.physics.core.EnvironmentBox;
import lib.kasuga.rendering.models.uml.dynamic.physics.core.EnvironmentCell;
import lib.kasuga.rendering.models.uml.dynamic.physics.core.RigidBodyWorld;
import lib.kasuga.rendering.models.uml.dynamic.physics.core.SimBody;
import lib.kasuga.rendering.models.uml.dynamic.physics.core.StaticEnvironmentMesh;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3f;
import org.joml.Vector3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Incremental Minecraft terrain adapter for {@link lib.kasuga.rendering.models.uml.dynamic.physics.SkeletonRagdoll SkeletonRagdoll}.
 *
 * <p>Every block position is a cached mesh cell. Moving the ragdoll only adds
 * cells entering the broad phase and removes cells leaving it. The retained
 * voxel shapes are compiled into one native triangle mesh whenever the cache
 * revision changes; a bounded background diff checks block/fluid state and
 * replaces only cells whose state changed. Explicit invalidation is
 * available for block-update hooks and also refreshes neighbors whose voxel
 * shape may depend on the changed block.</p>
 */
public final class MinecraftBlockRagdollEnvironment
        implements CollisionEnvironment, AutoCloseable {
    private static final int DEFAULT_MAX_SCANNED_BLOCKS = 4096;
    private static final int MAX_VALIDATIONS_PER_UPDATE = 96;

    private final Supplier<? extends Level> levelSupplier;
    private final int refreshIntervalTicks;
    private final float padding;
    private final float friction;
    private final float restitution;
    private final int maxScannedBlocks;
    private final BlockPos.MutableBlockPos mutablePosition = new BlockPos.MutableBlockPos();
    private final Map<Long, CachedCell> cells = new HashMap<>();
    private final ArrayDeque<Long> validationQueue = new ArrayDeque<>();
    private final Set<Long> queuedValidation = new HashSet<>();
    private final Set<Long> forcedValidation = new HashSet<>();

    private Predicate<BlockPos> excludedBlock = ignored -> false;
    private RigidBodyWorld attachedWorld;
    private StaticEnvironmentMesh environmentMesh;
    private Level cachedLevel;
    private final AutoCloseable blockUpdateListener;
    private long lastValidationTime = Long.MIN_VALUE;
    private int minX, minY, minZ, maxX, maxY, maxZ;
    private int scannedBlockCount;
    private boolean boundsInitialized;
    private boolean truncated;
    private boolean validateAll = true;

    public MinecraftBlockRagdollEnvironment(Supplier<? extends Level> levelSupplier,
                                             int refreshIntervalTicks) {
        this(levelSupplier, refreshIntervalTicks, 0.25f, 0.8f, 0f,
                DEFAULT_MAX_SCANNED_BLOCKS);
    }

    public MinecraftBlockRagdollEnvironment(Supplier<? extends Level> levelSupplier,
                                             int refreshIntervalTicks, float padding,
                                             float friction, float restitution,
                                             int maxScannedBlocks) {
        this.levelSupplier = Objects.requireNonNull(levelSupplier, "levelSupplier");
        if (refreshIntervalTicks < 1) throw new IllegalArgumentException("refreshIntervalTicks must be positive");
        if (!(padding >= 0f) || !Float.isFinite(padding)) {
            throw new IllegalArgumentException("padding must be finite and non-negative");
        }
        if (!Float.isFinite(friction) || !Float.isFinite(restitution)) {
            throw new IllegalArgumentException("material properties must be finite");
        }
        if (maxScannedBlocks < 1) throw new IllegalArgumentException("maxScannedBlocks must be positive");
        this.refreshIntervalTicks = refreshIntervalTicks;
        this.padding = padding;
        this.friction = Math.max(0f, friction);
        this.restitution = Math.clamp(restitution, 0f, 1f);
        this.maxScannedBlocks = maxScannedBlocks;
        // Live block updates reach the mesh immediately; the periodic diff
        // remains as a fallback for bulk section updates and missed events.
        this.blockUpdateListener = ClientBlockUpdateHooks.addListener(this::onClientBlockUpdate);
    }

    public void exclude(Predicate<BlockPos> excludedBlock) {
        this.excludedBlock = Objects.requireNonNull(excludedBlock, "excludedBlock");
        invalidate();
    }

    /** Schedules a bounded diff of every retained cell; it does not tear down the mesh. */
    public void invalidate() {
        validateAll = true;
        if (attachedWorld != null) attachedWorld.wake();
    }

    /** Invalidates one block and its shape/flow-dependent neighbors. */
    public void invalidate(BlockPos position) {
        Objects.requireNonNull(position, "position");
        if (attachedWorld != null) attachedWorld.wake();
        forceValidation(position.asLong());
        for (Direction direction : Direction.values()) {
            forceValidation(position.relative(direction).asLong());
        }
    }

    /**
     * Client hook for {@code ClientLevel#sendBlockUpdated}. Cheap rejects run
     * first: other levels, changes outside the padded bounds and no-op state
     * writes never touch the ragdoll.
     */
    private void onClientBlockUpdate(ClientBlockUpdateHooks.Update update) {
        Level level = cachedLevel;
        if (!boundsInitialized || level == null || level != update.level()) return;
        if (update.oldState() == update.newState()) return;
        BlockPos position = BlockPos.of(update.packedPos());
        int x = position.getX(), y = position.getY(), z = position.getZ();
        // Neighbors are included: invalidate(BlockPos) also refreshes them.
        boolean nearBounds = x >= minX - 1 && x <= maxX + 1
                && y >= minY - 1 && y <= maxY + 1
                && z >= minZ - 1 && z <= maxZ + 1;
        if (nearBounds) invalidate(position);
    }

    public int scannedBlockCount() { return scannedBlockCount; }
    public int colliderCount() { return environmentMesh == null ? 0 : environmentMesh.solidCount(); }
    public int cachedCellCount() { return cells.size(); }
    public boolean truncated() { return truncated; }

    @Override
    public void update(RigidBodyWorld world) {
        Objects.requireNonNull(world, "world");
        Level level = levelSupplier.get();
        if (attachedWorld != world || cachedLevel != level) attach(world, level);
        if (level == null || world.bodies().isEmpty()) {
            detachMesh();
            return;
        }

        Bounds bounds = bounds(world);
        int nextMinX = Mth.floor(bounds.minimum.x);
        int nextMinY = Mth.floor(bounds.minimum.y);
        int nextMinZ = Mth.floor(bounds.minimum.z);
        int nextMaxX = Mth.floor(Math.nextDown(bounds.maximum.x));
        int nextMaxY = Mth.floor(Math.nextDown(bounds.maximum.y));
        int nextMaxZ = Mth.floor(Math.nextDown(bounds.maximum.z));
        boolean boundsChanged = !boundsInitialized
                || nextMinX != minX || nextMinY != minY || nextMinZ != minZ
                || nextMaxX != maxX || nextMaxY != maxY || nextMaxZ != maxZ;
        if (boundsChanged) {
            minX = nextMinX; minY = nextMinY; minZ = nextMinZ;
            maxX = nextMaxX; maxY = nextMaxY; maxZ = nextMaxZ;
            boundsInitialized = true;
            synchronizeBounds(level);
        }

        long gameTime = level.getGameTime();
        boolean validationExpired = gameTime < lastValidationTime
                || gameTime - lastValidationTime >= refreshIntervalTicks;
        if (validateAll || validationExpired) {
            enqueueRetainedCells(validateAll);
            validateAll = false;
            lastValidationTime = gameTime;
        }
        validateQueuedCells(level);
    }

    private void attach(RigidBodyWorld world, Level level) {
        detachMesh();
        attachedWorld = world;
        cachedLevel = level;
        boundsInitialized = false;
        lastValidationTime = Long.MIN_VALUE;
        validateAll = true;
        if (level != null) environmentMesh = attachedWorld.addEnvironmentMesh(friction, restitution);
    }

    private void synchronizeBounds(Level level) {
        if (environmentMesh == null) environmentMesh = attachedWorld.addEnvironmentMesh(friction, restitution);
        Iterator<Map.Entry<Long, CachedCell>> iterator = cells.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, CachedCell> entry = iterator.next();
            BlockPos position = BlockPos.of(entry.getKey());
            if (insideBounds(position)) continue;
            environmentMesh.removeCell(entry.getKey());
            queuedValidation.remove(entry.getKey());
            forcedValidation.remove(entry.getKey());
            iterator.remove();
        }

        truncated = false;
        outer:
        for (int z = minZ; z <= maxZ; z++) {
            for (int y = minY; y <= maxY; y++) {
                for (int x = minX; x <= maxX; x++) {
                    long key = BlockPos.asLong(x, y, z);
                    if (cells.containsKey(key)) continue;
                    if (cells.size() >= maxScannedBlocks) {
                        truncated = true;
                        break outer;
                    }
                    refreshCell(level, key, true);
                }
            }
        }
        scannedBlockCount = cells.size();
        if (lastValidationTime == Long.MIN_VALUE) {
            // The initial population already read every retained state and
            // built its geometry; do not immediately scan it a second time.
            lastValidationTime = level.getGameTime();
            validateAll = false;
        }
    }

    private void enqueueRetainedCells(boolean force) {
        for (long key : cells.keySet()) {
            if (force) forcedValidation.add(key);
            enqueueValidation(key);
        }
    }

    private void validateQueuedCells(Level level) {
        int budget = Math.min(MAX_VALIDATIONS_PER_UPDATE, Math.max(1, cells.size()));
        while (budget-- > 0 && !validationQueue.isEmpty()) {
            long key = validationQueue.removeFirst();
            queuedValidation.remove(key);
            if (!cells.containsKey(key) || !insideBounds(BlockPos.of(key))) {
                forcedValidation.remove(key);
                continue;
            }
            boolean force = forcedValidation.remove(key);
            if (refreshCell(level, key, force)) {
                BlockPos changed = BlockPos.of(key);
                for (Direction direction : Direction.values()) {
                    long neighbor = changed.relative(direction).asLong();
                    if (cells.containsKey(neighbor)) forceValidation(neighbor);
                }
            }
        }
    }

    /** Returns true if the cached block/fluid state changed. */
    private boolean refreshCell(Level level, long key, boolean force) {
        mutablePosition.set(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key));
        boolean loaded = level.hasChunkAt(mutablePosition);
        BlockState state = loaded ? level.getBlockState(mutablePosition) : null;
        FluidState fluid = loaded ? state.getFluidState() : null;
        CachedCell previous = cells.get(key);
        boolean stateChanged = previous == null || previous.loaded != loaded
                || previous.state != state || previous.fluid != fluid;
        if (!force && !stateChanged) return false;
        if (stateChanged && previous != null && attachedWorld != null) attachedWorld.wake();

        EnvironmentCell geometry = loaded ? geometry(level, mutablePosition, state)
                : EnvironmentCell.EMPTY;
        cells.put(key, new CachedCell(loaded, state, fluid, geometry));
        if (previous == null || !previous.geometry.equals(geometry)) {
            environmentMesh.putCell(key, geometry);
        }
        return stateChanged;
    }

    private EnvironmentCell geometry(Level level, BlockPos position, BlockState state) {
        if (excludedBlock.test(position.immutable())) return EnvironmentCell.EMPTY;
        int blockX = position.getX(), blockY = position.getY(), blockZ = position.getZ();
        Vector3d origin = attachedWorld.worldOrigin();
        List<EnvironmentBox> solids = new ArrayList<>();
        VoxelShape shape = state.getCollisionShape(level, position);
        if (!shape.isEmpty()) {
            shape.forAllBoxes((boxMinX, boxMinY, boxMinZ, boxMaxX, boxMaxY, boxMaxZ) ->
                    solids.add(new EnvironmentBox(
                            new Vector3f((float) (blockX + boxMinX - origin.x),
                                    (float) (blockY + boxMinY - origin.y),
                                    (float) (blockZ + boxMinZ - origin.z)),
                            new Vector3f((float) (blockX + boxMaxX - origin.x),
                                    (float) (blockY + boxMaxY - origin.y),
                                    (float) (blockZ + boxMaxZ - origin.z)))));
        }

        return solids.isEmpty() ? EnvironmentCell.EMPTY : new EnvironmentCell(solids);
    }

    private void forceValidation(long key) {
        if (!cells.containsKey(key)) return;
        forcedValidation.add(key);
        enqueueValidation(key);
    }

    private void enqueueValidation(long key) {
        if (queuedValidation.add(key)) validationQueue.addLast(key);
    }

    private boolean insideBounds(BlockPos position) {
        return position.getX() >= minX && position.getX() <= maxX
                && position.getY() >= minY && position.getY() <= maxY
                && position.getZ() >= minZ && position.getZ() <= maxZ;
    }

    private Bounds bounds(RigidBodyWorld world) {
        Vector3d origin = world.worldOrigin();
        Vector3d minimum = new Vector3d(Double.POSITIVE_INFINITY);
        Vector3d maximum = new Vector3d(Double.NEGATIVE_INFINITY);
        for (SimBody body : world.bodies()) {
            float radius = boundingRadius(body) + padding;
            Vector3f position = body.positionRef();
            minimum.min(new Vector3d(origin).add(position.x - radius,
                    position.y - radius, position.z - radius));
            maximum.max(new Vector3d(origin).add(position.x + radius,
                    position.y + radius, position.z + radius));
        }
        return new Bounds(minimum, maximum);
    }

    private static float boundingRadius(SimBody body) {
        Vector3f size = body.shapeSizeRef().absolute();
        return switch (body.shape()) {
            case 0 -> size.x;
            case 1 -> size.length();
            case 2 -> size.x + size.y * 0.5f;
            default -> 0f;
        };
    }

    private void detachMesh() {
        if (attachedWorld != null && environmentMesh != null) {
            attachedWorld.removeEnvironmentMesh(environmentMesh);
        }
        environmentMesh = null;
        cells.clear();
        validationQueue.clear();
        queuedValidation.clear();
        forcedValidation.clear();
        scannedBlockCount = 0;
        truncated = false;
    }

    @Override
    public void close() {
        try {
            blockUpdateListener.close();
        } catch (Exception exception) {
            // Listener removal must never break environment teardown.
        }
        detachMesh();
        attachedWorld = null;
        cachedLevel = null;
    }

    private record CachedCell(boolean loaded, BlockState state, FluidState fluid,
                              EnvironmentCell geometry) {}
    private record Bounds(Vector3d minimum, Vector3d maximum) {}
}
