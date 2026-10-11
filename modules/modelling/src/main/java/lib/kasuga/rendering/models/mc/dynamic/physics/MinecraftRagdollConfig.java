package lib.kasuga.rendering.models.mc.dynamic.physics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
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
            return fromJson(com.google.gson.JsonParser.parseReader(reader).getAsJsonObject());
        } catch (RuntimeException exception) {
            throw new IOException("Invalid ragdoll config: " + location, exception);
        }
    }

    public static MinecraftRagdollConfig fromJson(JsonObject root) {
        Objects.requireNonNull(root, "root");
        JsonArray bodyArray = requiredArray(root, "bodies");
        if (bodyArray.isEmpty()) throw new JsonParseException("bodies must not be empty");
        Map<SkeletonRagdoll.BodyRole, SkeletonRagdoll.SwingTwistLimit> roleLimits = roleLimits(root);
        List<SkeletonRagdoll.Registration> registrations = new ArrayList<>(bodyArray.size());
        for (JsonElement element : bodyArray) {
            if (!element.isJsonObject()) throw new JsonParseException("body entry must be an object");
            JsonObject body = element.getAsJsonObject();
            int rigidBody = requiredInt(body, "rigid_body");
            int parent = integer(body, "parent", -1);
            SkeletonRagdoll.BodyRole role;
            try {
                role = SkeletonRagdoll.BodyRole.valueOf(requiredString(body, "role")
                        .toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new JsonParseException("Unknown body role in entry " + rigidBody, exception);
            }
            JsonElement minimum = body.get("rotation_min_degrees");
            JsonElement maximum = body.get("rotation_max_degrees");
            if ((minimum == null) != (maximum == null)) {
                throw new JsonParseException("custom rotation limits require both min and max");
            }
            boolean hasSwing = body.has("max_swing_degrees")
                    || body.has("min_twist_degrees") || body.has("max_twist_degrees");
            if (hasSwing && (minimum != null || maximum != null)) {
                throw new JsonParseException("body limit must use either swing/twist or Euler fields");
            }
            if (hasSwing) {
                if (!body.has("max_swing_degrees") || !body.has("min_twist_degrees")
                        || !body.has("max_twist_degrees")) {
                    throw new JsonParseException("swing/twist limit requires max swing and both twist bounds");
                }
                registrations.add(new SkeletonRagdoll.Registration(rigidBody, parent, role,
                        new SkeletonRagdoll.SwingTwistLimit(
                                radians(requiredDecimal(body, "max_swing_degrees")),
                                radians(requiredDecimal(body, "min_twist_degrees")),
                                radians(requiredDecimal(body, "max_twist_degrees")),
                                decimal(body, "limit_stiffness", 0.58f))));
            } else if (minimum == null) {
                SkeletonRagdoll.SwingTwistLimit roleLimit = roleLimits.get(role);
                registrations.add(roleLimit == null
                        ? new SkeletonRagdoll.Registration(rigidBody, parent, role)
                        : new SkeletonRagdoll.Registration(rigidBody, parent, role, roleLimit));
            } else {
                registrations.add(new SkeletonRagdoll.Registration(rigidBody, parent, role,
                        radians(vector(minimum, "rotation_min_degrees")),
                        radians(vector(maximum, "rotation_max_degrees"))));
            }
        }

        JsonObject simulation = object(root, "simulation");
        JsonObject collision = object(root, "collision");
        JsonObject environment = object(root, "environment");
        JsonObject initial = object(root, "initial_state");
        JsonObject dragging = object(root, "dragging");
        JsonObject sleeping = object(root, "sleeping");
        return new MinecraftRagdollConfig(
                new SkeletonRagdoll.Profile(registrations,
                        bool(root, "include_secondary_bodies", false)),
                new Simulation(
                        decimal(simulation, "hertz", 120f),
                        integer(simulation, "substeps", RigidBodyWorld.PROFILE_SUBSTEP_COUNT),
                        integer(simulation, "solver_iterations", 12),
                        decimal(simulation, "constraint_hertz", 60f),
                        decimal(simulation, "constraint_damping_ratio", 2f),
                        decimal(simulation, "max_linear_speed", 100f),
                        decimal(simulation, "max_angular_speed", 50f),
                        integer(simulation, "max_fixed_steps_per_update", 12),
                        vector(simulation, "gravity", new Vector3f(0f, -9.80665f, 0f))),
                new Collision(
                        bool(collision, "enabled", true),
                        bool(collision, "self_collision", false),
                        bool(collision, "continuous", true)),
                new Environment(
                        bool(environment, "enabled", true),
                        integer(environment, "refresh_interval_ticks", 1),
                        decimal(environment, "padding", 0.25f),
                        decimal(environment, "friction", 0.8f),
                        decimal(environment, "restitution", 0f),
                        integer(environment, "max_scanned_blocks", 4096)),
                new InitialState(
                        vector(initial, "offset", new Vector3f()),
                        vector(initial, "root_linear_velocity", new Vector3f()),
                        vector(initial, "root_angular_velocity", new Vector3f())),
                new Dragging(
                        bool(dragging, "enabled", true),
                        integer(dragging, "mouse_button", 0),
                        decimal(dragging, "max_distance", 16f),
                        new DragSettings(
                                decimal(dragging, "position_slop", 0.002f),
                                decimal(dragging, "position_stiffness", 0.16f),
                                decimal(dragging, "max_position_correction", 0.012f),
                                decimal(dragging, "bias_rate", 7f),
                                decimal(dragging, "max_bias_speed", 4f),
                                decimal(dragging, "max_target_speed", 6f),
                                decimal(dragging, "max_velocity_impulse", 0.65f),
                                decimal(dragging, "relative_linear_damping", 12.643f),
                                decimal(dragging, "relative_angular_damping", 4.899f))),
                updateMode(simulation, "update_mode", UpdateMode.RENDER_FRAME),
                new Sleeping(
                        bool(sleeping, "enabled", true),
                        decimal(sleeping, "linear_speed", 0.04f),
                        decimal(sleeping, "angular_speed", 0.25f),
                        decimal(sleeping, "delay_seconds", 0.75f)));
    }

    private static Map<SkeletonRagdoll.BodyRole, SkeletonRagdoll.SwingTwistLimit> roleLimits(JsonObject root) {
        EnumMap<SkeletonRagdoll.BodyRole, SkeletonRagdoll.SwingTwistLimit> result =
                new EnumMap<>(SkeletonRagdoll.BodyRole.class);
        JsonObject limits = object(root, "limits");
        for (Map.Entry<String, JsonElement> entry : limits.entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                throw new JsonParseException("limit for " + entry.getKey() + " must be an object");
            }
            SkeletonRagdoll.BodyRole role;
            try {
                role = SkeletonRagdoll.BodyRole.valueOf(entry.getKey().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new JsonParseException("Unknown body role limit: " + entry.getKey(), exception);
            }
            JsonObject limit = entry.getValue().getAsJsonObject();
            result.put(role, new SkeletonRagdoll.SwingTwistLimit(
                    radians(requiredDecimal(limit, "max_swing_degrees")),
                    radians(requiredDecimal(limit, "min_twist_degrees")),
                    radians(requiredDecimal(limit, "max_twist_degrees")),
                    decimal(limit, "limit_stiffness", 0.7f)));
        }
        return result;
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

    private static JsonObject object(JsonObject parent, String name) {
        JsonElement value = parent.get(name);
        if (value == null) return new JsonObject();
        if (!value.isJsonObject()) throw new JsonParseException(name + " must be an object");
        return value.getAsJsonObject();
    }

    private static JsonArray requiredArray(JsonObject parent, String name) {
        JsonElement value = parent.get(name);
        if (value == null || !value.isJsonArray()) throw new JsonParseException(name + " must be an array");
        return value.getAsJsonArray();
    }

    private static int requiredInt(JsonObject parent, String name) {
        JsonElement value = parent.get(name);
        if (value == null || !value.isJsonPrimitive()) throw new JsonParseException(name + " must be an integer");
        return value.getAsInt();
    }

    private static String requiredString(JsonObject parent, String name) {
        JsonElement value = parent.get(name);
        if (value == null || !value.isJsonPrimitive()) throw new JsonParseException(name + " must be a string");
        return value.getAsString();
    }

    private static int integer(JsonObject parent, String name, int fallback) {
        JsonElement value = parent.get(name);
        return value == null ? fallback : value.getAsInt();
    }

    private static float decimal(JsonObject parent, String name, float fallback) {
        JsonElement value = parent.get(name);
        return value == null ? fallback : value.getAsFloat();
    }

    private static float requiredDecimal(JsonObject parent, String name) {
        JsonElement value = parent.get(name);
        if (value == null || !value.isJsonPrimitive()) {
            throw new JsonParseException(name + " must be a number");
        }
        return value.getAsFloat();
    }

    private static boolean bool(JsonObject parent, String name, boolean fallback) {
        JsonElement value = parent.get(name);
        return value == null ? fallback : value.getAsBoolean();
    }

    private static UpdateMode updateMode(JsonObject parent, String name, UpdateMode fallback) {
        JsonElement value = parent.get(name);
        if (value == null) return fallback;
        try {
            return UpdateMode.valueOf(value.getAsString().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new JsonParseException("Unknown simulation update mode: " + value, exception);
        }
    }

    private static Vector3f vector(JsonObject parent, String name, Vector3f fallback) {
        JsonElement value = parent.get(name);
        return value == null ? new Vector3f(fallback) : vector(value, name);
    }

    private static Vector3f vector(JsonElement element, String name) {
        if (!element.isJsonArray() || element.getAsJsonArray().size() != 3) {
            throw new JsonParseException(name + " must contain exactly three numbers");
        }
        JsonArray array = element.getAsJsonArray();
        return new Vector3f(array.get(0).getAsFloat(), array.get(1).getAsFloat(),
                array.get(2).getAsFloat());
    }

    private static Vector3f radians(Vector3f degrees) {
        return degrees.mul((float) Math.PI / 180f);
    }

    private static float radians(float degrees) {
        return degrees * (float) Math.PI / 180f;
    }
}
