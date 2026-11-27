package lib.kasuga.registration.minecraft.block.shape;

import lib.kasuga.geometry.Shape;
import lib.kasuga.mixins.BlockPropertiesAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Installs compiled plans once per block and resolves them without parsing or converting geometry.
 */
public final class BlockShapeBindings {
    private static final Map<Block, InstalledBlockShapes> INSTALLED = new ConcurrentHashMap<>();

    private BlockShapeBindings() {
    }

    /**
     * Installs a compiled plan. Call this after the block object exists and before its state caches
     * initialize; {@code BlockReg} performs that sequencing for every factory-created block.
     */
    public static void install(Block block, BlockShapePlan plan) {
        install(block, plan, state -> false);
    }

    /**
     * Installs a compiled plan while clipping collision to the anchor cell only for selected states.
     * The unclipped collision remains available through {@link #resolveFull(BlockState, ShapeSlot,
     * Supplier)} for multi-cell companion coverage.
     */
    public static void install(
            Block block,
            BlockShapePlan plan,
            Predicate<BlockState> clipCollisionToAnchor
    ) {
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(clipCollisionToAnchor, "clipCollisionToAnchor");
        if (plan.isEmpty()) return;
        if (INSTALLED.containsKey(block)) {
            throw new IllegalStateException("Shape bindings are already installed for " + block);
        }

        InstalledBlockShapes compiled = compile(block, plan, clipCollisionToAnchor);
        if (INSTALLED.putIfAbsent(block, compiled) != null) {
            throw new IllegalStateException("Shape bindings are already installed for " + block);
        }
    }

    public static boolean isInstalled(Block block) {
        return INSTALLED.containsKey(block);
    }

    /** Test and lifecycle hook. Production plans stay installed for the registry object's life. */
    public static void uninstall(Block block) {
        INSTALLED.remove(Objects.requireNonNull(block, "block"));
    }

