package lib.kasuga.rendering.models.mc.dynamic.physics;

import lib.kasuga.rendering.models.uml.dynamic.physics.core.GenericRigidBody;
import lib.kasuga.rendering.models.uml.dynamic.physics.core.BodyShape;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Official rigid-body adapter for a single Minecraft block: one block state
 * simulated as a dynamic unit Box3D box ({@code 0.5} half extents) whose mass
 * and friction derive from the authored block.
 *
 * <p>Spawn these through {@link MinecraftBlockPhysics}; the visual is drawn by
 * the vanilla block renderer, so every modelled block works automatically.</p>
 */
public final class MinecraftBlockRigidBody {
    /** Half extent of the oriented box representing one full block. */
    public static final float HALF_EXTENT = 0.5f;

    /**
     * Dedicated collision group so hosts can separate "props" from default
     * group-0 geometry (static terrain). The player proxy opts out of group 0
     * while keeping prop contacts.
     */
    public static final int COLLISION_GROUP = 1;

    private static final Vector3f BLOCK_BOX = new Vector3f(HALF_EXTENT, HALF_EXTENT, HALF_EXTENT);

    private final BlockState state;
    private final GenericRigidBody body;

    private MinecraftBlockRigidBody(BlockState state, Vec3 center, float mass,
                                    List<? extends BodyShape> collisionShapes) {
        this.state = state;
        this.body = GenericRigidBody.compound(collisionShapes, mass)
                .at((float) center.x, (float) center.y, (float) center.z)
                .friction(blockFriction(state))
                .restitution(0.05f)
                .filter(COLLISION_GROUP, 0);
    }

    /** Creates a falling/tumbling physics block at the supplied world-space center. */
    public static MinecraftBlockRigidBody spawnDynamic(BlockState state, Vec3 center) {
        return new MinecraftBlockRigidBody(state, center, blockMass(state),
                List.of(new BodyShape.Box(BLOCK_BOX)));
    }

    /**
     * Creates a body from the block's actual voxel collision boxes. Shape
     * coordinates are converted from block-local {@code [0,1]} coordinates to
     * Box3D local coordinates centered on the rendered block.
     */
    public static MinecraftBlockRigidBody spawnDynamic(BlockState state, Vec3 center,
                                                        VoxelShape collisionShape) {
        List<BodyShape> shapes = collisionShapes(collisionShape);
        if (shapes.isEmpty()) {
            throw new IllegalArgumentException("a physics block requires a non-empty collision shape");
        }
        return new MinecraftBlockRigidBody(Objects.requireNonNull(state, "state"),
                Objects.requireNonNull(center, "center"), blockMass(state), shapes);
    }

    /** Converts a Minecraft voxel shape into local Box3D box shapes. */
    public static List<BodyShape> collisionShapes(VoxelShape collisionShape) {
        Objects.requireNonNull(collisionShape, "collisionShape");
        List<BodyShape> result = new ArrayList<>();
        collisionShape.forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) -> {
            float halfX = (float)((maxX - minX) * 0.5);
            float halfY = (float)((maxY - minY) * 0.5);
            float halfZ = (float)((maxZ - minZ) * 0.5);
            if (halfX <= 0f || halfY <= 0f || halfZ <= 0f) return;
            result.add(new BodyShape.Box(
                    new Vector3f((float)((minX + maxX) * 0.5 - 0.5),
                            (float)((minY + maxY) * 0.5 - 0.5),
                            (float)((minZ + maxZ) * 0.5 - 0.5)),
                    new Vector3f(halfX, halfY, halfZ)));
        });
        return List.copyOf(result);
    }

    /**
     * Mass derived from block hardness: dirt-like blocks are light, metals and
     * stone are heavy. Negative hardness (unbreakable) uses an upper bound.
     */
    public static float blockMass(BlockState state) {
        float hardness = Math.max(0f, state.getBlock().defaultDestroyTime());
        return Mth.clamp(1f + hardness, 1f, 16f) * 4f;
    }

    /** Friction derived from slipperiness so ice slides farther than soil. */
    public static float blockFriction(BlockState state) {
        float slipperiness = state.getBlock().getFriction();
        // Vanilla slipperiness ranges from 0.6 (most blocks) to 0.98 (ice);
        // invert it into Box3D friction so slippery surfaces grip less.
        return Mth.clamp(0.9f - slipperiness, 0.05f, 0.85f);
    }

    public BlockState state() { return state; }

    public Block block() { return state.getBlock(); }

    /** The simulated body; add/remove or apply impulses through the owning world. */
    public GenericRigidBody body() { return body; }

    /** Number of Box3D child shapes used by this block. */
    public int collisionShapeCount() { return body.collisionShapes().size(); }

    /** Current world-space center of the box. */
    public Vec3 position() {
        Vector3f p = body.positionRef();
        return new Vec3(p.x, p.y, p.z);
    }

    /** The block position this box currently occupies, for diagnostics. */
    public BlockPos blockPosition() {
        Vector3f p = body.positionRef();
        return BlockPos.containing(p.x - HALF_EXTENT, p.y - HALF_EXTENT, p.z - HALF_EXTENT);
    }
}
