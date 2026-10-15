package lib.kasuga.rendering.effect.particle.fluid;

import org.joml.Vector3f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Objects;

/**
 * Allocation-free 3D stable-fluids solver backed by reusable direct buffers.
 *
 * <p>This is a compact incompressible Navier-Stokes approximation using semi-Lagrangian advection,
 * Gauss-Seidel diffusion and pressure projection. It models a velocity/density volume, not a
 * free-surface liquid.</p>
 */
public final class StableFluidGrid3D {
    /** Controls whether the edge of the finite solver domain behaves like a wall or an outlet. */
    public enum BoundaryMode {
        CLOSED,
        OPEN
    }

    private final int size;
    private final int side;
    private final int cells;
    private final FloatBuffer density;
    private final FloatBuffer densityScratch;
    private final FloatBuffer velocityX;
    private final FloatBuffer velocityY;
    private final FloatBuffer velocityZ;
    private final FloatBuffer velocityScratchX;
    private final FloatBuffer velocityScratchY;
    private final FloatBuffer velocityScratchZ;
    private final FloatBuffer pressure;
    private final FloatBuffer divergence;
    private final ByteBuffer solids;
    private final ConstraintContext constraintContext = new ConstraintContext();
    private BoundaryMode boundaryMode = BoundaryMode.CLOSED;

    public StableFluidGrid3D(int size) {
        if (size < 4) throw new IllegalArgumentException("Fluid grid size must be at least 4");
        this.size = size;
        side = size + 2;
        cells = Math.multiplyExact(Math.multiplyExact(side, side), side);
        density = allocate();
        densityScratch = allocate();
        velocityX = allocate();
        velocityY = allocate();
        velocityZ = allocate();
        velocityScratchX = allocate();
        velocityScratchY = allocate();
        velocityScratchZ = allocate();
        pressure = allocate();
        divergence = allocate();
        solids = ByteBuffer.allocateDirect(cells);
    }

    public int size() {
        return size;
    }

    public void clear() {
        clear(density);
        clear(densityScratch);
        clear(velocityX);
        clear(velocityY);
        clear(velocityZ);
        clear(velocityScratchX);
        clear(velocityScratchY);
        clear(velocityScratchZ);
        clear(pressure);
        clear(divergence);
        clear(solids);
    }

    public void addDensity(float x, float y, float z, float amount) {
        requireNormalized(x, y, z);
        int index = normalizedIndex(x, y, z);
        density.put(index, Math.max(0, density.get(index) + amount));
    }

    public void addVelocity(float x, float y, float z, float vx, float vy, float vz) {
        requireNormalized(x, y, z);
        int index = normalizedIndex(x, y, z);
        velocityX.put(index, velocityX.get(index) + vx);
        velocityY.put(index, velocityY.get(index) + vy);
        velocityZ.put(index, velocityZ.get(index) + vz);
    }

    public void applyBuoyancy(float deltaTime, float strength) {
        if (!Float.isFinite(deltaTime) || deltaTime < 0) {
            throw new IllegalArgumentException("deltaTime must be finite and non-negative");
        }
        if (!Float.isFinite(strength)) throw new IllegalArgumentException("strength must be finite");
        for (int z = 1; z <= size; z++) {
            for (int y = 1; y <= size; y++) {
                for (int x = 1; x <= size; x++) {
                    int index = index(x, y, z);
                    velocityY.put(index, velocityY.get(index) + density.get(index) * strength * deltaTime);
                }
            }
        }
    }

    public void applyGravity(float deltaTime, float acceleration) {
        if (!Float.isFinite(deltaTime) || deltaTime < 0) {
            throw new IllegalArgumentException("deltaTime must be finite and non-negative");
        }
        if (!Float.isFinite(acceleration)) throw new IllegalArgumentException("acceleration must be finite");
        float delta = acceleration * deltaTime;
        for (int z = 1; z <= size; z++) {
            for (int y = 1; y <= size; y++) {
                for (int x = 1; x <= size; x++) {
                    int index = index(x, y, z);
                    if (density.get(index) > 0.001f) {
                        velocityY.put(index, velocityY.get(index) + delta);
                    }
                }
            }
        }
    }