    /**
     * Returns an installed static shape, or invokes the captured native operation when the block or
     * slot delegates. Missing and explicit native selections both preserve the original call.
     */
    public static VoxelShape resolve(
            BlockState state,
            ShapeSlot slot,
            Supplier<VoxelShape> nativeQuery
    ) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(slot, "slot");
        Objects.requireNonNull(nativeQuery, "nativeQuery");
        InstalledBlockShapes installed = INSTALLED.get(state.getBlock());
        if (installed == null) return nativeQuery.get();
        ResolvedState statePlan = installed.states().get(state);
        if (statePlan == null) return nativeQuery.get();
        VoxelShape shape = statePlan.shapes()[slot.ordinal()];
        return shape == null ? nativeQuery.get() : shape;
    }

    /**
     * Resolves the unclipped shape before anchor-cell collision clipping. Multi-cell companions
     * use this result so that selection-only geometry does not leak into neighboring collisions.
     */
    public static VoxelShape resolveFull(
            BlockState state,
            ShapeSlot slot,
            Supplier<VoxelShape> nativeQuery
    ) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(slot, "slot");
        Objects.requireNonNull(nativeQuery, "nativeQuery");
        InstalledBlockShapes installed = INSTALLED.get(state.getBlock());
        if (installed == null) return nativeQuery.get();
        ResolvedState statePlan = installed.states().get(state);
        if (statePlan == null) return nativeQuery.get();
        VoxelShape shape = statePlan.fullShapes()[slot.ordinal()];
        return shape == null ? nativeQuery.get() : shape;
    }

    private static InstalledBlockShapes compile(
            Block block,
            BlockShapePlan plan,
            Predicate<BlockState> clipCollisionToAnchor
    ) {
        IdentityHashMap<BlockState, ResolvedState> byState = new IdentityHashMap<>();
        Map<BlockStateKey, BlockState> declaredStates = new HashMap<>();
        Map<Shape, VoxelShape> converted = new HashMap<>();
        for (BlockState state : block.getStateDefinition().getPossibleStates()) {
            declaredStates.put(BlockStateKey.of(state), state);
        }

        for (Map.Entry<BlockStateKey, BlockStateShapeBinding> entry : plan.states().entrySet()) {
            BlockState state = declaredStates.get(entry.getKey());
            if (state == null) {
                throw new IllegalArgumentException(
                        "Shape plan contains state " + entry.getKey().values()
                                + " that is not declared by " + block);
            }
            byState.put(
                    state,
                    resolveState(
                            block,
                            state,
                            entry.getValue(),
                            converted,
                            clipCollisionToAnchor.test(state)
                    )
            );
        }
        return new InstalledBlockShapes(byState);
    }

    private static ResolvedState resolveState(
            Block block,
            BlockState state,
            BlockStateShapeBinding binding,
            Map<Shape, VoxelShape> converted,
            boolean clipCollisionToAnchor
    ) {
        EnumMap<ShapeSlot, VoxelShape> shapes = new EnumMap<>(ShapeSlot.class);
        Map<ShapeSlot, ShapeSelection> selections = binding.selections();

        for (ShapeSlot slot : ShapeSlot.values()) {
            ShapeSelection selection = selections.getOrDefault(slot, ShapeSelection.MISSING);
            Shape resolved = resolveGeometry(selections, slot, new EnumMap<>(ShapeSlot.class));
            if (selection instanceof ShapeSelection.Missing) {
                if (slot == ShapeSlot.SUPPORT) {
                    resolved = resolveGeometry(
                            selections,
                            ShapeSlot.COLLISION,
                            new EnumMap<>(ShapeSlot.class)
                    );
                } else if (slot == ShapeSlot.OCCLUSION) {
                    resolved = resolveGeometry(
                            selections,
                            ShapeSlot.SHAPE,
                            new EnumMap<>(ShapeSlot.class)
                    );
                }
            }
            if (resolved == null) continue;

            VoxelShape voxel = converted.computeIfAbsent(resolved, MinecraftShapeAdapter::toVoxelShape);
            if (slot == ShapeSlot.COLLISION && !hasCollision(block)) {
                voxel = Shapes.empty();
            } else if (slot == ShapeSlot.OCCLUSION && !state.canOcclude()) {
                voxel = Shapes.empty();
            }
            shapes.put(slot, voxel);
        }

        VoxelShape[] indexed = new VoxelShape[ShapeSlot.values().length];
        for (Map.Entry<ShapeSlot, VoxelShape> entry : shapes.entrySet()) {
            indexed[entry.getKey().ordinal()] = entry.getValue();
        }
        VoxelShape[] full = indexed.clone();
        if (clipCollisionToAnchor) {
            VoxelShape collision = indexed[ShapeSlot.COLLISION.ordinal()];
            if (collision != null) {
                indexed[ShapeSlot.COLLISION.ordinal()] = Shapes.join(
                        collision,
                        Shapes.block(),
                        BooleanOp.AND
                );
            }
        }
        return new ResolvedState(indexed, full);
    }

    private static Shape resolveGeometry(
            Map<ShapeSlot, ShapeSelection> selections,
            ShapeSlot wanted,
            EnumMap<ShapeSlot, Boolean> visiting
    ) {
        if (visiting.put(wanted, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("Cyclic block shape-slot alias involving " + wanted);
        }
        try {
            ShapeSelection selection = selections.getOrDefault(wanted, ShapeSelection.MISSING);
            return switch (selection) {
                case ShapeSelection.Geometry geometry -> geometry.shape();
                case ShapeSelection.Empty ignored -> Shape.empty();
                case ShapeSelection.Alias alias ->
                        resolveGeometry(selections, alias.target(), visiting);
                case ShapeSelection.Missing ignored -> null;
                case ShapeSelection.Native ignored -> null;
            };
        } finally {
            visiting.remove(wanted);
        }
    }

    private static boolean hasCollision(Block block) {
        return !(block.properties() instanceof BlockPropertiesAccess access)
                || access.kasuga$hasCollision();
    }

    private record InstalledBlockShapes(Map<BlockState, ResolvedState> states) {
        private InstalledBlockShapes(Map<BlockState, ResolvedState> states) {
            this.states = Collections.unmodifiableMap(new IdentityHashMap<>(states));
        }
    }

    private record ResolvedState(VoxelShape[] shapes, VoxelShape[] fullShapes) {
    }
}
