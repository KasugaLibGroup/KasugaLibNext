package lib.kasuga.rendering.models.uml.dynamic.physics.core;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Objects;

/**
 * One local-space collision shape attached to a Box3D body.
 *
 * <p>A body may own any number of these shapes. This is the generic compound
 * shape API used by Minecraft voxel shapes as well as model physics.</p>
 */
public sealed interface BodyShape permits BodyShape.Sphere, BodyShape.Box, BodyShape.Capsule {
    /** Conservative distance from the body origin to the farthest point. */
    float boundingRadius();

    record Sphere(Vector3f center, float radius) implements BodyShape {
        public Sphere {
            center = new Vector3f(Objects.requireNonNull(center, "center"));
            if (!center.isFinite() || !Float.isFinite(radius) || radius <= 0f) {
                throw new IllegalArgumentException("sphere must be finite with a positive radius");
            }
        }

        public Sphere(float radius) {
            this(new Vector3f(), radius);
        }

        @Override public Vector3f center() { return new Vector3f(center); }
        @Override public float boundingRadius() { return center.length() + radius; }
    }

    record Box(Vector3f center, Quaternionf rotation, Vector3f halfExtents) implements BodyShape {
        public Box {
            center = new Vector3f(Objects.requireNonNull(center, "center"));
            rotation = new Quaternionf(Objects.requireNonNull(rotation, "rotation")).normalize();
            halfExtents = new Vector3f(Objects.requireNonNull(halfExtents, "halfExtents")).absolute();
            if (!center.isFinite() || !rotation.isFinite() || !halfExtents.isFinite()
                    || halfExtents.x <= 0f || halfExtents.y <= 0f || halfExtents.z <= 0f) {
                throw new IllegalArgumentException("box transform and half extents must be finite and positive");
            }
        }

        public Box(Vector3f center, Vector3f halfExtents) {
            this(center, new Quaternionf(), halfExtents);
        }

        public Box(Vector3f halfExtents) {
            this(new Vector3f(), new Quaternionf(), halfExtents);
        }

        @Override public Vector3f center() { return new Vector3f(center); }
        @Override public Quaternionf rotation() { return new Quaternionf(rotation); }
        @Override public Vector3f halfExtents() { return new Vector3f(halfExtents); }
        @Override public float boundingRadius() { return center.length() + halfExtents.length(); }
    }

    record Capsule(Vector3f centerA, Vector3f centerB, float radius) implements BodyShape {
        public Capsule {
            centerA = new Vector3f(Objects.requireNonNull(centerA, "centerA"));
            centerB = new Vector3f(Objects.requireNonNull(centerB, "centerB"));
            if (!centerA.isFinite() || !centerB.isFinite()
                    || !Float.isFinite(radius) || radius <= 0f) {
                throw new IllegalArgumentException("capsule must be finite with a positive radius");
            }
        }

        /** Y-aligned capsule whose cylinder section has the supplied full height. */
        public Capsule(float radius, float height) {
            this(new Vector3f(0f, -0.5f * height, 0f),
                    new Vector3f(0f, 0.5f * height, 0f), radius);
            if (!Float.isFinite(height) || height < 0f) {
                throw new IllegalArgumentException("capsule height must be finite and non-negative");
            }
        }

        @Override public Vector3f centerA() { return new Vector3f(centerA); }
        @Override public Vector3f centerB() { return new Vector3f(centerB); }
        @Override public float boundingRadius() {
            return Math.max(centerA.length(), centerB.length()) + radius;
        }
    }
}
