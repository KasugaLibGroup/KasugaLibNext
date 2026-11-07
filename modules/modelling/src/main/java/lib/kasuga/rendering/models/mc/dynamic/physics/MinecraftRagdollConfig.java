package lib.kasuga.rendering.models.mc.dynamic.physics;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import lib.kasuga.utils.json.GsonBindings;
import lib.kasuga.utils.json.JsonFieldPolicy.NonNullFields;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.physics.SkeletonRagdoll;
import lib.kasuga.rendering.models.uml.dynamic.physics.ModelPhysicsScene;
import lib.kasuga.rendering.models.uml.dynamic.physics.core.DragSettings;
import lib.kasuga.rendering.models.uml.dynamic.physics.core.RigidBodyWorld;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Data-driven Minecraft binding for an {@link SkeletonRagdoll}. Resource packs may
 * provide their own body topology, joint limits, Box3D cadence and terrain
 * collision settings without changing model-loader code.
 */
public record MinecraftRagdollConfig(
        SkeletonRagdoll.Profile profile,
        Simulation simulation,
        Collision collision,
        Environment environment,
        InitialState initialState,
        Dragging dragging,
        UpdateMode updateMode,
        Sleeping sleeping
) {
    public MinecraftRagdollConfig {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(simulation, "simulation");
        Objects.requireNonNull(collision, "collision");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(initialState, "initialState");
        Objects.requireNonNull(dragging, "dragging");
        Objects.requireNonNull(updateMode, "updateMode");
        Objects.requireNonNull(sleeping, "sleeping");
    }

    public static MinecraftRagdollConfig load(ResourceManager manager,
                                                ResourceLocation location) throws IOException {
        Resource resource = manager.getResource(location)
                .orElseThrow(() -> new IOException("Missing ragdoll config: " + location));
        try (Reader reader = resource.openAsReader()) {
            return build(GsonBindings.GSON.fromJson(reader, Input.class));
        } catch (RuntimeException exception) {
            throw new IOException("Invalid ragdoll config: " + location, exception);
        }
    }

    public static MinecraftRagdollConfig fromJson(JsonObject root) {
        return build(GsonBindings.GSON.fromJson(Objects.requireNonNull(root, "root"), Input.class));
    }

    private static MinecraftRagdollConfig build(Input input) {
        Objects.requireNonNull(input, "ragdoll config");
        if (input.bodies == null || input.bodies.isEmpty()) throw new JsonParseException("bodies must not be empty");
        Map<SkeletonRagdoll.BodyRole, SkeletonRagdoll.SwingTwistLimit> limits = new EnumMap<>(SkeletonRagdoll.BodyRole.class);
        input.limits.forEach((role, limit) -> limits.put(role(role), limit.build(0.7f)));
        List<SkeletonRagdoll.Registration> registrations = new ArrayList<>(input.bodies.size());
        for (BodyInput body : input.bodies) {
            int rigidBody = Objects.requireNonNull(body.rigidBody, "rigid_body");
            SkeletonRagdoll.BodyRole role = role(body.role);
            boolean euler = body.rotationMinDegrees != null || body.rotationMaxDegrees != null;
            boolean swing = body.maxSwingDegrees != null || body.minTwistDegrees != null || body.maxTwistDegrees != null;
            if (euler && swing) throw new JsonParseException("body limit must use either swing/twist or Euler fields");
            if (swing) {
                registrations.add(new SkeletonRagdoll.Registration(rigidBody, body.parent, role, body.build(0.58f)));
            } else if (euler) {
                registrations.add(new SkeletonRagdoll.Registration(rigidBody, body.parent, role,
                        radians(vector(body.rotationMinDegrees, "rotation_min_degrees")),
                        radians(vector(body.rotationMaxDegrees, "rotation_max_degrees"))));
            } else {
                var limit = limits.get(role);
                registrations.add(limit == null ? new SkeletonRagdoll.Registration(rigidBody, body.parent, role)
                        : new SkeletonRagdoll.Registration(rigidBody, body.parent, role, limit));
            }
        }
        return new MinecraftRagdollConfig(
                new SkeletonRagdoll.Profile(registrations,
                        input.includeSecondaryBodies),
                new Simulation(
                        input.simulation.hertz,
                        input.simulation.substeps,
                        input.simulation.solverIterations,
                        input.simulation.constraintHertz,
                        input.simulation.constraintDampingRatio,
                        input.simulation.maxLinearSpeed,
                        input.simulation.maxAngularSpeed,
                        input.simulation.maxFixedStepsPerUpdate,
                        vector(input.simulation.gravity, "gravity")),
                new Collision(
                        input.collision.enabled,
                        input.collision.selfCollision,
                        input.collision.continuous),
                new Environment(
                        input.environment.enabled,
                        input.environment.refreshIntervalTicks,
                        input.environment.padding,
                        input.environment.friction,
                        input.environment.restitution,
                        input.environment.maxScannedBlocks),
                new InitialState(
                        vector(input.initialState.offset, "offset"),
                        vector(input.initialState.rootLinearVelocity, "root_linear_velocity"),
                        vector(input.initialState.rootAngularVelocity, "root_angular_velocity")),
                new Dragging(
                        input.dragging.enabled,
                        input.dragging.mouseButton,
                        input.dragging.maxDistance,
                        new DragSettings(
                                input.dragging.positionSlop,
                                input.dragging.positionStiffness,
                                input.dragging.maxPositionCorrection,
                                input.dragging.biasRate,
                                input.dragging.maxBiasSpeed,
                                input.dragging.maxTargetSpeed,
                                input.dragging.maxVelocityImpulse,
                                input.dragging.relativeLinearDamping,
                                input.dragging.relativeAngularDamping)),
                UpdateMode.valueOf(input.simulation.updateMode.toUpperCase(Locale.ROOT)),
                new Sleeping(
                        input.sleeping.enabled,
                        input.sleeping.linearSpeed,
                        input.sleeping.angularSpeed,
                        input.sleeping.delaySeconds));
    }

    private static SkeletonRagdoll.BodyRole role(String role) {
        try { return SkeletonRagdoll.BodyRole.valueOf(Objects.requireNonNull(role, "role").toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException failure) { throw new JsonParseException("Unknown body role: " + role, failure); }
    }

    @NonNullFields
    private static class LimitInput {
        Float maxSwingDegrees, minTwistDegrees, maxTwistDegrees, limitStiffness;
        SkeletonRagdoll.SwingTwistLimit build(float defaultStiffness) {
            return new SkeletonRagdoll.SwingTwistLimit(
                    radians(Objects.requireNonNull(maxSwingDegrees, "max_swing_degrees")),
                    radians(Objects.requireNonNull(minTwistDegrees, "min_twist_degrees")),
                    radians(Objects.requireNonNull(maxTwistDegrees, "max_twist_degrees")),
                    limitStiffness == null ? defaultStiffness : limitStiffness);
        }
    }
    @NonNullFields
    private static final class BodyInput extends LimitInput {
        Integer rigidBody, parent = -1;
        String role;
        float[] rotationMinDegrees, rotationMaxDegrees;
    }
    @NonNullFields
    private static final class Input {
        List<BodyInput> bodies;
        Map<String, LimitInput> limits = Map.of();
        Boolean includeSecondaryBodies = false;
        SimulationInput simulation = new SimulationInput();
        CollisionInput collision = new CollisionInput();
        EnvironmentInput environment = new EnvironmentInput();
        InitialInput initialState = new InitialInput();
        DraggingInput dragging = new DraggingInput();
        SleepingInput sleeping = new SleepingInput();
    }
    @NonNullFields
    private static final class SimulationInput {
        Float hertz = 120f;
        Integer substeps = RigidBodyWorld.PROFILE_SUBSTEP_COUNT;
        Integer solverIterations = 12;
        Float constraintHertz = 60f;
        Float constraintDampingRatio = 2f;
        Float maxLinearSpeed = 100f;
        Float maxAngularSpeed = 50f;
        Integer maxFixedStepsPerUpdate = 12;
        float[] gravity = new float[]{0f, -9.80665f, 0f};
        String updateMode = "RENDER_FRAME";
    }
    @NonNullFields
    private static final class CollisionInput {
        Boolean enabled = true;
        Boolean selfCollision = false;
        Boolean continuous = true;
    }
    @NonNullFields
    private static final class EnvironmentInput {
        Boolean enabled = true;
        Integer refreshIntervalTicks = 1;
        Float padding = 0.25f;
        Float friction = 0.8f;
        Float restitution = 0f;
        Integer maxScannedBlocks = 4096;
    }
    @NonNullFields
    private static final class InitialInput {
        float[] offset = new float[]{0f, 0f, 0f};
        float[] rootLinearVelocity = new float[]{0f, 0f, 0f};
        float[] rootAngularVelocity = new float[]{0f, 0f, 0f};
    }
    @NonNullFields
    private static final class DraggingInput {
        Boolean enabled = true;
        Integer mouseButton = 0;
        Float maxDistance = 16f;
        Float positionSlop = 0.002f;
        Float positionStiffness = 0.16f;
        Float maxPositionCorrection = 0.012f;
        Float biasRate = 7f;
        Float maxBiasSpeed = 4f;
        Float maxTargetSpeed = 6f;
        Float maxVelocityImpulse = 0.65f;
        Float relativeLinearDamping = 12.643f;
        Float relativeAngularDamping = 4.899f;
    }
    @NonNullFields
    private static final class SleepingInput {
        Boolean enabled = true;
        Float linearSpeed = 0.04f;
        Float angularSpeed = 0.25f;
        Float delaySeconds = 0.75f;
    }

    /**
     * Creates and completely configures the ragdoll and optional Minecraft
     * terrain adapter, or returns {@code null} when Box3D is unavailable.
     */
    @Nullable
    public SkeletonRagdoll attach(ModelInstance instance, Supplier<? extends Level> levelSupplier) {
        return attach(instance, levelSupplier, true);
    }

    @Nullable
    public SkeletonRagdoll attach(ModelInstance instance, Supplier<? extends Level> levelSupplier,
                             boolean applyInitialState) {
        SkeletonRagdoll ragdoll = instance.enablePhysics(profile);
        if (ragdoll == null) return null;
        configure(instance, ragdoll, levelSupplier, applyInitialState);
        MinecraftRagdollRuntime.register(instance, updateMode);
        return ragdoll;
    }

    /** Attaches one model to a shared scene; scene settings are owned collectively. */
    @Nullable
    public SkeletonRagdoll attach(ModelInstance instance, ModelPhysicsScene scene,
                             Supplier<? extends Level> levelSupplier,
                             boolean applyInitialState) {
        SkeletonRagdoll ragdoll = scene.attach(instance, profile);
        if (ragdoll == null) return null;
        configure(instance, ragdoll, levelSupplier, applyInitialState);
        MinecraftRagdollRuntime.unregister(instance);
        MinecraftRagdollRuntime.register(scene, updateMode);
        return ragdoll;
    }

    private void configure(ModelInstance instance, SkeletonRagdoll ragdoll,
                           Supplier<? extends Level> levelSupplier,
                           boolean applyInitialState) {
        ragdoll.setSimulationHertz(simulation.hertz);
        ragdoll.setSubstepCount(simulation.substeps);
        ragdoll.setSolverIterations(simulation.solverIterations);
        ragdoll.setConstraintTuning(simulation.constraintHertz, simulation.constraintDampingRatio);
        ragdoll.setSpeedLimits(simulation.maxLinearSpeed, simulation.maxAngularSpeed);
        ragdoll.setMaxFixedStepsPerUpdate(simulation.maxFixedStepsPerUpdate);
        ragdoll.setSleepingThresholds(sleeping.linearSpeed, sleeping.angularSpeed,
                sleeping.delaySeconds);
        ragdoll.setSleepingEnabled(sleeping.enabled);
        ragdoll.setGravity(simulation.gravity);
        ragdoll.setCollisionsEnabled(collision.enabled);
        ragdoll.setSelfCollisionsEnabled(collision.selfCollision);
        ragdoll.setContinuousCollisionEnabled(collision.continuous);
        ragdoll.setDragSettings(dragging.settings);
        if (environment.enabled) {
            ragdoll.setCollisionEnvironment(new MinecraftBlockRagdollEnvironment(
                    levelSupplier, environment.refreshIntervalTicks, environment.padding,
                    environment.friction, environment.restitution, environment.maxScannedBlocks));
        } else {
            ragdoll.setCollisionEnvironment(null);
        }
        if (applyInitialState) applyInitialState(ragdoll);
        if (dragging.enabled) MinecraftRagdollDragger.register(instance, dragging);
        else MinecraftRagdollDragger.unregister(instance);
    }

    private void applyInitialState(SkeletonRagdoll ragdoll) {
        for (SkeletonRagdoll.Body body : ragdoll.bodies()) {
            body.teleport(body.position().add(initialState.offset), body.rotation());
        }
        for (int i = 0; i < profile.bodies().size(); i++) {
            if (profile.bodies().get(i).parentRigidBodyIndex() >= 0) continue;
            SkeletonRagdoll.Body root = ragdoll.bodies().get(i);
            root.setLinearVelocity(initialState.rootLinearVelocity);
            root.setAngularVelocity(initialState.rootAngularVelocity);
            break;
        }
    }

    public record Simulation(float hertz, int substeps, int solverIterations,
                             float constraintHertz, float constraintDampingRatio,
                             float maxLinearSpeed, float maxAngularSpeed,
                             int maxFixedStepsPerUpdate,
                             Vector3f gravity) {
        public Simulation {
            gravity = new Vector3f(Objects.requireNonNull(gravity, "gravity"));
            if (!Float.isFinite(hertz) || hertz < 10f || hertz > 1000f) {
                throw new IllegalArgumentException("simulation hertz must be within [10, 1000]");
            }
            if (substeps < 1 || substeps > 50) {
                throw new IllegalArgumentException("simulation substeps must be within [1, 50]");
            }
            if (solverIterations < 1 || solverIterations > 128) {
                throw new IllegalArgumentException("solver iterations must be within [1, 128]");
            }
            if (maxFixedStepsPerUpdate < 1 || maxFixedStepsPerUpdate > 1000) {
                throw new IllegalArgumentException("max fixed steps per update must be within [1, 1000]");
            }
            if (!Float.isFinite(constraintHertz) || constraintHertz < 0f
                    || !Float.isFinite(constraintDampingRatio) || constraintDampingRatio < 0f
                    || !Float.isFinite(maxLinearSpeed) || maxLinearSpeed <= 0f
                    || !Float.isFinite(maxAngularSpeed) || maxAngularSpeed <= 0f
                    || !gravity.isFinite()) {
                throw new IllegalArgumentException("simulation values must be finite and constraint tuning non-negative");
            }
        }
        @Override public Vector3f gravity() { return new Vector3f(gravity); }
    }

    public record Collision(boolean enabled, boolean selfCollision, boolean continuous) {}

    public record Environment(boolean enabled, int refreshIntervalTicks, float padding,
                              float friction, float restitution, int maxScannedBlocks) {
        public Environment {
            if (refreshIntervalTicks < 1 || maxScannedBlocks < 1
                    || !Float.isFinite(padding) || padding < 0f
                    || !Float.isFinite(friction) || friction < 0f
                    || !Float.isFinite(restitution) || restitution < 0f || restitution > 1f) {
                throw new IllegalArgumentException("invalid environment collision settings");
            }
        }
    }

    public record InitialState(Vector3f offset, Vector3f rootLinearVelocity,
                               Vector3f rootAngularVelocity) {
        public InitialState {
            offset = new Vector3f(Objects.requireNonNull(offset, "offset"));
            rootLinearVelocity = new Vector3f(Objects.requireNonNull(rootLinearVelocity, "rootLinearVelocity"));
            rootAngularVelocity = new Vector3f(Objects.requireNonNull(rootAngularVelocity, "rootAngularVelocity"));
            if (!offset.isFinite() || !rootLinearVelocity.isFinite() || !rootAngularVelocity.isFinite()) {
                throw new IllegalArgumentException("initial state vectors must be finite");
            }
        }
        @Override public Vector3f offset() { return new Vector3f(offset); }
        @Override public Vector3f rootLinearVelocity() { return new Vector3f(rootLinearVelocity); }
        @Override public Vector3f rootAngularVelocity() { return new Vector3f(rootAngularVelocity); }
    }

    public record Dragging(boolean enabled, int mouseButton, float maxDistance,
                           DragSettings settings) {
        public Dragging {
            Objects.requireNonNull(settings, "settings");
            if (mouseButton < 0 || mouseButton > 7) {
                throw new IllegalArgumentException("drag mouse button must be within [0, 7]");
            }
            if (!Float.isFinite(maxDistance) || maxDistance <= 0f) {
                throw new IllegalArgumentException("drag max distance must be finite and positive");
            }
        }
    }

    public enum UpdateMode {
        /** Fixed-step accumulation is fed from the independent render-frame clock. */
        RENDER_FRAME,
        /** The owner advances physics explicitly through the public runtime/core API. */
        MANUAL
    }

    public record Sleeping(boolean enabled, float linearSpeed, float angularSpeed,
                           float delaySeconds) {
        public Sleeping {
            if (!Float.isFinite(linearSpeed) || linearSpeed < 0f
                    || !Float.isFinite(angularSpeed) || angularSpeed < 0f
                    || !Float.isFinite(delaySeconds) || delaySeconds < 0f) {
                throw new IllegalArgumentException("sleeping settings must be finite and non-negative");
            }
        }
    }

    private static Vector3f vector(float[] values, String name) {
        if (values == null || values.length != 3) throw new JsonParseException(name + " must contain exactly three numbers");
        return new Vector3f(values);
    }

    private static Vector3f radians(Vector3f degrees) {
        return degrees.mul((float) Math.PI / 180f);
    }

    private static float radians(float degrees) {
        return degrees * (float) Math.PI / 180f;
    }
}
