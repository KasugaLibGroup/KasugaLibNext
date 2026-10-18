package lib.kasuga.rendering.effect.particle;

import lib.kasuga.rendering.models.uml.math.Transform;
import net.minecraft.client.multiplayer.ClientLevel;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A placeable, continuously emitting source for one particle group.
 *
 * <p>Source parameters are copied into each new particle, so changing a source affects later
 * emissions without retroactively changing particles that are already alive.</p>
 */
public final class ParticleSource implements AutoCloseable {
    private final ParticleGroup target;
    private final AtomicBoolean closed = new AtomicBoolean();
    private Transform transform;
    private float emissionRate;
    private ParticleType particleType;
    private Vector3f initialVelocity;
    private boolean affectedByGravity;
    private Vector3f gravity;
    private Vector4f color;
    private Vector3f size;
    private Vector3f rotation;
    private int lifetimeTicks;
    private ParticleOperator operator;
    private boolean active;
    private double emissionCarry;
    private long emittedCount;

    public ParticleSource(ParticleGroup target, Settings settings) {
        this.target = Objects.requireNonNull(target, "target");
        Settings value = Objects.requireNonNull(settings, "settings");
        transform = value.transform();
        emissionRate = value.emissionRate();
        particleType = value.particleType();
        initialVelocity = value.initialVelocity();
        affectedByGravity = value.affectedByGravity();
        gravity = value.gravity();
        color = value.color();
        size = value.size();
        rotation = value.rotation();
        lifetimeTicks = value.lifetimeTicks();
        operator = value.operator();
        active = value.active();
    }

    /** Emits according to the configured particles-per-tick density. */
    public void update(ClientLevel level) {
        List<SpawnPlan> plans;
        synchronized (this) {
            if (closed.get() || !active || emissionRate == 0) return;
            emissionCarry += emissionRate;
            int count = (int) Math.floor(emissionCarry);
            emissionCarry -= count;
            if (count == 0) return;
            plans = plans(count);
        }
        plans.forEach(this::spawn);
    }

    /** Emits an immediate burst independently of the continuous emission rate. */
    public List<ParticleHandle> burst(int count) {
        if (count < 0) throw new IllegalArgumentException("count must be non-negative");
        List<SpawnPlan> plans;
        synchronized (this) {
            requireOpen();
            plans = plans(count);
        }
        List<ParticleHandle> result = new ArrayList<>(count);
        plans.forEach(plan -> result.add(spawn(plan)));
        return List.copyOf(result);
    }

    public synchronized Transform transform() {
        return transform.copy();
    }

    public synchronized ParticleSource transform(Transform value) {
        requireOpen();
        transform = Objects.requireNonNull(value, "value").copy();
        return this;
    }

    public synchronized ParticleSource position(Vector3f value) {
        requireOpen();
        transform.setPosition(Objects.requireNonNull(value, "value"));
        return this;
    }

    public synchronized float emissionRate() {
        return emissionRate;
    }

    public synchronized ParticleSource emissionRate(float particlesPerTick) {
        requireOpen();
        emissionRate = requireNonNegative(particlesPerTick, "emissionRate");
        return this;
    }

    public synchronized ParticleSource particleType(ParticleType value) {
        requireOpen();
        particleType = Objects.requireNonNull(value, "value");
        return this;
    }

    public synchronized ParticleSource initialVelocity(Vector3f value) {
        requireOpen();
        initialVelocity = finiteCopy(value, "initialVelocity");
        return this;
    }

    public synchronized ParticleSource affectedByGravity(boolean value) {
        requireOpen();
        affectedByGravity = value;
        return this;
    }

    public synchronized ParticleSource gravity(Vector3f value) {
        requireOpen();
        gravity = finiteCopy(value, "gravity");
        return this;
    }

    public synchronized ParticleSource color(Vector4f value) {
        requireOpen();
        color = finiteCopy(value, "color");
        return this;
    }

    public synchronized ParticleSource size(float value) {
        return size(new Vector3f(value));
    }

    public synchronized ParticleSource size(Vector3f value) {
        requireOpen();
        size = positiveCopy(value, "size");
        return this;
    }

    public synchronized ParticleSource rotation(Vector3f radians) {
        requireOpen();
        rotation = finiteCopy(radians, "rotation");
        return this;
    }

    public synchronized ParticleSource lifetimeTicks(int value) {
        requireOpen();
        lifetimeTicks = requirePositive(value, "lifetimeTicks");
        return this;
    }

    public synchronized ParticleSource operator(ParticleOperator value) {
        requireOpen();
        operator = Objects.requireNonNull(value, "value");
        return this;
    }