    public float sampleDensity(float x, float y, float z) {
        requireNormalized(x, y, z);
        return sample(density, x, y, z);
    }

    public Vector3f sampleVelocity(float x, float y, float z, Vector3f destination) {
        requireNormalized(x, y, z);
        Objects.requireNonNull(destination, "destination");
        return destination.set(
                sample(velocityX, x, y, z),
                sample(velocityY, x, y, z),
                sample(velocityZ, x, y, z)
        );
    }

    /** Returns whether the nearest interior cell is occupied by the current environment mask. */
    public boolean isSolid(float x, float y, float z) {
        requireNormalized(x, y, z);
        return solid(normalizedIndex(x, y, z));
    }

    public void step(float deltaTime, Settings settings) {
        step(deltaTime, settings, FluidEnvironment3D.EMPTY);
    }

    public void step(float deltaTime, Settings settings, FluidEnvironment3D environment) {
        step(deltaTime, settings, environment, BoundaryMode.CLOSED);
    }

    public void step(
            float deltaTime,
            Settings settings,
            FluidEnvironment3D environment,
            BoundaryMode boundaryMode
    ) {
        if (!Float.isFinite(deltaTime) || deltaTime <= 0) {
            throw new IllegalArgumentException("deltaTime must be finite and positive");
        }
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(environment, "environment");
        this.boundaryMode = Objects.requireNonNull(boundaryMode, "boundaryMode");

        clear(solids);
        constraintContext.deltaTime = deltaTime;
        environment.apply(constraintContext);
        enforceSolid(density);
        enforceSolidVelocity(velocityX, velocityY, velocityZ);

        diffuse(1, velocityScratchX, velocityX, settings.viscosity, deltaTime, settings.iterations);
        diffuse(2, velocityScratchY, velocityY, settings.viscosity, deltaTime, settings.iterations);
        diffuse(3, velocityScratchZ, velocityZ, settings.viscosity, deltaTime, settings.iterations);
        project(
                velocityScratchX, velocityScratchY, velocityScratchZ,
                pressure, divergence, settings.iterations
        );

        advect(1, velocityX, velocityScratchX,
                velocityScratchX, velocityScratchY, velocityScratchZ, deltaTime);
        advect(2, velocityY, velocityScratchY,
                velocityScratchX, velocityScratchY, velocityScratchZ, deltaTime);
        advect(3, velocityZ, velocityScratchZ,
                velocityScratchX, velocityScratchY, velocityScratchZ, deltaTime);
        project(velocityX, velocityY, velocityZ, pressure, divergence, settings.iterations);

        diffuse(0, densityScratch, density, settings.diffusion, deltaTime, settings.iterations);
        advect(0, density, densityScratch, velocityX, velocityY, velocityZ, deltaTime);
        dissipate(density, settings.densityRetention);
        dissipate(velocityX, settings.velocityRetention);
        dissipate(velocityY, settings.velocityRetention);
        dissipate(velocityZ, settings.velocityRetention);
        enforceSolid(density);
        enforceSolidVelocity(velocityX, velocityY, velocityZ);
    }

    /** Mean absolute divergence of the current interior velocity field, intended for diagnostics. */
    public float meanAbsoluteDivergence() {
        float total = 0;
        int count = 0;
        float inverse = 0.5f / size;
        for (int z = 1; z <= size; z++) {
            for (int y = 1; y <= size; y++) {
                for (int x = 1; x <= size; x++) {
                    float value = inverse * (
                            velocityX.get(index(x + 1, y, z)) - velocityX.get(index(x - 1, y, z))
                                    + velocityY.get(index(x, y + 1, z)) - velocityY.get(index(x, y - 1, z))
                                    + velocityZ.get(index(x, y, z + 1)) - velocityZ.get(index(x, y, z - 1))
                    );
                    total += Math.abs(value);
                    count++;
                }
            }
        }
        return count == 0 ? 0 : total / count;
    }

