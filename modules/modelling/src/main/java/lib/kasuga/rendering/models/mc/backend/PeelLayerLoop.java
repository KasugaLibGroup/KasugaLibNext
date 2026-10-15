package lib.kasuga.rendering.models.mc.backend;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;

import java.util.Arrays;
import java.util.Objects;

/** Shared production/standalone peel submission loop. No Minecraft bootstrap or render-state cache. */
final class PeelLayerLoop implements AutoCloseable {
    interface Layers {
        void clear();
        void draw(int layer);
        void pinNearestDepth();
        void accumulate();
        void swap();
    }

    interface Queries {
        int create();
        void delete(int id);
        boolean available(int id);
        boolean visible(int id);
        void begin(int id);
        void end();
        void beginConditional(int id);
        void endConditional();
    }

    record Result(int submittedLayers, int issuedQueries, boolean ringBusy,
                  int observedPassBound, int observedOverflowLimit) {}

    private final Queries device;
    private final Batch[] ring;
    private final QueryResultRing slots;
    private int observedPassBound;
    private int observedOverflowLimit;

    PeelLayerLoop() {
        this(new GlQueries(), 3);
    }

    PeelLayerLoop(Queries device, int ringSize) {
        this.device = Objects.requireNonNull(device, "device");
        slots = new QueryResultRing(ringSize);
        ring = new Batch[ringSize];
        Arrays.setAll(ring, ignored -> new Batch());
    }

    Result render(int limit, int batchSize, Layers layers) {
        if (limit < 1 || limit > 256 || batchSize < 1 || batchSize > 16) {
            throw new IllegalArgumentException("Invalid peel limit or query batch size");
        }
        int slot = slots.acquire(index -> ring[index].ready(), index -> poll(ring[index]));
        Batch queries = slot < 0 ? null : ring[slot];
        if (queries != null) queries.prepare(limit, batchSize);
        int layer = 0;
        try {
            for (; layer < limit; layer++) {
                int group = layer / batchSize;
                // Only THIS frame's completed result can stop CPU submission.
                if (queries != null && group > 0 && layer % batchSize == 0
                        && queries.completedEmpty(group - 1)) break;
                boolean conditional = queries != null && group > 0;
                boolean probe = queries != null && ((layer + 1) % batchSize == 0 || layer + 1 == limit);
                if (conditional) device.beginConditional(queries.ids[group - 1]);
                try {
                    // Clear AND accumulation use the same predicate, so stale ping-pong data cannot leak.
                    layers.clear();
                    if (probe) device.begin(queries.ids[group]);
                    try {
                        layers.draw(layer);
                    } finally {
                        if (probe) {
                            device.end();
                            queries.issued = group + 1;
                        }
                    }
                    if (layer == 0) layers.pinNearestDepth();
                    layers.accumulate();
                } finally {
                    if (conditional) device.endConditional();
                }
                layers.swap();
            }
        } finally {
            // Even a failed callback can have issued GPU work. Never reuse its query ids prematurely.
            if (queries != null && queries.issued > 0) slots.submit(slot);
        }
        return new Result(layer, queries == null ? 0 : queries.issued, queries == null,
                observedPassBound, observedOverflowLimit);
    }

    private void poll(Batch batch) {
        observedPassBound = batch.limit;
        for (int i = 0; i < batch.issued; i++) {
            if (!device.visible(batch.ids[i])) {
                observedPassBound = Math.min(batch.limit, (i + 1) * batch.size);
                return;
            }
        }
        if (batch.issued == Math.ceilDiv(batch.limit, batch.size)) observedOverflowLimit = batch.limit;
    }

    private final class Batch {
        int[] ids = new int[0];
        int issued, limit, size;

        boolean ready() {
            return issued > 0 && device.available(ids[issued - 1]);
        }

        boolean completedEmpty(int group) {
            return device.available(ids[group]) && !device.visible(ids[group]);
        }

        void prepare(int limit, int size) {
            this.limit = limit;
            this.size = size;
            issued = 0;
            int count = (limit + size - 1) / size;
            int old = ids.length;
            if (old < count) {
                ids = Arrays.copyOf(ids, count);
                for (int i = old; i < count; i++) ids[i] = device.create();
            }
        }
    }

    private static final class GlQueries implements Queries {
        private int target;

        private int target() {
            if (target == 0) {
                var caps = GL.getCapabilities();
                target = caps.OpenGL33 || caps.GL_ARB_occlusion_query2
                        ? GL33.GL_ANY_SAMPLES_PASSED : GL15.GL_SAMPLES_PASSED;
            }
            return target;
        }

        @Override public int create() { return GL15.glGenQueries(); }
        @Override public void delete(int id) { GL15.glDeleteQueries(id); }
        @Override public boolean available(int id) {
            return GL15.glGetQueryObjecti(id, GL15.GL_QUERY_RESULT_AVAILABLE) != 0;
        }
        @Override public boolean visible(int id) { return GL15.glGetQueryObjecti(id, GL15.GL_QUERY_RESULT) != 0; }
        @Override public void begin(int id) { GL15.glBeginQuery(target(), id); }
        @Override public void end() { GL15.glEndQuery(target()); }
        @Override public void beginConditional(int id) { GL30.glBeginConditionalRender(id, GL30.GL_QUERY_WAIT); }
        @Override public void endConditional() { GL30.glEndConditionalRender(); }
    }

    @Override
    public void close() {
        for (Batch batch : ring) {
            for (int id : batch.ids) if (id != 0) device.delete(id);
            batch.ids = new int[0];
            batch.issued = 0;
        }
        slots.reset();
        observedPassBound = observedOverflowLimit = 0;
    }
}
