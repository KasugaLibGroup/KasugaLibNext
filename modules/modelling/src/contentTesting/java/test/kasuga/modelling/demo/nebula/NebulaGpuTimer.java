package test.kasuga.modelling.demo.nebula;

import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

/** Render-thread-only timer. Busy slots skip a sample; result reads always follow availability. */
final class NebulaGpuTimer implements AutoCloseable {
    private final int[] queries = new int[3];
    private final boolean[] pending = new boolean[3];
    private final long[] epochs = new long[3];
    private int active = -1;
    private boolean enabled;
    private long epoch, samples;
    private double totalMs, maxMs, lastMs;
    private boolean lastDeleteVerified = true;

    void enabled(boolean value) {
        if (value && !enabled) {
            epoch++;
            samples = 0;
            totalMs = maxMs = lastMs = 0;
        }
        enabled = value;
    }

    void begin() {
        if (active >= 0) throw new IllegalStateException("GPU timer already active");
        for (int i = 0; i < queries.length; i++) {
            if (!pending[i] || GL15.glGetQueryObjecti(queries[i], GL15.GL_QUERY_RESULT_AVAILABLE) == 0) continue;
            double elapsed = GL33.glGetQueryObjecti64(queries[i], GL15.GL_QUERY_RESULT) / 1_000_000.0;
            pending[i] = false;
            if (epochs[i] != epoch) continue;
            lastMs = elapsed;
            samples++;
            totalMs += elapsed;
            maxMs = Math.max(maxMs, elapsed);
        }
        if (!enabled) return;
        for (int i = 0; i < queries.length; i++) {
            if (pending[i]) continue;
            if (queries[i] == 0) queries[i] = GL15.glGenQueries();
            active = i;
            epochs[i] = epoch;
            GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, queries[i]);
            return;
        }
    }

    void end() {
        if (active < 0) return;
        GL15.glEndQuery(GL33.GL_TIME_ELAPSED);
        pending[active] = true;
        active = -1;
    }

    long samples() { return samples; }
    double totalMs() { return totalMs; }
    double maxMs() { return maxMs; }
    double lastMs() { return lastMs; }
    boolean lastDeleteVerified() { return lastDeleteVerified; }

    @Override public void close() {
        end();
        lastDeleteVerified = true;
        for (int i = 0; i < queries.length; i++) {
            if (queries[i] != 0) {
                int query = queries[i];
                GL15.glDeleteQueries(query);
                lastDeleteVerified &= !GL15.glIsQuery(query);
            }
            queries[i] = 0;
            pending[i] = false;
        }
        enabled = false;
        epoch++;
    }
}
