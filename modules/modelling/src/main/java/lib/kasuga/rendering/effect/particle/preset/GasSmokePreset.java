package lib.kasuga.rendering.effect.particle.preset;

import lib.kasuga.rendering.effect.particle.ParticleGroupBehavior;
import lib.kasuga.rendering.effect.particle.ParticleBufferGroupBehavior;
import lib.kasuga.rendering.effect.particle.ParticleInstance;
import lib.kasuga.rendering.effect.particle.ParticleInstanceBuffer;
import lib.kasuga.rendering.effect.particle.ParticleSnapshot;
import lib.kasuga.rendering.effect.particle.ParticleUpdate;
import lib.kasuga.rendering.effect.particle.fluid.StableFluidGrid3D;
import lib.kasuga.rendering.effect.particle.fluid.FluidEnvironment3D;
import lib.kasuga.rendering.models.uml.math.Transform;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.joml.Matrix4f;

import java.util.Objects;

/**
 * Smoke visualization advected by an incompressible density/velocity grid with buoyancy.
 *
 * <p>Particles are passive visual tracers; gas dynamics live in the shared group controller.</p>
 */
public final class GasSmokePreset {
    private static final int AGE = 0;
    private static final int LIFETIME = 1;
    private static final int BASE_SCALE = 2;
    private static final int BASE_ALPHA = 3;
    private static final int ATTRIBUTE_COUNT = 4;

    private final Settings settings;
    private final StableFluidGrid3D fluid;
    private final Vector3f center = new Vector3f();
    private FluidEnvironment3D environment = FluidEnvironment3D.EMPTY;

    public GasSmokePreset(Settings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        fluid = new StableFluidGrid3D(settings.gridSize);
    }

    public synchronized void center(Vector3f value) {
        center.set(Objects.requireNonNull(value, "value"));
    }

    public synchronized Vector3f center() {
        return new Vector3f(center);
    }

    public synchronized void clearFluid() {
        fluid.clear();
    }

    public synchronized void environment(FluidEnvironment3D value) {
        environment = Objects.requireNonNull(value, "value");
    }

    public synchronized void inject(Vector3f worldPosition, float density, Vector3f velocity) {
        Objects.requireNonNull(worldPosition, "worldPosition");
        Objects.requireNonNull(velocity, "velocity");
        Vector3f local = worldToGrid(worldPosition, new Vector3f());
        if (!inside(local)) return;
        fluid.addDensity(local.x, local.y, local.z, density);
        fluid.addVelocity(
                local.x, local.y, local.z,
                velocity.x / (settings.halfExtents.x * 2.0f),
                velocity.y / (settings.halfExtents.y * 2.0f),
                velocity.z / (settings.halfExtents.z * 2.0f)
        );
    }

    public ParticleInstance createTracer(Vector3f position, float scale, Vector4f color) {
        if (!(scale > 0)) throw new IllegalArgumentException("Smoke tracer scale must be positive");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(color, "color");
        return ParticleInstance.builder(new Transform().translate(position).scale(scale, scale, scale))
                .color(color)
                .attributes(0, settings.lifetimeTicks, scale, color.w)
                .build();
    }

    public ParticleGroupBehavior controller() {
        Vector3f local = new Vector3f();
        Vector3f flow = new Vector3f();
        Vector3f position = new Vector3f();
        return (group, updates, level) -> {
            synchronized (GasSmokePreset.this) {
                fluid.applyBuoyancy(settings.timeStep, settings.buoyancy);
                fluid.step(settings.timeStep, settings.fluid, environment);
                for (ParticleSnapshot particle : group.instances()) {
                    requireAttributes(particle);
                    float age = particle.attribute(AGE) + 1;
                    float lifetime = particle.attribute(LIFETIME);
                    position.set(particle.position());
                    worldToGrid(position, local);
                    if (age >= lifetime || !inside(local)) {
                        updates.submit(particle.id(), ParticleUpdate.remove(particle));
                        continue;
                    }

                    fluid.sampleVelocity(local.x, local.y, local.z, flow);
                    position.add(
                            flow.x * settings.halfExtents.x * 2.0f * settings.timeStep,
                            flow.y * settings.halfExtents.y * 2.0f * settings.timeStep,
                            flow.z * settings.halfExtents.z * 2.0f * settings.timeStep
                    );
                    float density = fluid.sampleDensity(local.x, local.y, local.z);
                    float progress = age / lifetime;
                    float scale = particle.attribute(BASE_SCALE)
                            * (1.0f + settings.expansion * progress);
                    float fade = 1.0f - progress;
                    Vector4f color = particle.color();
                    color.w = particle.attribute(BASE_ALPHA) * fade * fade
                            * clamp(density * settings.densityOpacity + 0.12f, 0, 1);
                    float[] attributes = particle.attributes();
                    attributes[AGE] = age;

                    updates.submit(particle.id(), ParticleUpdate.keep(particle)
                            .withTransform(new Transform().translate(position).scale(scale, scale, scale))
                            .withVelocity(flow)
                            .withColor(color)
                            .withAttributes(attributes));
                }
            }
        };
    }

