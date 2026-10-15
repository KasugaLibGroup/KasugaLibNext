package lib.kasuga.rendering.models.mc.backend;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PeelLayerLoopTest {
    @Test
    void stopsOnlyOnACompletedEmptyBatchFromThisFrame() {
        var queries = new FakeQueries();
        try (var loop = new PeelLayerLoop(queries, 3)) {
            var layers = new FakeLayers(queries, 2);
            var result = loop.render(32, 4, layers);
            assertEquals(4, result.submittedLayers());
            assertEquals(1, result.issuedQueries());
            assertEquals(4, layers.clears);
            assertEquals(4, layers.accumulations);
            assertEquals(1, layers.pins);
        }
        assertEquals(queries.created, queries.deleted.size());
    }

    @Test
    void oldFrameBoundsDoNotTruncateNewGeometryAndLayerCapRemainsExplicit() {
        var queries = new FakeQueries();
        try (var loop = new PeelLayerLoop(queries, 1)) {
            loop.render(32, 4, new FakeLayers(queries, 2));
            var growing = loop.render(32, 4, new FakeLayers(queries, 40));
            assertEquals(4, growing.observedPassBound());
            assertEquals(32, growing.submittedLayers());
            var empty = loop.render(32, 4, new FakeLayers(queries, 0));
            assertEquals(32, empty.observedOverflowLimit());
            assertEquals(4, empty.submittedLayers());
        }
    }

    @Test
    void busyRingNeverWaitsReadsUnavailableResultsOrReusesQueryNames() {
        var queries = new FakeQueries();
        queries.ready = false;
        try (var loop = new PeelLayerLoop(queries, 3)) {
            for (int i = 0; i < 3; i++) {
                var result = loop.render(32, 4, new FakeLayers(queries, 2));
                assertFalse(result.ringBusy());
                assertEquals(32, result.submittedLayers());
            }
            int created = queries.created;
            var fourth = loop.render(32, 4, new FakeLayers(queries, 2));
            assertTrue(fourth.ringBusy());
            assertEquals(0, fourth.issuedQueries());
            assertEquals(32, fourth.submittedLayers());
            assertEquals(created, queries.created);
            assertEquals(0, queries.reads);
        }
    }

    @Test
    void failedProbeClosesQueryAndConditionalScopesAndKeepsItsSlotPending() {
        var queries = new FakeQueries();
        queries.ready = false;
        try (var loop = new PeelLayerLoop(queries, 1)) {
            var layers = new FakeLayers(queries, 40) {
                @Override public void draw(int layer) {
                    super.draw(layer);
                    if (layer == 7) throw new IllegalStateException("injected draw failure");
                }
            };
            assertThrows(IllegalStateException.class, () -> loop.render(32, 4, layers));
            assertEquals(0, queries.active);
            assertEquals(0, queries.conditionalDepth);
            assertTrue(loop.render(32, 4, new FakeLayers(queries, 0)).ringBusy());
        }
    }

    @Test
    void acceptsPartialLastQueryBatchAndRejectsInvalidBounds() {
        var queries = new FakeQueries();
        try (var loop = new PeelLayerLoop(queries, 1)) {
            var result = loop.render(5, 3, new FakeLayers(queries, 8));
            assertEquals(5, result.submittedLayers());
            assertEquals(2, result.issuedQueries());
            assertThrows(IllegalArgumentException.class, () -> loop.render(0, 4, new FakeLayers(queries, 0)));
            assertThrows(IllegalArgumentException.class, () -> loop.render(32, 17, new FakeLayers(queries, 0)));
        }
    }

    private static class FakeLayers implements PeelLayerLoop.Layers {
        final FakeQueries queries;
        final int surfaces;
        int clears, accumulations, pins;
        FakeLayers(FakeQueries queries, int surfaces) { this.queries = queries; this.surfaces = surfaces; }
        @Override public void clear() { clears++; }
        @Override public void draw(int layer) {
            if (queries.active != 0) queries.results.put(queries.active, layer < surfaces);
        }
        @Override public void pinNearestDepth() { pins++; }
        @Override public void accumulate() { accumulations++; }
        @Override public void swap() {}
    }

    private static final class FakeQueries implements PeelLayerLoop.Queries {
        final Map<Integer, Boolean> results = new HashMap<>();
        final Set<Integer> deleted = new HashSet<>();
        int created, active, conditionalDepth, reads;
        boolean ready = true;
        @Override public int create() { return ++created; }
        @Override public void delete(int id) { assertTrue(deleted.add(id)); }
        @Override public boolean available(int id) { return ready && results.containsKey(id); }
        @Override public boolean visible(int id) {
            assertTrue(available(id), "read of unavailable query");
            reads++;
            return results.get(id);
        }
        @Override public void begin(int id) { assertEquals(0, active); active = id; }
        @Override public void end() { assertNotEquals(0, active); active = 0; }
        @Override public void beginConditional(int id) { assertEquals(0, conditionalDepth); conditionalDepth++; }
        @Override public void endConditional() { assertEquals(1, conditionalDepth); conditionalDepth--; }
    }
}
