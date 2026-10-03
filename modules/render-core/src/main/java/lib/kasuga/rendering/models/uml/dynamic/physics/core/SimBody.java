package lib.kasuga.rendering.models.uml.dynamic.physics.core;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

/**
 * Format-agnostic rigid-body data mapped into native Box3D. PMX ragdoll
 * bodies, generated humanoid capsules and arbitrary user-created rigid bodies
 * all implement this, so the native adapter never depends on a model format.
 *
 * <p>The {@code *Ref} accessors return live internal storage used to exchange
 * transforms and velocities with Box3D without allocating every fixed step.
 */
public interface SimBody {
    int SHAPE_SPHERE = 0;
    int SHAPE_BOX = 1;
    int SHAPE_CAPSULE = 2;

    /** PMX/Box3D shape id: {@link #SHAPE_SPHERE}, {@link #SHAPE_BOX} or {@link #SHAPE_CAPSULE}. */
    int shape();

    /** Shape parameters. Sphere/capsule radius in x, capsule height in y, box half extents in xyz. */
    Vector3f shapeSizeRef();

    /**
     * Local-space shapes attached to the native body. Implementations may
     * override this to expose a compound body; the legacy shape fields map to
     * one centered primitive by default.
     */
    default List<BodyShape> collisionShapes() {
        Vector3f size = shapeSizeRef();
        return switch (shape()) {
            case SHAPE_SPHERE -> List.of(new BodyShape.Sphere(Math.abs(size.x)));
            case SHAPE_BOX -> List.of(new BodyShape.Box(new Vector3f(size).absolute()));
            case SHAPE_CAPSULE -> List.of(new BodyShape.Capsule(Math.abs(size.x), Math.abs(size.y)));
            default -> throw new IllegalStateException("unsupported body shape: " + shape());
        };
    }

    float friction();

    float restitution();

    /** Rolling resistance for sphere/capsule contacts. Boxes ignore this value. */
    default float rollingResistance() { return 0f; }

    /** Enables the maintained Box3D humanoid-ragdoll contact/joint tuning. */
    default boolean profiledRagdollBody() { return false; }

    /** True for authored PMX cloth/hair bodies kept beside a generated humanoid profile. */
    default boolean authoredSecondaryBody() { return false; }

    /**
     * Optional negative Box3D group used to suppress contact within one model
     * while allowing bodies owned by other models to collide.
     */
    default int selfCollisionGroup() { return 0; }

    /** Generated humanoid profiles additionally align their inferred joint frames. */
    default boolean ragdollAlignmentSpring() { return false; }

    /** Collision group index (0-15) used with the non-collision mask. */
    int collisionGroup();

    /** Bit mask of groups this body does not collide with. */
    int nonCollisionMask();

    /**
     * True for kinematic bodies: their pose follows an external driver
     * (animation, scripted path) and they carry infinite inertia.
     */
    boolean kinematic();

    /** Per-second rate of first-order linear damping (0 = none). */
    float linearDamping();

    /** Per-second rate of first-order angular damping (0 = none). */
    float angularDamping();

    float inverseLinearMass();

    Vector3f positionRef();

    Vector3f previousPositionRef();

    Quaternionf rotationRef();

    Quaternionf previousRotationRef();

    /** Render-interpolation start pose written at the beginning of every fixed step. */
    Vector3f interpolationPositionRef();

    Quaternionf interpolationRotationRef();

    Vector3f linearVelocityRef();

    Vector3f angularVelocityRef();

}