    /** Direct-buffer controller used by high-count runtime groups without per-tracer update objects. */
    public ParticleBufferGroupBehavior bufferController() {
        Vector3f local = new Vector3f();
        Vector3f flow = new Vector3f();
        Vector3f position = new Vector3f();
        Vector4f color = new Vector4f();
        Matrix4f matrix = new Matrix4f();
        return (current, next, level) -> {
            synchronized (GasSmokePreset.this) {
                fluid.applyBuoyancy(settings.timeStep, settings.buoyancy);
                fluid.step(settings.timeStep, settings.fluid, environment);
                for (int index = 0; index < current.size(); index++) {
                    requireAttributes(current, index);
                    float age = current.attribute(index, AGE) + 1;
                    float lifetime = current.attribute(index, LIFETIME);
                    current.position(index, position);
                    worldToGrid(position, local);
                    if (age >= lifetime || !inside(local)) {
                        next.remove(index);
                        continue;
                    }
                    fluid.sampleVelocity(local.x, local.y, local.z, flow);
                    position.add(
                            flow.x * settings.halfExtents.x * 2.0f * settings.timeStep,
                            flow.y * settings.halfExtents.y * 2.0f * settings.timeStep,
                            flow.z * settings.halfExtents.z * 2.0f * settings.timeStep
                    );
                    float density = fluid.sampleDensity(local.x, local.y, local.z);
                    float progress = age / lifetime;
                    float scale = current.attribute(index, BASE_SCALE)
                            * (1.0f + settings.expansion * progress);
                    current.color(index, color);
                    color.w = current.attribute(index, BASE_ALPHA) * (1.0f - progress)
                            * (1.0f - progress)
                            * clamp(density * settings.densityOpacity + 0.12f, 0, 1);
                    next.setMatrix(index, matrix.identity().translate(position).scale(scale));
                    next.setVelocity(index, flow);
                    next.setColor(index, color);
                    next.attribute(index, AGE, age);
                }
            }
        };
    }

    private Vector3f worldToGrid(Vector3f world, Vector3f destination) {
        return destination.set(
                (world.x - center.x) / (settings.halfExtents.x * 2.0f) + 0.5f,
                (world.y - center.y) / (settings.halfExtents.y * 2.0f) + 0.5f,
                (world.z - center.z) / (settings.halfExtents.z * 2.0f) + 0.5f
        );
    }

    private static boolean inside(Vector3f value) {
        return value.x >= 0 && value.x <= 1
                && value.y >= 0 && value.y <= 1
                && value.z >= 0 && value.z <= 1;
    }

    private static void requireAttributes(ParticleSnapshot particle) {
        if (particle.attributeCount() != ATTRIBUTE_COUNT) {
            throw new IllegalArgumentException(
                    "Gas smoke instance " + particle.id() + " requires "
                            + ATTRIBUTE_COUNT + " attributes"
            );
        }
    }

    private static void requireAttributes(ParticleInstanceBuffer particles, int index) {
        if (particles.attributeCount(index) != ATTRIBUTE_COUNT) {
            throw new IllegalArgumentException(
                    "Gas smoke instance " + particles.id(index) + " requires "
                            + ATTRIBUTE_COUNT + " attributes"
            );
        }
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    public record Settings(
            int gridSize,
            Vector3f halfExtents,
            int lifetimeTicks,
            float timeStep,
            float buoyancy,
            float densityOpacity,
            float expansion,
            StableFluidGrid3D.Settings fluid
    ) {
        public Settings {
            if (gridSize < 4) throw new IllegalArgumentException("gridSize must be at least 4");
            halfExtents = new Vector3f(Objects.requireNonNull(halfExtents, "halfExtents"));
            if (halfExtents.x <= 0 || halfExtents.y <= 0 || halfExtents.z <= 0) {
                throw new IllegalArgumentException("halfExtents must be positive");
            }
            if (lifetimeTicks <= 0) throw new IllegalArgumentException("lifetimeTicks must be positive");
            positive(timeStep, "timeStep");
            finite(buoyancy, "buoyancy");
            nonNegative(densityOpacity, "densityOpacity");
            nonNegative(expansion, "expansion");
            Objects.requireNonNull(fluid, "fluid");
        }

        @Override
        public Vector3f halfExtents() {
            return new Vector3f(halfExtents);
        }

        public static Settings defaults() {
            return new Settings(
                    20,
                    new Vector3f(5, 7, 5),
                    120,
                    0.075f,
                    0.32f,
                    0.65f,
                    2.2f,
                    new StableFluidGrid3D.Settings(0.00018f, 0.00012f, 0.985f, 0.994f, 8)
            );
        }
    }

    private static void positive(float value, String name) {
        if (!Float.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException(name + " must be finite and positive");
        }
    }

    private static void nonNegative(float value, String name) {
        if (!Float.isFinite(value) || value < 0) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }

    private static void finite(float value, String name) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
    }
}
