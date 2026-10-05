package lib.kasuga.rendering.models.uml.structure.skeleton;

import org.joml.Vector3f;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Format-neutral, shared definitions. All mutable pose/solver state belongs to an instance. */
public final class SkeletonDynamics {
    public static final SkeletonDynamics EMPTY = new SkeletonDynamics(Physics.EMPTY, List.of(), List.of());

    private final Physics physics;
    private final List<IkChain> ikChains;
    private final List<DiamondConstraint> diamonds;
    private final Map<String, IkChain> chainsByName;
    private final Map<Bone, List<IkChain>> ikChainsByBone;

    public SkeletonDynamics(Physics physics, List<IkChain> ikChains, List<DiamondConstraint> diamonds) {
        this.physics = Objects.requireNonNull(physics, "physics");
        this.ikChains = List.copyOf(ikChains);
        this.diamonds = List.copyOf(diamonds);
        Map<String, IkChain> byName = new java.util.LinkedHashMap<>();
        Map<Bone, List<IkChain>> byBone = new java.util.IdentityHashMap<>();
        for (IkChain chain : this.ikChains) {
            if (byName.put(chain.name(), chain) != null) throw new IllegalArgumentException("duplicate IK name");
            for (IkLink link : chain.links()) {
                byBone.computeIfAbsent(link.bone(), ignored -> new java.util.ArrayList<>()).add(chain);
            }
        }
        byBone.replaceAll((bone, chains) -> List.copyOf(chains));
        chainsByName = java.util.Collections.unmodifiableMap(byName);
        ikChainsByBone = java.util.Collections.unmodifiableMap(byBone);
    }

    public Physics physics() { return physics; }
    public List<IkChain> ikChains() { return ikChains; }
    public List<DiamondConstraint> diamonds() { return diamonds; }
    public Map<String, IkChain> chainsByName() { return chainsByName; }
    /** Reverse mapping of driver bone to every IK chain that controls it, shared by all instances. */
    public Map<Bone, List<IkChain>> ikChainsByBone() { return ikChainsByBone; }

    /** Positions and shape dimensions use unitScale; angular values always use radians. */
    public record Physics(List<RigidBody> bodies, List<Joint> joints, Vector3f unitScale,
                          boolean profileRequired, boolean affineWriteback,
                          float profileRadiusScale, Set<Bone> bindPoseFollowers) {
        public static final Physics EMPTY = new Physics(List.of(), List.of(), new Vector3f(1),
                false, true, 1f, Set.of());
        public Physics {
            bodies = List.copyOf(bodies);
            joints = List.copyOf(joints);
            unitScale = vector(unitScale);
            if (unitScale.x <= 0 || unitScale.y <= 0 || unitScale.z <= 0
                    || !Float.isFinite(profileRadiusScale) || profileRadiusScale <= 0) {
                throw new IllegalArgumentException("physics scales must be positive");
            }
            bindPoseFollowers = Set.copyOf(bindPoseFollowers);
        }
        @Override public Vector3f unitScale() { return new Vector3f(unitScale); }
    }

    /** Bone may be null for an unattached body; indices in profiles address the bodies list. */
    public record RigidBody(String name, String alias, Bone bone, int collisionGroup,
                            int nonCollisionMask, int shape, Vector3f size, Vector3f position,
                            Vector3f rotation, float mass, float linearDamping,
                            float angularDamping, float restitution, float friction, int mode) {
        public static final int SPHERE = 0, BOX = 1, CAPSULE = 2;
        public static final int KINEMATIC = 0, DYNAMIC = 1, DYNAMIC_ROTATION = 2;
        public RigidBody {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(alias, "alias");
            size = vector(size); position = vector(position); rotation = vector(rotation);
            if (shape < SPHERE || shape > CAPSULE || mode < KINEMATIC || mode > DYNAMIC_ROTATION
                    || collisionGroup < 0 || collisionGroup > 63 || size.x < 0 || size.y < 0 || size.z < 0) {
                throw new IllegalArgumentException("invalid rigid body shape, mode, layer or dimensions");
            }
            for (float value : new float[]{mass, linearDamping, angularDamping, restitution, friction}) {
                if (!Float.isFinite(value) || value < 0) throw new IllegalArgumentException("invalid body material");
            }
        }
        @Override public Vector3f size() { return new Vector3f(size); }
        @Override public Vector3f position() { return new Vector3f(position); }
        @Override public Vector3f rotation() { return new Vector3f(rotation); }
    }