    private void diffuse(int boundary, FloatBuffer output, FloatBuffer input,
                         float diffusion, float deltaTime, int iterations) {
        float coefficient = deltaTime * diffusion * size * size;
        linearSolve(boundary, output, input, coefficient, 1.0f + 6.0f * coefficient, iterations);
    }

    private void linearSolve(int boundary, FloatBuffer output, FloatBuffer input,
                             float coefficient, float divisor, int iterations) {
        for (int iteration = 0; iteration < iterations; iteration++) {
            for (int z = 1; z <= size; z++) {
                for (int y = 1; y <= size; y++) {
                    for (int x = 1; x <= size; x++) {
                        int index = index(x, y, z);
                        if (solid(index)) {
                            output.put(index, 0);
                            continue;
                        }
                        float neighbors =
                                output.get(index(x - 1, y, z)) + output.get(index(x + 1, y, z))
                                        + output.get(index(x, y - 1, z)) + output.get(index(x, y + 1, z))
                                        + output.get(index(x, y, z - 1)) + output.get(index(x, y, z + 1));
                        output.put(index, (input.get(index) + coefficient * neighbors) / divisor);
                    }
                }
            }
            setBoundary(boundary, output);
        }
    }

    private void project(FloatBuffer u, FloatBuffer v, FloatBuffer w,
                         FloatBuffer pressure, FloatBuffer divergence, int iterations) {
        float inverse = -0.5f / size;
        for (int z = 1; z <= size; z++) {
            for (int y = 1; y <= size; y++) {
                for (int x = 1; x <= size; x++) {
                    int index = index(x, y, z);
                    if (solid(index)) {
                        divergence.put(index, 0);
                        pressure.put(index, 0);
                        continue;
                    }
                    divergence.put(index, inverse * (
                            u.get(index(x + 1, y, z)) - u.get(index(x - 1, y, z))
                                    + v.get(index(x, y + 1, z)) - v.get(index(x, y - 1, z))
                                    + w.get(index(x, y, z + 1)) - w.get(index(x, y, z - 1))
                    ));
                    pressure.put(index, 0);
                }
            }
        }
        setBoundary(0, divergence);
        setBoundary(0, pressure);
        linearSolve(0, pressure, divergence, 1, 6, iterations);

        float scale = 0.5f * size;
        for (int z = 1; z <= size; z++) {
            for (int y = 1; y <= size; y++) {
                for (int x = 1; x <= size; x++) {
                    int index = index(x, y, z);
                    u.put(index, u.get(index) - scale * (
                            pressure.get(index(x + 1, y, z)) - pressure.get(index(x - 1, y, z))
                    ));
                    v.put(index, v.get(index) - scale * (
                            pressure.get(index(x, y + 1, z)) - pressure.get(index(x, y - 1, z))
                    ));
                    w.put(index, w.get(index) - scale * (
                            pressure.get(index(x, y, z + 1)) - pressure.get(index(x, y, z - 1))
                    ));
                }
            }
        }
        setBoundary(1, u);
        setBoundary(2, v);
        setBoundary(3, w);
        enforceSolidVelocity(u, v, w);
    }

    private void advect(int boundary, FloatBuffer output, FloatBuffer input,
                        FloatBuffer u, FloatBuffer v, FloatBuffer w, float deltaTime) {
        float scale = deltaTime * size;
        for (int z = 1; z <= size; z++) {
            for (int y = 1; y <= size; y++) {
                for (int x = 1; x <= size; x++) {
                    int index = index(x, y, z);
                    if (solid(index)) {
                        output.put(index, 0);
                        continue;
                    }
                    float sourceX = clamp(x - scale * u.get(index), 0.5f, size + 0.5f);
                    float sourceY = clamp(y - scale * v.get(index), 0.5f, size + 0.5f);
                    float sourceZ = clamp(z - scale * w.get(index), 0.5f, size + 0.5f);
                    output.put(index, sampleGrid(input, sourceX, sourceY, sourceZ));
                }
            }
        }
        setBoundary(boundary, output);
    }

