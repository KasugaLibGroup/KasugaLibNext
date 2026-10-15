package lib.kasuga.rendering.effect.particle.preset;

import lib.kasuga.rendering.effect.particle.ParticleGroupBehavior;
import lib.kasuga.rendering.effect.particle.ParticleBufferGroupBehavior;
import lib.kasuga.rendering.effect.particle.ParticleInstance;
import lib.kasuga.rendering.effect.particle.ParticleInstanceBuffer;
import lib.kasuga.rendering.effect.particle.ParticleSnapshot;
import lib.kasuga.rendering.effect.particle.ParticleUpdate;
import lib.kasuga.rendering.effect.particle.fluid.StableFluidGrid3D;
import lib.kasuga.rendering.effect.particle.fluid.FluidEnvironment3D;
import lib.kasuga.rendering.effect.particle.fluid.FluidTracerCollision3D;
import lib.kasuga.rendering.models.uml.math.Transform;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.joml.Matrix4f;

import java.util.Objects;

/**
 * Tracer visualization for a low-viscosity incompressible stable-fluid volume.
 *
 * <p>This deliberately omits a free surface, surface tension and solid coupling; it is a simplified
 * Navier-Stokes flow preset rather than a full liquid solver.</p>
 */
public final class LiquidFlowPreset {
    private static final int SCALE = 0;
    private static final int BASE_ALPHA = 1;
    private static final int ATTRIBUTE_COUNT = 2;

    private final Settings settings;
    private final StableFluidGrid3D fluid;
    private final Vector3f center = new Vector3f();
    private FluidEnvironment3D environment = FluidEnvironment3D.EMPTY;
    private FluidTracerCollision3D tracerCollision = FluidTracerCollision3D.NONE;

    public LiquidFlowPreset(Settings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        fluid = new StableFluidGrid3D(settings.gridSize);
    }

    public synchronized void center(Vector3f value) {
        center.set(Objects.requireNonNull(value, "value"));
    }

    public synchronized void clearFluid() {
        fluid.clear();
    }

    public synchronized void environment(FluidEnvironment3D value) {
        environment = Objects.requireNonNull(value, "value");
    }

    /** Sets world-space collision that remains active after tracers leave the solver volume. */
    public synchronized void tracerCollision(FluidTracerCollision3D value) {
        tracerCollision = Objects.requireNonNull(value, "value");
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
        if (!(scale > 0)) throw new IllegalArgumentException("Liquid tracer scale must be positive");
        return ParticleInstance.builder(new Transform().translate(position).scale(scale, scale, scale))
                .color(color)
                .attributes(scale, color.w)
                .build();
    }

    public ParticleGroupBehavior controller() {
        Vector3f local = new Vector3f();
        Vector3f flow = new Vector3f();
        Vector3f position = new Vector3f();
        Vector3f previousPosition = new Vector3f();
        return (group, updates, level) -> {
            synchronized (LiquidFlowPreset.this) {
                fluid.applyGravity(settings.timeStep, settings.gravity);
                fluid.step(
                        settings.timeStep,
                        settings.fluid,
                        environment,
                        StableFluidGrid3D.BoundaryMode.OPEN
                );
                for (ParticleSnapshot particle : group.instances()) {
                    requireAttributes(particle);
                    position.set(particle.position());
                    worldToGrid(position, local);
                    boolean simulated = inside(local);
                    if (simulated) {
                        fluid.sampleVelocity(local.x, local.y, local.z, flow);
                        gridToWorldVelocity(flow);
                    } else {
                        flow.set(particle.velocity());
                        applyWorldGravity(flow);
                    }
                    previousPosition.set(position);
                    position.fma(settings.timeStep, flow);
                    worldToGrid(position, local);
                    simulated = inside(local);
                    if (simulated && fluid.isSolid(local.x, local.y, local.z)) {
                        position.set(previousPosition);
                        flow.zero();
                    }
                    float scale = particle.attribute(SCALE);
                    tracerCollision.resolve(previousPosition, position, flow, scale * 0.5f);
                    worldToGrid(position, local);
                    simulated = inside(local);
                    Vector4f color = particle.color();
                    if (simulated) {
                        float density = fluid.sampleDensity(local.x, local.y, local.z);
                        color.w = particle.attribute(BASE_ALPHA)
                                * clamp(density * settings.densityOpacity, 0.08f, 1.0f);
                    }
                    updates.submit(particle.id(), ParticleUpdate.keep(particle)
                            .withTransform(new Transform().translate(position).scale(scale, scale, scale))
                            .withVelocity(flow)
                            .withColor(color));
                }
            }
        };
    }