    public synchronized ParticleSource active(boolean value) {
        requireOpen();
        active = value;
        return this;
    }

    public synchronized boolean active() {
        return active && !closed.get();
    }

    public synchronized long emittedCount() {
        return emittedCount;
    }

    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public synchronized void close() {
        active = false;
        closed.set(true);
        emissionCarry = 0;
    }

    private List<SpawnPlan> plans(int count) {
        List<SpawnPlan> plans = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            Transform particleTransform = transform.copy()
                    .rotate(rotation, false)
                    .scale(size.x, size.y, size.z);
            plans.add(new SpawnPlan(
                    particleTransform,
                    new Vector3f(initialVelocity),
                    new Vector4f(color),
                    emittedCount++,
                    particleType,
                    affectedByGravity,
                    new Vector3f(gravity),
                    lifetimeTicks,
                    operator
            ));
        }
        return plans;
    }

    private ParticleHandle spawn(SpawnPlan plan) {
        Spawn spawn = new Spawn(plan.transform, plan.velocity, plan.color, plan.sequence);
        ParticleInstance instance = Objects.requireNonNull(
                plan.particleType.create(spawn), "particleType result"
        );
        instance.behavior(new SourceBehavior(
                plan.affectedByGravity,
                plan.gravity,
                plan.lifetimeTicks,
                plan.operator
        ));
        return target.add(instance);
    }

    private void requireOpen() {
        if (closed.get()) throw new IllegalStateException("Particle source is closed");
    }

    private record SpawnPlan(
            Transform transform,
            Vector3f velocity,
            Vector4f color,
            long sequence,
            ParticleType particleType,
            boolean affectedByGravity,
            Vector3f gravity,
            int lifetimeTicks,
            ParticleOperator operator
    ) {
    }

    private static final class SourceBehavior implements ParticleBehavior {
        private final boolean affectedByGravity;
        private final Vector3f gravity;
        private final int lifetimeTicks;
        private final ParticleOperator operator;
        private int ageTicks;

        private SourceBehavior(
                boolean affectedByGravity,
                Vector3f gravity,
                int lifetimeTicks,
                ParticleOperator operator
        ) {
            this.affectedByGravity = affectedByGravity;
            this.gravity = gravity;
            this.lifetimeTicks = lifetimeTicks;
            this.operator = operator;
        }

        @Override
        public ParticleUpdate update(
                ParticleSnapshot particle,
                ParticleGroupSnapshot group,
                ClientLevel level
        ) {
            ageTicks++;
            if (ageTicks >= lifetimeTicks) return ParticleUpdate.remove(particle);
            ParticleState state = ParticleState.from(particle, ageTicks, lifetimeTicks);
            if (affectedByGravity) {
                state = state.withVelocity(state.velocity().add(gravity));
            }
            ParticleState result = Objects.requireNonNull(
                    operator.apply(state), "particle operator result"
            );
            return result.toUpdate(particle);
        }
    }

    @FunctionalInterface
    public interface ParticleType {
        ParticleType DEFAULT = spawn -> ParticleInstance.builder(spawn.transform())
                .velocity(spawn.velocity())
                .color(spawn.color())
                .build();

        ParticleInstance create(Spawn spawn);
    }

    /**
     * Immutable-by-copy parameters supplied to a custom particle type factory.
     *
     * <p>The default type uses these values directly. A custom type may vary them per emission,
     * for example to add position, velocity, size or color noise.</p>
     */
    public static final class Spawn {
        private final Transform transform;
        private final Vector3f velocity;
        private final Vector4f color;
        private final long sequence;

        private Spawn(Transform transform, Vector3f velocity, Vector4f color, long sequence) {
            this.transform = transform.copy();
            this.velocity = new Vector3f(velocity);
            this.color = new Vector4f(color);
            this.sequence = sequence;
        }

        public Transform transform() {
            return transform.copy();
        }

        public Vector3f velocity() {
            return new Vector3f(velocity);
        }

        public Vector4f color() {
            return new Vector4f(color);
        }

        public long sequence() {
            return sequence;
        }
    }

    public static final class Settings {
        private final Transform transform;
        private final float emissionRate;
        private final ParticleType particleType;
        private final Vector3f initialVelocity;
        private final boolean affectedByGravity;
        private final Vector3f gravity;
        private final Vector4f color;
        private final Vector3f size;
        private final Vector3f rotation;
        private final int lifetimeTicks;
        private final ParticleOperator operator;
        private final boolean active;

        private Settings(Builder builder) {
            transform = builder.transform.copy();
            emissionRate = builder.emissionRate;
            particleType = builder.particleType;
            initialVelocity = new Vector3f(builder.initialVelocity);
            affectedByGravity = builder.affectedByGravity;
            gravity = new Vector3f(builder.gravity);
            color = new Vector4f(builder.color);
            size = new Vector3f(builder.size);
            rotation = new Vector3f(builder.rotation);
            lifetimeTicks = builder.lifetimeTicks;
            operator = builder.operator;
            active = builder.active;
        }

        public static Builder builder() {
            return new Builder();
        }

        public Transform transform() {
            return transform.copy();
        }

        public float emissionRate() {
            return emissionRate;
        }

        public ParticleType particleType() {
            return particleType;
        }

        public Vector3f initialVelocity() {
            return new Vector3f(initialVelocity);
        }

        public boolean affectedByGravity() {
            return affectedByGravity;
        }

        public Vector3f gravity() {
            return new Vector3f(gravity);
        }

        public Vector4f color() {
            return new Vector4f(color);
        }

        public Vector3f size() {
            return new Vector3f(size);
        }

        public Vector3f rotation() {
            return new Vector3f(rotation);
        }

        public int lifetimeTicks() {
            return lifetimeTicks;
        }

        public ParticleOperator operator() {
            return operator;
        }

        public boolean active() {
            return active;
        }
    }

    public static final class Builder {
        private Transform transform = new Transform();
        private float emissionRate = 1;
        private ParticleType particleType = ParticleType.DEFAULT;
        private Vector3f initialVelocity = new Vector3f();
        private boolean affectedByGravity;
        private Vector3f gravity = new Vector3f(0, -0.04f, 0);
        private Vector4f color = new Vector4f(1);
        private Vector3f size = new Vector3f(1);
        private Vector3f rotation = new Vector3f();
        private int lifetimeTicks = 100;
        private ParticleOperator operator = ParticleOperators.integrate();
        private boolean active = true;

        private Builder() {
        }

        public Builder transform(Transform value) {
            transform = Objects.requireNonNull(value, "value").copy();
            return this;
        }

        public Builder position(Vector3f value) {
            transform.setPosition(Objects.requireNonNull(value, "value"));
            return this;
        }

        public Builder emissionRate(float particlesPerTick) {
            emissionRate = requireNonNegative(particlesPerTick, "emissionRate");
            return this;
        }

        public Builder particleType(ParticleType value) {
            particleType = Objects.requireNonNull(value, "value");
            return this;
        }

        public Builder initialVelocity(Vector3f value) {
            initialVelocity = finiteCopy(value, "initialVelocity");
            return this;
        }

        public Builder affectedByGravity(boolean value) {
            affectedByGravity = value;
            return this;
        }

        public Builder gravity(Vector3f value) {
            gravity = finiteCopy(value, "gravity");
            return this;
        }

        public Builder color(Vector4f value) {
            color = finiteCopy(value, "color");
            return this;
        }

        public Builder size(float value) {
            return size(new Vector3f(value));
        }

        public Builder size(Vector3f value) {
            size = positiveCopy(value, "size");
            return this;
        }

        public Builder rotation(Vector3f radians) {
            rotation = finiteCopy(radians, "rotation");
            return this;
        }

        public Builder lifetimeTicks(int value) {
            lifetimeTicks = requirePositive(value, "lifetimeTicks");
            return this;
        }

        public Builder operator(ParticleOperator value) {
            operator = Objects.requireNonNull(value, "value");
            return this;
        }

        public Builder active(boolean value) {
            active = value;
            return this;
        }

        public Settings build() {
            return new Settings(this);
        }
    }

    private static float requireNonNegative(float value, String name) {
        if (!Float.isFinite(value) || value < 0) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
        return value;
    }

    private static int requirePositive(int value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static Vector3f finiteCopy(Vector3f value, String name) {
        Vector3f result = new Vector3f(Objects.requireNonNull(value, name));
        if (!result.isFinite()) {
            throw new IllegalArgumentException(name + " must contain only finite values");
        }
        return result;
    }

    private static Vector4f finiteCopy(Vector4f value, String name) {
        Vector4f result = new Vector4f(Objects.requireNonNull(value, name));
        if (!result.isFinite()) {
            throw new IllegalArgumentException(name + " must contain only finite values");
        }
        return result;
    }

    private static Vector3f positiveCopy(Vector3f value, String name) {
        Vector3f result = finiteCopy(value, name);
        if (result.x <= 0 || result.y <= 0 || result.z <= 0) {
            throw new IllegalArgumentException(name + " components must be positive");
        }
        return result;
    }
}