    private float sample(FloatBuffer field, float x, float y, float z) {
        return sampleGrid(
                field,
                0.5f + x * size,
                0.5f + y * size,
                0.5f + z * size
        );
    }

    private float sampleGrid(FloatBuffer field, float x, float y, float z) {
        int x0 = (int) Math.floor(x);
        int x1 = x0 + 1;
        int y0 = (int) Math.floor(y);
        int y1 = y0 + 1;
        int z0 = (int) Math.floor(z);
        int z1 = z0 + 1;
        float tx = x - x0;
        float ty = y - y0;
        float tz = z - z0;
        float x00 = lerp(field.get(index(x0, y0, z0)), field.get(index(x1, y0, z0)), tx);
        float x10 = lerp(field.get(index(x0, y1, z0)), field.get(index(x1, y1, z0)), tx);
        float x01 = lerp(field.get(index(x0, y0, z1)), field.get(index(x1, y0, z1)), tx);
        float x11 = lerp(field.get(index(x0, y1, z1)), field.get(index(x1, y1, z1)), tx);
        return lerp(lerp(x00, x10, ty), lerp(x01, x11, ty), tz);
    }

    private void setBoundary(int boundary, FloatBuffer field) {
        boolean reflectX = boundaryMode == BoundaryMode.CLOSED && boundary == 1;
        boolean reflectY = boundaryMode == BoundaryMode.CLOSED && boundary == 2;
        boolean reflectZ = boundaryMode == BoundaryMode.CLOSED && boundary == 3;
        for (int y = 1; y <= size; y++) {
            for (int z = 1; z <= size; z++) {
                field.put(index(0, y, z), reflectX ? -field.get(index(1, y, z)) : field.get(index(1, y, z)));
                field.put(index(size + 1, y, z), reflectX
                        ? -field.get(index(size, y, z)) : field.get(index(size, y, z)));
            }
        }
        for (int x = 1; x <= size; x++) {
            for (int z = 1; z <= size; z++) {
                field.put(index(x, 0, z), reflectY ? -field.get(index(x, 1, z)) : field.get(index(x, 1, z)));
                field.put(index(x, size + 1, z), reflectY
                        ? -field.get(index(x, size, z)) : field.get(index(x, size, z)));
            }
        }
        for (int x = 1; x <= size; x++) {
            for (int y = 1; y <= size; y++) {
                field.put(index(x, y, 0), reflectZ ? -field.get(index(x, y, 1)) : field.get(index(x, y, 1)));
                field.put(index(x, y, size + 1), reflectZ
                        ? -field.get(index(x, y, size)) : field.get(index(x, y, size)));
            }
        }
        setEdgesAndCorners(field);
    }