    /** Six-axis authored limits; the current Box3D adapter uses spherical cone/twist limits. */
    public record Joint(String name, String alias, int rigidBodyA, int rigidBodyB,
                        Vector3f position, Vector3f rotation, Vector3f positionMin,
                        Vector3f positionMax, Vector3f rotationMin, Vector3f rotationMax,
                        Vector3f positionSpring, Vector3f rotationSpring) {
        public Joint {
            Objects.requireNonNull(name, "name"); Objects.requireNonNull(alias, "alias");
            position = vector(position); rotation = vector(rotation);
            positionMin = vector(positionMin); positionMax = vector(positionMax);
            rotationMin = vector(rotationMin); rotationMax = vector(rotationMax);
            positionSpring = vector(positionSpring); rotationSpring = vector(rotationSpring);
        }
        @Override public Vector3f position() { return new Vector3f(position); }
        @Override public Vector3f rotation() { return new Vector3f(rotation); }
        @Override public Vector3f positionMin() { return new Vector3f(positionMin); }
        @Override public Vector3f positionMax() { return new Vector3f(positionMax); }
        @Override public Vector3f rotationMin() { return new Vector3f(rotationMin); }
        @Override public Vector3f rotationMax() { return new Vector3f(rotationMax); }
        @Override public Vector3f positionSpring() { return new Vector3f(positionSpring); }
        @Override public Vector3f rotationSpring() { return new Vector3f(rotationSpring); }
    }

    public enum TargetMode { POSITION, DIRECTION }

    /** Local Euler bounds relative to the animated/bind pose; radians, XYZ order. */
    public record RotationLimit(Vector3f minimum, Vector3f maximum) {
        public RotationLimit {
            minimum = vector(minimum); maximum = vector(maximum);
            if (minimum.x > maximum.x || minimum.y > maximum.y || minimum.z > maximum.z) {
                throw new IllegalArgumentException("rotation minimum exceeds maximum");
            }
        }
        @Override public Vector3f minimum() { return new Vector3f(minimum); }
        @Override public Vector3f maximum() { return new Vector3f(maximum); }
    }

    public sealed interface IkJoint permits Rotor, Hinge {}
    public record Rotor(float angle) implements IkJoint {
        public Rotor {
            if (!Float.isFinite(angle) || angle < 0 || angle > (float) Math.PI) {
                throw new IllegalArgumentException("rotor angle must be within [0, pi]");
            }
        }
    }
    /** Axis and reference direction are in the driver's uncorrected local frame; radians. */
    public record Hinge(Vector3f axis, Vector3f reference, float minimum, float maximum) implements IkJoint {
        public Hinge {
            axis = vector(axis); reference = vector(reference);
            if (axis.lengthSquared() < 1e-12f || reference.lengthSquared() < 1e-12f
                    || !Float.isFinite(minimum) || !Float.isFinite(maximum)
                    || minimum < -(float) Math.PI || maximum > (float) Math.PI || minimum > maximum) {
                throw new IllegalArgumentException("invalid hinge axes or angles");
            }
            axis.normalize(); reference.normalize();
            if (Math.abs(axis.dot(reference)) > 1e-4f) {
                throw new IllegalArgumentException("hinge axis and reference must be perpendicular");
            }
        }
        @Override public Vector3f axis() { return new Vector3f(axis); }
        @Override public Vector3f reference() { return new Vector3f(reference); }
    }

    public record IkLink(Bone bone, RotationLimit limit, IkJoint joint) {
        public IkLink { Objects.requireNonNull(bone, "bone"); }
        public IkLink(Bone bone) { this(bone, null, null); }
        public IkLink(Bone bone, RotationLimit limit) { this(bone, limit, null); }
    }

    /** Links are ordered from base to tip; effector is the final endpoint bone. */
    public record IkChain(String name, Bone controller, Bone effector, List<IkLink> links,
                          int iterations, float tolerance, float maxRotationStep,
                          boolean replaceAuthoredRotation, TargetMode targetMode) {
        public IkChain {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("empty IK name");
            Objects.requireNonNull(controller, "controller"); Objects.requireNonNull(effector, "effector");
            Objects.requireNonNull(targetMode, "targetMode");
            links = List.copyOf(links);
            if (links.isEmpty() || iterations < 1 || iterations > 256 || !Float.isFinite(tolerance)
                    || tolerance <= 0 || !Float.isFinite(maxRotationStep) || maxRotationStep < 0) {
                throw new IllegalArgumentException("invalid IK solve settings");
            }
        }
        public IkChain(String name, Bone controller, Bone effector, List<IkLink> links) {
            this(name, controller, effector, links, 32, 1e-4f, (float) Math.PI, false, TargetMode.POSITION);
        }
    }

    /** Two independently driven branches whose endpoints meet, forming a closed diamond. */
    public record DiamondConstraint(String name, String firstChain, String secondChain,
                                    int iterations, float tolerance) {
        public DiamondConstraint {
            if (name == null || name.isBlank() || firstChain == null || secondChain == null
                    || firstChain.equals(secondChain) || iterations < 1 || iterations > 256
                    || !Float.isFinite(tolerance) || tolerance <= 0) {
                throw new IllegalArgumentException("invalid diamond constraint");
            }
        }
        public DiamondConstraint(String name, String firstChain, String secondChain) {
            this(name, firstChain, secondChain, 32, 1e-4f);
        }
    }

    private static Vector3f vector(Vector3f value) {
        Vector3f copy = new Vector3f(Objects.requireNonNull(value, "vector"));
        if (!copy.isFinite()) throw new IllegalArgumentException("non-finite vector");
        return copy;
    }
}
