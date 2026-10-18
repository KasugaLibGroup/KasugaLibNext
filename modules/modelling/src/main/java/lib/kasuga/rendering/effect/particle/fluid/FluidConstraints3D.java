package lib.kasuga.rendering.effect.particle.fluid;

import org.joml.Vector3f;

import java.util.Objects;

/** Common allocation-free fluid environment constraints in normalized grid coordinates. */
public final class FluidConstraints3D {
    private FluidConstraints3D() {
    }

    public static FluidConstraint3D solidBox(Vector3f minimum, Vector3f maximum) {
        Bounds bounds = bounds(minimum, maximum);
        CellOperation operation = (grid, x, y, z) -> grid.solid(x, y, z, true);
        return context -> forEach(bounds, context, operation);
    }

    public static FluidConstraint3D solidSphere(Vector3f center, float radius) {
        Vector3f copiedCenter = normalized(center, "center");
        if (!Float.isFinite(radius) || radius <= 0) {
            throw new IllegalArgumentException("radius must be finite and positive");
        }
        float radiusSquared = radius * radius;
        Bounds bounds = bounds(
                new Vector3f(copiedCenter).sub(radius, radius, radius),
                new Vector3f(copiedCenter).add(radius, radius, radius),
                false
        );
        CellOperation operation = (grid, x, y, z) -> {
            float dx = grid.coordinate(x) - copiedCenter.x;
            float dy = grid.coordinate(y) - copiedCenter.y;
            float dz = grid.coordinate(z) - copiedCenter.z;
            if (dx * dx + dy * dy + dz * dz <= radiusSquared) {
                grid.solid(x, y, z, true);
            }
        };
        return context -> forEach(bounds, context, operation);
    }

    /** Adds acceleration to all non-solid cells in a box. */
    public static FluidConstraint3D directionalForce(
            Vector3f minimum,
            Vector3f maximum,
            Vector3f acceleration
    ) {
        Bounds bounds = bounds(minimum, maximum);
        Vector3f copiedAcceleration = finite(acceleration, "acceleration");
        CellOperation operation = (grid, x, y, z) -> {
            if (!grid.solid(x, y, z)) {
                float scale = grid.deltaTime();
                grid.addVelocity(
                        x, y, z,
                        copiedAcceleration.x * scale,
                        copiedAcceleration.y * scale,
                        copiedAcceleration.z * scale
                );
            }
        };
        return context -> forEach(bounds, context, operation);
    }

    /**
     * Maintains a source region. Velocity is assigned directly and density is added per second,
     * making the result stable across simulation time-step changes.
     */
    public static FluidConstraint3D source(
            Vector3f minimum,
            Vector3f maximum,
            float densityPerSecond,
            Vector3f velocity
    ) {
        Bounds bounds = bounds(minimum, maximum);
        if (!Float.isFinite(densityPerSecond) || densityPerSecond < 0) {
            throw new IllegalArgumentException("densityPerSecond must be finite and non-negative");
        }
        Vector3f copiedVelocity = finite(velocity, "velocity");
        CellOperation operation = (grid, x, y, z) -> {
            if (grid.solid(x, y, z)) return;
            float densityDelta = densityPerSecond * grid.deltaTime();
            grid.density(x, y, z, grid.density(x, y, z) + densityDelta);
            grid.velocity(x, y, z, copiedVelocity.x, copiedVelocity.y, copiedVelocity.z);
        };
        return context -> forEach(bounds, context, operation);
    }

    /** Removes density inside a region and optionally pulls neighboring flow toward the outlet. */
    public static FluidConstraint3D drain(
            Vector3f minimum,
            Vector3f maximum,
            float densityRetention,
            Vector3f acceleration
    ) {
        Bounds bounds = bounds(minimum, maximum);
        if (!Float.isFinite(densityRetention) || densityRetention < 0 || densityRetention > 1) {
            throw new IllegalArgumentException("densityRetention must be within [0, 1]");
        }
        Vector3f copiedAcceleration = finite(acceleration, "acceleration");
        CellOperation operation = (grid, x, y, z) -> {
            if (grid.solid(x, y, z)) return;
            grid.density(x, y, z, grid.density(x, y, z) * densityRetention);
            float scale = grid.deltaTime();
            grid.addVelocity(
                    x, y, z,
                    copiedAcceleration.x * scale,
                    copiedAcceleration.y * scale,
                    copiedAcceleration.z * scale
            );
        };
        return context -> forEach(bounds, context, operation);
    }

    private static void forEach(Bounds bounds, FluidConstraintContext3D context, CellOperation operation) {
        int size = context.resolution();
        int minimumX = cell(bounds.minimum.x, size);
        int minimumY = cell(bounds.minimum.y, size);
        int minimumZ = cell(bounds.minimum.z, size);
        int maximumX = cell(bounds.maximum.x, size);
        int maximumY = cell(bounds.maximum.y, size);
        int maximumZ = cell(bounds.maximum.z, size);
        for (int z = minimumZ; z <= maximumZ; z++) {
            for (int y = minimumY; y <= maximumY; y++) {
                for (int x = minimumX; x <= maximumX; x++) {
                    float px = context.coordinate(x);
                    float py = context.coordinate(y);
                    float pz = context.coordinate(z);
                    if (px >= bounds.minimum.x && px <= bounds.maximum.x
                            && py >= bounds.minimum.y && py <= bounds.maximum.y
                            && pz >= bounds.minimum.z && pz <= bounds.maximum.z) {
                        operation.apply(context, x, y, z);
                    }
                }
            }
        }
    }

    private static int cell(float coordinate, int size) {
        return Math.max(0, Math.min(size - 1, (int) (coordinate * size)));
    }

    private static Bounds bounds(Vector3f minimum, Vector3f maximum) {
        return bounds(minimum, maximum, true);
    }

    private static Bounds bounds(Vector3f minimum, Vector3f maximum, boolean requireNormalized) {
        Vector3f min = finite(minimum, "minimum");
        Vector3f max = finite(maximum, "maximum");
        if (min.x > max.x || min.y > max.y || min.z > max.z) {
            throw new IllegalArgumentException("minimum must not exceed maximum");
        }
        if (requireNormalized && (min.x < 0 || min.y < 0 || min.z < 0
                || max.x > 1 || max.y > 1 || max.z > 1)) {
            throw new IllegalArgumentException("constraint bounds must be within [0, 1]");
        }
        min.max(new Vector3f());
        max.min(new Vector3f(1));
        return new Bounds(min, max);
    }

    private static Vector3f normalized(Vector3f value, String name) {
        Vector3f result = finite(value, name);
        if (result.x < 0 || result.x > 1 || result.y < 0 || result.y > 1
                || result.z < 0 || result.z > 1) {
            throw new IllegalArgumentException(name + " must be within [0, 1]");
        }
        return result;
    }

    private static Vector3f finite(Vector3f value, String name) {
        Vector3f result = new Vector3f(Objects.requireNonNull(value, name));
        if (!Float.isFinite(result.x) || !Float.isFinite(result.y) || !Float.isFinite(result.z)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
        return result;
    }

    @FunctionalInterface
    private interface CellOperation {
        void apply(FluidConstraintContext3D context, int x, int y, int z);
    }

    private record Bounds(Vector3f minimum, Vector3f maximum) {
    }
}
