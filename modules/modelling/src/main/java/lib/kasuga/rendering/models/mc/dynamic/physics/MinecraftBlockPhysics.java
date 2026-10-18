package lib.kasuga.rendering.models.mc.dynamic.physics;

import com.mojang.blaze3d.vertex.PoseStack;
import lib.kasuga.rendering.models.uml.dynamic.physics.core.BodyContact;
import lib.kasuga.rendering.models.uml.dynamic.physics.core.CollisionEnvironment;
import lib.kasuga.rendering.models.uml.dynamic.physics.core.Frames;
import lib.kasuga.rendering.models.uml.dynamic.physics.core.GenericRigidBody;
import lib.kasuga.rendering.models.uml.dynamic.physics.core.RigidBodyWorld;
import lib.kasuga.rendering.models.uml.dynamic.physics.core.SimBody;
import lib.kasuga.rendering.models.uml.dynamic.physics.box3d.NativeBox3D;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Official "physics block" playground built on the generic rigid-body engine.
 *
 * <p>Players spawn Minecraft blocks that fall, tumble and collide with the
 * real terrain: every spawned block is a {@link MinecraftBlockRigidBody} — a
 * unit oriented box — inside one shared {@link RigidBodyWorld}, whose static
 * geometry comes from {@link MinecraftBlockRagdollEnvironment}. Visuals are
 * drawn with the vanilla block renderer, so every modelled block works.</p>
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class MinecraftBlockPhysics {
    /** Upper bound on simultaneously simulated physics blocks. */
    public static final int MAX_PROPS = 256;
    private static final float PLAYER_PROXY_MASS = 4f;
    /** Horizontal shrink applied to the player proxy to avoid edge-brush kicks. */
    private static final float PLAYER_PROXY_SHRINK = 0.85f;
    /** Player proxy collision group; group 0 (terrain) is excluded via non-collision mask. */
    private static final int PLAYER_COLLISION_GROUP = 15;
    /** Per-axis cap on the contact correction applied to the player per frame, in blocks. */
    static final float MAX_CORRECTION = 0.15f;
    /** Contacts with |normal.y| at or above this count as ground support. */
    static final float SUPPORT_NORMAL_Y = 0.55f;

    private static final ArrayDeque<MinecraftBlockRigidBody> PROPS = new ArrayDeque<>();
    private static RigidBodyWorld world;
    private static MinecraftBlockRagdollEnvironment environment;
    private static ClientLevel boundLevel;
    private static GenericRigidBody playerCollider;
    private static final Vector3f playerHalfExtents = new Vector3f();

    private static final RigidBodyWorld.KinematicDriver ENTITY_DRIVER = new RigidBodyWorld.KinematicDriver() {
        @Override public void beginStep(RigidBodyWorld simulatedWorld) {}

        @Override
        public Frames.Pose kinematicTarget(SimBody body) {
            if (body == playerCollider) {
                LocalPlayer player = Minecraft.getInstance().player;
                if (player != null) return playerPose(player);
            }
            return new Frames.Pose(body.positionRef(), body.rotationRef());
        }
    };

    private MinecraftBlockPhysics() {}

    // ------------------------------------------------------------------
    // Public control surface
    // ------------------------------------------------------------------

    /**
     * Spawns a dynamic physics block at the world-space center point. The
     * oldest prop is recycled once {@link #MAX_PROPS} is reached.
     */
    public static synchronized Optional<MinecraftBlockRigidBody> spawn(
            ClientLevel level, Vec3 center, BlockState state) {
        if (!NativeBox3D.availableOrWarn()) return Optional.empty();
        Objects_requireLevel(level);
        if (PROPS.size() >= MAX_PROPS) {
            MinecraftBlockRigidBody oldest = PROPS.pollFirst();
            if (oldest != null) world.remove(oldest.body());
        }
        BlockPos collisionContext = BlockPos.containing(center);
        VoxelShape collisionShape = state.getCollisionShape(level, collisionContext);
        if (collisionShape.isEmpty()) return Optional.empty();
        MinecraftBlockRigidBody prop = MinecraftBlockRigidBody.spawnDynamic(
                state, center, collisionShape);
        // A touch of spin makes spawned blocks feel physical instead of
        // sliding down like sand entities.
        net.minecraft.util.RandomSource random = net.minecraft.util.RandomSource.create();
        prop.body().setAngularVelocity(new Vector3f(
                Mth.randomBetween(random, -2f, 2f),
                Mth.randomBetween(random, -1f, 1f),
                Mth.randomBetween(random, -2f, 2f)));
        prop.body().wireWake(world::wake);
        if (!world.add(prop.body())) return Optional.empty();
        PROPS.addLast(prop);
        return Optional.of(prop);
    }

    /** Removes one prop from simulation; returns false when it was already gone. */
    public static synchronized boolean remove(MinecraftBlockRigidBody prop) {
        boolean removed = PROPS.remove(prop);
        if (removed) world.remove(prop.body());
        return removed;
    }

    /** Removes every simulated physics block. */
    public static synchronized int clear() {
        int count = PROPS.size();
        if (world != null) {
            for (MinecraftBlockRigidBody prop : PROPS) world.remove(prop.body());
        }
        PROPS.clear();
        removePlayerCollider();
        return count;
    }

    public static synchronized int activeCount() {
        return PROPS.size();
    }

    private static void Objects_requireLevel(ClientLevel level) {
        if (level == null) throw new IllegalStateException("no client level");
        if (level != boundLevel || world == null) bind(level);
    }

    private static void bind(ClientLevel level) {
        clear();
        if (environment != null) environment.close();
        boundLevel = level;
        world = new RigidBodyWorld(List.of(), List.of(), RigidBodyWorld.DEFAULT_SUBSTEP_COUNT);
        if (level != null) {
            environment = new MinecraftBlockRagdollEnvironment(() -> boundLevel, 1);
            world.setCollisionEnvironment((CollisionEnvironment) environment);
        }
    }

    // ------------------------------------------------------------------
    // Simulation and lifecycle
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != boundLevel) {
            // Dimension changed: drop the whole playground including terrain cache.
            clear();
            if (environment != null) environment.close();
            environment = null;
            boundLevel = level;
            world = null;
        }
    }

    @SubscribeEvent
    public static void onRenderFrame(RenderFrameEvent.Pre event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (world == null || minecraft.level == null || minecraft.isPaused()) return;
        syncPlayerCollider(minecraft.player);
        float deltaSeconds = event.getPartialTick().getRealtimeDeltaTicks() / 20f;
        world.step(deltaSeconds, ENTITY_DRIVER);
        resolvePlayerContacts(minecraft.player);
        despawnFallen(minecraft.level);
    }

    /**
     * Mirrors the local player's current AABB into Box3D as a light, zero-
     * gravity dynamic proxy. A kinematic proxy has infinite inertia and simply
     * punches a prop downward every time vanilla gravity moves the player's
     * feet into it. The finite proxy lets the contact solver move the player
     * back onto the supporting face while transferring only a small response
     * to the block.
     *
     * <p>The proxy never collides with static terrain (collision group 0):
     * vanilla already resolves player-vs-world, and a proxy/terrain manifold
     * only injects solver noise between the player and props resting on the
     * ground. Spectators and dead players get no proxy at all.</p>
     */
    private static synchronized void syncPlayerCollider(LocalPlayer player) {
        if (world == null || player == null || PROPS.isEmpty()
                || player.isSpectator() || !player.isAlive()) {
            removePlayerCollider();
            return;
        }
        AABB bounds = player.getBoundingBox();
        // Slightly smaller than the authoritative AABB so brushing past a
        // block edge does not kick it; contact response still supports the
        // player standing on top.
        Vector3f half = new Vector3f(
                (float)(bounds.getXsize() * 0.5 * PLAYER_PROXY_SHRINK),
                (float)(bounds.getYsize() * 0.5),
                (float)(bounds.getZsize() * 0.5 * PLAYER_PROXY_SHRINK));
        Frames.Pose pose = playerPose(player);
        if (playerCollider == null || !half.equals(playerHalfExtents, 1e-4f)) {
            removePlayerCollider();
            playerHalfExtents.set(half);
            playerCollider = GenericRigidBody.box(half, PLAYER_PROXY_MASS)
                    .at(pose.position)
                    // Near-frictionless: tangential solver coupling with the
                    // walked-on props must not drag them or the proxy sideways;
                    // ground feel is synthesized from contacts, not friction.
                    .friction(0.05f)
                    .damping(8f, 8f)
                    // Group 15 with group 0 (terrain + default bodies) excluded;
                    // props live in MinecraftBlockRigidBody.COLLISION_GROUP.
                    .filter(PLAYER_COLLISION_GROUP, 1);
            playerCollider.wireWake(world::wake);
            world.add(playerCollider);
            world.setGravityScale(playerCollider, 0f);
        } else {
            // Reset the proxy to vanilla's authoritative AABB before this
            // frame. Its solved displacement is contact response only.
            playerCollider.teleport(pose.position, pose.rotation);
        }
    }

    private static Frames.Pose playerPose(LocalPlayer player) {
        AABB bounds = player.getBoundingBox();
        return new Frames.Pose(new Vector3f(
                (float)((bounds.minX + bounds.maxX) * 0.5),
                (float)((bounds.minY + bounds.maxY) * 0.5),
                (float)((bounds.minZ + bounds.maxZ) * 0.5)), new Quaternionf());
    }

    private static synchronized void removePlayerCollider() {
        if (playerCollider != null && world != null) world.remove(playerCollider);
        playerCollider = null;
        playerHalfExtents.zero();
    }

    /**
     * Applies native penetration and impact information back to the local
     * Minecraft player.
     *
     * <p>The correction comes exclusively from the deepest-contact minimum
     * translation vector — never from the proxy's solved displacement. The
     * proxy is teleported onto vanilla's AABB every frame, so its pose delta
     * contains solver reaction against the walk direction; feeding that back
     * made ground movement rubber-band (the "sliding on ice" report).
     * Tangential motion from top-like contacts is discarded: standing on a
     * settling prop stack must not drift the player sideways.</p>
     */
    private static synchronized void resolvePlayerContacts(LocalPlayer player) {
        if (player == null || playerCollider == null || world == null || PROPS.isEmpty()) return;
        Set<SimBody> propBodies = Collections.newSetFromMap(new IdentityHashMap<>());
        for (MinecraftBlockRigidBody prop : PROPS) propBodies.add(prop.body());
        List<BodyContact> contacts = world.contacts(playerCollider).stream()
                .filter(contact -> contact.other().isPresent() && propBodies.contains(contact.other().get()))
                .toList();
        if (contacts.isEmpty()) return;

        Vec3 correction = playerCorrection(contacts);
        if (correction.lengthSqr() > 0d) {
            player.setPos(player.getX() + correction.x,
                    player.getY() + correction.y, player.getZ() + correction.z);
        }

        Vec3 velocity = clipVelocityAgainstContacts(player.getDeltaMovement(), contacts);
        if (supportedOnProps(contacts)) {
            // Vanilla clears onGround every tick (no real collision beneath the
            // props), so it must be re-asserted here: ground friction, jump
            // availability and fall-distance reset all hang off this flag.
            player.setOnGround(true);
            player.fallDistance = 0f;
            if (velocity.y < 0d) velocity = new Vec3(velocity.x, 0d, velocity.z);
        }
        player.setDeltaMovement(velocity);
    }

    /**
     * Minimum translation of the deepest contact, split by orientation:
     * vertical lift from top/bottom-like contacts always applies; horizontal
     * push only from side-like contacts (walking into a stack stops like a
     * real wall). Both components are clamped so a stale or deep manifold
     * cannot teleport the player.
     */
    static Vec3 playerCorrection(List<BodyContact> contacts) {
        BodyContact deepest = deepestContact(contacts);
        if (deepest == null) return Vec3.ZERO;
        float depth = -deepest.separation();
        if (depth <= 0.0005f) return Vec3.ZERO;
        Vector3f normal = deepest.normal();
        float magnitude = Math.min(depth + 0.001f, MAX_CORRECTION);
        boolean topLike = Math.abs(normal.y) >= SUPPORT_NORMAL_Y;
        double x = topLike ? 0d : Mth.clamp(normal.x * magnitude, -MAX_CORRECTION, MAX_CORRECTION);
        double y = Mth.clamp(normal.y * magnitude, -MAX_CORRECTION, MAX_CORRECTION);
        double z = topLike ? 0d : Mth.clamp(normal.z * magnitude, -MAX_CORRECTION, MAX_CORRECTION);
        return new Vec3(x, y, z);
    }

    /** True when at least one touching contact pushes upward — i.e. the props support the player. */
    static boolean supportedOnProps(List<BodyContact> contacts) {
        for (BodyContact contact : contacts) {
            if (contact.touching() && contact.normal().y >= SUPPORT_NORMAL_Y) return true;
        }
        return false;
    }

    private static BodyContact deepestContact(List<BodyContact> contacts) {
        BodyContact deepest = null;
        float deepestDepth = 0f;
        for (BodyContact contact : contacts) {
            float depth = -contact.separation();
            if (depth <= deepestDepth) continue;
            deepestDepth = depth;
            deepest = contact;
        }
        return deepest;
    }

    /**
     * Removes only velocity directed into a contact. Box3D's accumulated
     * impulse belongs to its dynamic body and must not be applied a second
     * time to Minecraft's independently simulated player.
     */
    static Vec3 clipVelocityAgainstContacts(Vec3 input, List<BodyContact> contacts) {
        Vector3f velocity = new Vector3f((float) input.x, (float) input.y, (float) input.z);
        for (BodyContact contact : contacts) {
            if (!contact.touching()) continue;
            Vector3f normal = contact.normal();
            float intoSurface = velocity.dot(normal);
            if (intoSurface < 0f) velocity.fma(-intoSurface, normal);
        }
        return new Vec3(velocity.x, velocity.y, velocity.z);
    }

    private static synchronized void despawnFallen(ClientLevel level) {
        int minimum = level.getMinBuildHeight() - 32;
        PROPS.removeIf(prop -> {
            if (prop.body().positionRef().y >= minimum) return false;
            world.remove(prop.body());
            return true;
        });
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft minecraft = Minecraft.getInstance();
        List<MinecraftBlockRigidBody> snapshot;
        synchronized (MinecraftBlockPhysics.class) {
            if (PROPS.isEmpty()) return;
            snapshot = new ArrayList<>(PROPS);
        }
        ClientLevel level = minecraft.level;
        if (level == null) return;
        PoseStack poses = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        Vec3 camera = event.getCamera().getPosition();
        for (MinecraftBlockRigidBody prop : snapshot) {
            Vector3f position = prop.body().positionRef();
            Quaternionf rotation = prop.body().rotationRef();
            poses.pushPose();
            poses.translate(position.x - camera.x, position.y - camera.y, position.z - camera.z);
            poses.mulPose(rotation);
            poses.translate(-MinecraftBlockRigidBody.HALF_EXTENT,
                    -MinecraftBlockRigidBody.HALF_EXTENT, -MinecraftBlockRigidBody.HALF_EXTENT);
            int light = LevelRenderer.getLightColor(level, prop.blockPosition());
            minecraft.getBlockRenderer().renderSingleBlock(prop.state(), poses, buffers,
                    light, OverlayTexture.NO_OVERLAY);
            poses.popPose();
        }
        buffers.endBatch();
    }
}