    /** Direct-buffer controller used by dense tracer groups without per-tracer update objects. */
    public ParticleBufferGroupBehavior bufferController() {
        Vector3f local = new Vector3f();
        Vector3f flow = new Vector3f();
        Vector3f position = new Vector3f();
        Vector3f previousPosition = new Vector3f();
        Vector4f color = new Vector4f();
        Matrix4f matrix = new Matrix4f();
        return (current, next, level) -> {
            synchronized (LiquidFlowPreset.this) {
                fluid.applyGravity(settings.timeStep, settings.gravity);
                fluid.step(
                        settings.timeStep,
                        settings.fluid,
                        environment,
                        StableFluidGrid3D.BoundaryMode.OPEN
                );
                for (int index = 0; index < current.size(); index++) {
                    requireAttributes(current, index);
                    current.position(index, position);
                    worldToGrid(position, local);
                    boolean simulated = inside(local);
                    if (simulated) {
                        fluid.sampleVelocity(local.x, local.y, local.z, flow);
                        gridToWorldVelocity(flow);
                    } else {
                        current.velocity(index, flow);
                        applyWorldGravity(flow);
                    }
                    previousPosition.set(position);
                    position.fma(settings.timeStep, flow);
                    worldToGrid(position, local);
                    simulated = inside(local);
                    if (simulated && fluid.isSolid(local.x, local.y, local.z)) {
                        position.set(previousPosition);
                        flow.zero();
                    }
                    float scale = current.attribute(index, SCALE);
                    tracerCollision.resolve(previousPosition, position, flow, scale * 0.5f);
                    worldToGrid(position, local);
                    simulated = inside(local);
                    current.color(index, color);
                    if (simulated) {
                        float density = fluid.sampleDensity(local.x, local.y, local.z);
                        color.w = current.attribute(index, BASE_ALPHA)
                                * clamp(density * settings.densityOpacity, 0.08f, 1.0f);
                    }
                    next.setMatrix(index, matrix.identity().translate(position).scale(scale));
                    next.setVelocity(index, flow);
                    next.setColor(index, color);
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

    private void gridToWorldVelocity(Vector3f velocity) {
        velocity.mul(
                settings.halfExtents.x * 2.0f,
                settings.halfExtents.y * 2.0f,
                settings.halfExtents.z * 2.0f
        );
    }

    private void applyWorldGravity(Vector3f velocity) {
        velocity.y += settings.gravity * settings.halfExtents.y * 2.0f * settings.timeStep;
    }

    private static boolean inside(Vector3f value) {
        return value.x >= 0 && value.x <= 1
                && value.y >= 0 && value.y <= 1
                && value.z >= 0 && value.z <= 1;
    }

    private static void requireAttributes(ParticleSnapshot particle) {
        if (particle.attributeCount() != ATTRIBUTE_COUNT) {
            throw new IllegalArgumentException(
                    "Liquid tracer " + particle.id() + " requires " + ATTRIBUTE_COUNT + " attributes"
            );
        }
    }

    private static void requireAttributes(ParticleInstanceBuffer particles, int index) {
        if (particles.attributeCount(index) != ATTRIBUTE_COUNT) {
            throw new IllegalArgumentException(
                    "Liquid tracer " + particles.id(index) + " requires "
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
            float timeStep,
            float gravity,
            float densityOpacity,
            StableFluidGrid3D.Settings fluid
    ) {
        public Settings {
            if (gridSize < 4) throw new IllegalArgumentException("gridSize must be at least 4");
            halfExtents = new Vector3f(Objects.requireNonNull(halfExtents, "halfExtents"));
            if (halfExtents.x <= 0 || halfExtents.y <= 0 || halfExtents.z <= 0) {
                throw new IllegalArgumentException("halfExtents must be positive");
            }
            if (!Float.isFinite(timeStep) || timeStep <= 0) {
                throw new IllegalArgumentException("timeStep must be finite and positive");
            }
            if (!Float.isFinite(gravity)) throw new IllegalArgumentException("gravity must be finite");
            if (!Float.isFinite(densityOpacity) || densityOpacity < 0) {
                throw new IllegalArgumentException("densityOpacity must be finite and non-negative");
            }
            Objects.requireNonNull(fluid, "fluid");
        }

        @Override
        public Vector3f halfExtents() {
            return new Vector3f(halfExtents);
        }

        public static Settings defaults() {
            return new Settings(
                    18,
                    new Vector3f(6, 4, 6),
                    0.06f,
                    -0.18f,
                    0.8f,
                    new StableFluidGrid3D.Settings(0.00002f, 0.00008f, 0.998f, 0.997f, 10)
            );
        }
    }
}
