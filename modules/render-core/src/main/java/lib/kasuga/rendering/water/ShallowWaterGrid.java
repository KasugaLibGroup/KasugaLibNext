package lib.kasuga.rendering.water;

import java.util.Arrays;

/**
 * Free-surface liquid on a flat bed, in metres and seconds. Conserved quantities are water depth
 * and horizontal momenta (h, hu, hv). A two-dimensional Rusanov finite-volume update uses a CFL
 * limited timestep and periodic boundaries. Unlike a density-volume solver, the simulated depth
 * is the surface itself. The depth-averaged model does not represent overturning waves or spray.
 *
 * <p>Single owner thread. Coordinates are relative to the centre of the grid; no graphics or host
 * dependency. All working storage is reused. See the Clawpack Riemann book's shallow-water chapter.
 */
public final class ShallowWaterGrid {
    private static final float GRAVITY = 9.81f;
    private final int size;
    private final float cellSize, restDepth;
    private float[] depth, mx, mz, nextDepth, nextMx, nextMz;

    public ShallowWaterGrid(int size, float cellSize, float restDepth) {
        if (size < 8 || size > 512 || !positive(cellSize) || !positive(restDepth))
            throw new IllegalArgumentException("Water requires 8..512 cells, finite positive cell size and depth");
        this.size = size;
        this.cellSize = cellSize;
        this.restDepth = restDepth;
        int count = Math.multiplyExact(size, size);
        depth = new float[count]; mx = new float[count]; mz = new float[count];
        nextDepth = new float[count]; nextMx = new float[count]; nextMz = new float[count];
        reset();
    }

    public int size() { return size; }
    public float cellSize() { return cellSize; }
    public float extent() { return (size - 1) * cellSize; }
    public float restDepth() { return restDepth; }
    public float cellDepth(int x, int z) { return depth[z * size + x]; }
    public float height(float x, float z) { return sample(depth, x, z); }
    public float velocityX(float x, float z) { return sample(mx, x, z) / height(x, z); }
    public float velocityZ(float x, float z) { return sample(mz, x, z) / height(x, z); }

    public void reset() {
        Arrays.fill(depth, restDepth);
        Arrays.fill(mx, 0); Arrays.fill(mz, 0);
    }

    /** A submerged foot depresses the centre and redistributes the same volume around it. */
    public void displace(float x, float z, float radius, float amount) {
        requireInteraction(x, z, radius, amount, 0);
        float strength = Math.clamp(amount, -restDepth * .2f, restDepth * .2f);
        float sum = 0;
        float half = extent() * .5f;
        for (int iz = 0; iz < size; iz++) for (int ix = 0; ix < size; ix++) {
            float dx = ix * cellSize - half - x, dz = iz * cellSize - half - z;
            float r2 = (dx * dx + dz * dz) / (radius * radius);
            // The broad positive ring has the same continuous integral as the central depression.
            float delta = strength * ((float) Math.exp(-r2 * .5f) * .25f - (float) Math.exp(-r2 * 2));
            nextDepth[iz * size + ix] = delta;
            sum += delta;
        }
        float correction = sum / depth.length;
        for (int i = 0; i < depth.length; i++) depth[i] += nextDepth[i] - correction;
    }

    /** Transfers horizontal velocity into the water without adding mass. */
    public void impulse(float x, float z, float radius, float vx, float vz) {
        requireInteraction(x, z, radius, vx, vz);
        vx = Math.clamp(vx, -2, 2); vz = Math.clamp(vz, -2, 2);
        float half = extent() * .5f;
        for (int iz = 0; iz < size; iz++) for (int ix = 0; ix < size; ix++) {
            float dx = ix * cellSize - half - x, dz = iz * cellSize - half - z;
            float weight = (float) Math.exp(-2 * (dx * dx + dz * dz) / (radius * radius));
            int i = iz * size + ix;
            mx[i] += depth[i] * weight * vx;
            mz[i] += depth[i] * weight * vz;
        }
    }

    /** Advances the full requested interval, up to one second. Long intervals use adaptive substeps. */
    public void step(float seconds) {
        if (!Float.isFinite(seconds) || seconds < 0 || seconds > 1)
            throw new IllegalArgumentException("Water timestep must be finite and in [0, 1]");
        float remaining = seconds;
        while (remaining > 1e-7f) {
            float maxSpeedX = 0, maxSpeedZ = 0;
            for (int i = 0; i < depth.length; i++) {
                float c = (float) Math.sqrt(GRAVITY * depth[i]);
                maxSpeedX = Math.max(maxSpeedX, Math.abs(mx[i] / depth[i]) + c);
                maxSpeedZ = Math.max(maxSpeedZ, Math.abs(mz[i] / depth[i]) + c);
            }
            float dt = Math.min(remaining, .4f * cellSize / (maxSpeedX + maxSpeedZ));
            advance(dt);
            remaining -= dt;
        }
    }