    private void setEdgesAndCorners(FloatBuffer field) {
        int high = size + 1;
        for (int value = 1; value <= size; value++) {
            field.put(index(0, 0, value), 0.5f * (
                    field.get(index(1, 0, value)) + field.get(index(0, 1, value))));
            field.put(index(0, high, value), 0.5f * (
                    field.get(index(1, high, value)) + field.get(index(0, size, value))));
            field.put(index(high, 0, value), 0.5f * (
                    field.get(index(size, 0, value)) + field.get(index(high, 1, value))));
            field.put(index(high, high, value), 0.5f * (
                    field.get(index(size, high, value)) + field.get(index(high, size, value))));

            field.put(index(0, value, 0), 0.5f * (
                    field.get(index(1, value, 0)) + field.get(index(0, value, 1))));
            field.put(index(0, value, high), 0.5f * (
                    field.get(index(1, value, high)) + field.get(index(0, value, size))));
            field.put(index(high, value, 0), 0.5f * (
                    field.get(index(size, value, 0)) + field.get(index(high, value, 1))));
            field.put(index(high, value, high), 0.5f * (
                    field.get(index(size, value, high)) + field.get(index(high, value, size))));

            field.put(index(value, 0, 0), 0.5f * (
                    field.get(index(value, 1, 0)) + field.get(index(value, 0, 1))));
            field.put(index(value, 0, high), 0.5f * (
                    field.get(index(value, 1, high)) + field.get(index(value, 0, size))));
            field.put(index(value, high, 0), 0.5f * (
                    field.get(index(value, size, 0)) + field.get(index(value, high, 1))));
            field.put(index(value, high, high), 0.5f * (
                    field.get(index(value, size, high)) + field.get(index(value, high, size))));
        }

        field.put(index(0, 0, 0), field.get(index(1, 1, 1)));
        field.put(index(0, 0, high), field.get(index(1, 1, size)));
        field.put(index(0, high, 0), field.get(index(1, size, 1)));
        field.put(index(0, high, high), field.get(index(1, size, size)));
        field.put(index(high, 0, 0), field.get(index(size, 1, 1)));
        field.put(index(high, 0, high), field.get(index(size, 1, size)));
        field.put(index(high, high, 0), field.get(index(size, size, 1)));
        field.put(index(high, high, high), field.get(index(size, size, size)));
    }

    private void dissipate(FloatBuffer field, float retention) {
        if (retention == 1.0f) return;
        for (int index = 0; index < cells; index++) {
            field.put(index, field.get(index) * retention);
        }
    }

    private void enforceSolid(FloatBuffer field) {
        for (int z = 1; z <= size; z++) {
            for (int y = 1; y <= size; y++) {
                for (int x = 1; x <= size; x++) {
                    int index = index(x, y, z);
                    if (solid(index)) field.put(index, 0);
                }
            }
        }
    }

    private void enforceSolidVelocity(FloatBuffer x, FloatBuffer y, FloatBuffer z) {
        for (int cellZ = 1; cellZ <= size; cellZ++) {
            for (int cellY = 1; cellY <= size; cellY++) {
                for (int cellX = 1; cellX <= size; cellX++) {
                    int index = index(cellX, cellY, cellZ);
                    if (solid(index)) {
                        x.put(index, 0);
                        y.put(index, 0);
                        z.put(index, 0);
                        continue;
                    }
                    float xVelocity = x.get(index);
                    if ((xVelocity > 0 && solid(index(cellX + 1, cellY, cellZ)))
                            || (xVelocity < 0 && solid(index(cellX - 1, cellY, cellZ)))) {
                        x.put(index, 0);
                    }
                    float yVelocity = y.get(index);
                    if ((yVelocity > 0 && solid(index(cellX, cellY + 1, cellZ)))
                            || (yVelocity < 0 && solid(index(cellX, cellY - 1, cellZ)))) {
                        y.put(index, 0);
                    }
                    float zVelocity = z.get(index);
                    if ((zVelocity > 0 && solid(index(cellX, cellY, cellZ + 1)))
                            || (zVelocity < 0 && solid(index(cellX, cellY, cellZ - 1)))) {
                        z.put(index, 0);
                    }
                }
            }
        }
    }

    private boolean solid(int index) {
        return solids.get(index) != 0;
    }

    private int normalizedIndex(float x, float y, float z) {
        return index(
                1 + Math.min(size - 1, (int) (x * size)),
                1 + Math.min(size - 1, (int) (y * size)),
                1 + Math.min(size - 1, (int) (z * size))
        );
    }

    private int index(int x, int y, int z) {
        return x + side * (y + side * z);
    }

    private FloatBuffer allocate() {
        return ByteBuffer.allocateDirect(Math.multiplyExact(cells, Float.BYTES))
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
    }

    private static void clear(FloatBuffer buffer) {
        for (int index = 0; index < buffer.capacity(); index++) buffer.put(index, 0);
    }