    private void advance(float dt) {
        System.arraycopy(depth, 0, nextDepth, 0, depth.length);
        System.arraycopy(mx, 0, nextMx, 0, mx.length);
        System.arraycopy(mz, 0, nextMz, 0, mz.length);
        float ratio = dt / cellSize;
        for (int z = 0; z < size; z++) for (int x = 0; x < size; x++) {
            int a = z * size + x;
            flux(a, z * size + (x + 1) % size, true, ratio);
            flux(a, ((z + 1) % size) * size + x, false, ratio);
        }
        float damping = (float) Math.exp(-.16 * dt);
        for (int i = 0; i < depth.length; i++) {
            if (!Float.isFinite(nextDepth[i]) || nextDepth[i] <= 0)
                throw new IllegalStateException("Water lost positive finite depth at cell " + i);
            nextMx[i] *= damping; nextMz[i] *= damping;
        }
        float[] swap = depth; depth = nextDepth; nextDepth = swap;
        swap = mx; mx = nextMx; nextMx = swap;
        swap = mz; mz = nextMz; nextMz = swap;
    }

    private void flux(int a, int b, boolean horizontal, float dtDx) {
        float ha = depth[a], hb = depth[b];
        float ua = (horizontal ? mx[a] : mz[a]) / ha;
        float ub = (horizontal ? mx[b] : mz[b]) / hb;
        float speed = Math.max(Math.abs(ua) + (float) Math.sqrt(GRAVITY * ha),
                Math.abs(ub) + (float) Math.sqrt(GRAVITY * hb));
        float fh = .5f * (ha * ua + hb * ub - speed * (hb - ha)) * dtDx;
        float fx = .5f * (mx[a] * ua + mx[b] * ub - speed * (mx[b] - mx[a])
                + (horizontal ? .5f * GRAVITY * (ha * ha + hb * hb) : 0)) * dtDx;
        float fz = .5f * (mz[a] * ua + mz[b] * ub - speed * (mz[b] - mz[a])
                + (!horizontal ? .5f * GRAVITY * (ha * ha + hb * hb) : 0)) * dtDx;
        nextDepth[a] -= fh; nextDepth[b] += fh;
        nextMx[a] -= fx; nextMx[b] += fx;
        nextMz[a] -= fz; nextMz[b] += fz;
    }

    /** Moves the simulated window; existing waves retain their world position. New edges are at rest. */
    public void shift(int cellsX, int cellsZ) {
        for (int z = 0; z < size; z++) for (int x = 0; x < size; x++) {
            int sx = x + cellsX, sz = z + cellsZ, to = z * size + x;
            if (sx >= 0 && sx < size && sz >= 0 && sz < size) {
                int from = sz * size + sx;
                nextDepth[to] = depth[from]; nextMx[to] = mx[from]; nextMz[to] = mz[from];
            } else {
                nextDepth[to] = restDepth; nextMx[to] = nextMz[to] = 0;
            }
        }
        float[] swap = depth; depth = nextDepth; nextDepth = swap;
        swap = mx; mx = nextMx; nextMx = swap;
        swap = mz; mz = nextMz; nextMz = swap;
    }

    public double volume() {
        double sum = 0; for (float h : depth) sum += h;
        return sum * cellSize * cellSize;
    }
    public float minimumDepth() {
        float min = Float.POSITIVE_INFINITY; for (float h : depth) min = Math.min(min, h); return min;
    }
    public float maximumDeviation() {
        float max = 0; for (float h : depth) max = Math.max(max, Math.abs(h - restDepth)); return max;
    }
    public boolean isFinite() {
        for (int i = 0; i < depth.length; i++)
            if (!Float.isFinite(depth[i]) || depth[i] <= 0 || !Float.isFinite(mx[i]) || !Float.isFinite(mz[i])) return false;
        return true;
    }

    private float sample(float[] values, float x, float z) {
        if (!Float.isFinite(x) || !Float.isFinite(z)) throw new IllegalArgumentException("Non-finite water coordinate");
        float gx = Math.clamp(x / cellSize + (size - 1) * .5f, 0, size - 1);
        float gz = Math.clamp(z / cellSize + (size - 1) * .5f, 0, size - 1);
        int x0 = Math.min((int) gx, size - 2), z0 = Math.min((int) gz, size - 2);
        float tx = gx - x0, tz = gz - z0;
        return (values[z0 * size + x0] * (1 - tx) + values[z0 * size + x0 + 1] * tx) * (1 - tz)
                + (values[(z0 + 1) * size + x0] * (1 - tx) + values[(z0 + 1) * size + x0 + 1] * tx) * tz;
    }
    private static boolean positive(float v) { return Float.isFinite(v) && v > 0; }
    private static void requireInteraction(float x, float z, float radius, float a, float b) {
        if (!Float.isFinite(x) || !Float.isFinite(z) || !positive(radius) || !Float.isFinite(a) || !Float.isFinite(b))
            throw new IllegalArgumentException("Water interaction requires finite coordinates, impulse and positive radius");
    }
}