    private static void clear(ByteBuffer buffer) {
        for (int index = 0; index < buffer.capacity(); index++) buffer.put(index, (byte) 0);
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static float lerp(float from, float to, float amount) {
        return from + (to - from) * amount;
    }

    private static void requireNormalized(float x, float y, float z) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
                || x < 0 || x > 1 || y < 0 || y > 1 || z < 0 || z > 1) {
            throw new IllegalArgumentException("Fluid coordinates must be finite and within [0, 1]");
        }
    }

    public record Settings(
            float diffusion,
            float viscosity,
            float densityRetention,
            float velocityRetention,
            int iterations
    ) {
        public Settings {
            nonNegative(diffusion, "diffusion");
            nonNegative(viscosity, "viscosity");
            retention(densityRetention, "densityRetention");
            retention(velocityRetention, "velocityRetention");
            if (iterations <= 0) throw new IllegalArgumentException("iterations must be positive");
        }

        private static void nonNegative(float value, String name) {
            if (!Float.isFinite(value) || value < 0) {
                throw new IllegalArgumentException(name + " must be finite and non-negative");
            }
        }

        private static void retention(float value, String name) {
            if (!Float.isFinite(value) || value < 0 || value > 1) {
                throw new IllegalArgumentException(name + " must be within [0, 1]");
            }
        }
    }

    private final class ConstraintContext implements FluidConstraintContext3D {
        private float deltaTime;

        @Override
        public int resolution() {
            return size;
        }

        @Override
        public float deltaTime() {
            return deltaTime;
        }

        @Override
        public float coordinate(int cell) {
            requireCell(cell);
            return (cell + 0.5f) / size;
        }

        @Override
        public float density(int x, int y, int z) {
            return density.get(cellIndex(x, y, z));
        }

        @Override
        public void density(int x, int y, int z, float value) {
            if (!Float.isFinite(value) || value < 0) {
                throw new IllegalArgumentException("density must be finite and non-negative");
            }
            density.put(cellIndex(x, y, z), value);
        }

        @Override
        public Vector3f velocity(int x, int y, int z, Vector3f destination) {
            Objects.requireNonNull(destination, "destination");
            int index = cellIndex(x, y, z);
            return destination.set(velocityX.get(index), velocityY.get(index), velocityZ.get(index));
        }

        @Override
        public void velocity(int x, int y, int z, float xVelocity, float yVelocity, float zVelocity) {
            requireFiniteVelocity(xVelocity, yVelocity, zVelocity);
            int index = cellIndex(x, y, z);
            velocityX.put(index, xVelocity);
            velocityY.put(index, yVelocity);
            velocityZ.put(index, zVelocity);
        }

        @Override
        public void addVelocity(
                int x, int y, int z,
                float xVelocity, float yVelocity, float zVelocity
        ) {
            requireFiniteVelocity(xVelocity, yVelocity, zVelocity);
            int index = cellIndex(x, y, z);
            velocityX.put(index, velocityX.get(index) + xVelocity);
            velocityY.put(index, velocityY.get(index) + yVelocity);
            velocityZ.put(index, velocityZ.get(index) + zVelocity);
        }

        @Override
        public void solid(int x, int y, int z, boolean value) {
            solids.put(cellIndex(x, y, z), value ? (byte) 1 : (byte) 0);
        }

        @Override
        public boolean solid(int x, int y, int z) {
            return StableFluidGrid3D.this.solid(cellIndex(x, y, z));
        }

        private int cellIndex(int x, int y, int z) {
            requireCell(x);
            requireCell(y);
            requireCell(z);
            return index(x + 1, y + 1, z + 1);
        }

        private void requireCell(int cell) {
            if (cell < 0 || cell >= size) {
                throw new IndexOutOfBoundsException("cell=" + cell + ", resolution=" + size);
            }
        }

        private void requireFiniteVelocity(float x, float y, float z) {
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
                throw new IllegalArgumentException("velocity must be finite");
            }
        }
    }
}
