package lib.kasuga.rendering.models.uml.loaders.assembly;

import lib.kasuga.rendering.models.uml.structure.Model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/** Bounded CPU geometry/rig cache. Synchronization ensures one assembly per concurrent cache miss. */
public final class ModelAssemblyCache {
    private final int maximumEntries;
    private final long maximumVertices;
    private final ModelAssembler assembler;
    private final Map<ModelAssembly.Request, ModelAssembly> entries = new LinkedHashMap<>(16, 0.75f, true);
    private long vertices, hits, misses, builds;

    public ModelAssemblyCache() { this(32, 1_000_000, ModelAssembler.standard()); }
    public ModelAssemblyCache(int maximumEntries, long maximumVertices) {
        this(maximumEntries, maximumVertices, ModelAssembler.standard());
    }
    public ModelAssemblyCache(int maximumEntries, long maximumVertices, ModelAssembler assembler) {
        if (maximumEntries < 1 || maximumVertices < 1) throw new IllegalArgumentException("positive cache limits required");
        this.maximumEntries = maximumEntries; this.maximumVertices = maximumVertices;
        this.assembler = Objects.requireNonNull(assembler, "assembler");
    }

    public synchronized ModelAssembly getOrAssemble(ModelAssembly.Request request) {
        Objects.requireNonNull(request, "request");
        ModelAssembly current = entries.get(request);
        if (current != null) { hits++; return current; }
        misses++;
        ModelAssembly result = Objects.requireNonNull(assembler.assemble(request), "assembler returned null");
        if (!request.equals(result.request())) throw new IllegalArgumentException("assembler returned a different request");
        builds++;
        int count = result.model().getVertices().length;
        if (count > maximumVertices) return result; // Usable result, but too large to retain.
        entries.put(request, result); vertices += count;
        var iterator = entries.entrySet().iterator();
        while (entries.size() > maximumEntries || vertices > maximumVertices) {
            var eldest = iterator.next();
            vertices -= eldest.getValue().model().getVertices().length;
            iterator.remove();
        }
        return result;
    }

    public synchronized int invalidate(Model source) {
        return invalidate(request -> request.parts().stream().anyMatch(part -> part.source().model() == source));
    }
    public synchronized int invalidate(String partId) {
        return invalidate(request -> request.parts().stream().anyMatch(part -> part.id().equals(partId)));
    }
    private int invalidate(Predicate<ModelAssembly.Request> match) {
        int removed = 0;
        var iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (match.test(entry.getKey())) {
                vertices -= entry.getValue().model().getVertices().length;
                iterator.remove(); removed++;
            }
        }
        return removed;
    }
    public synchronized void clear() { entries.clear(); vertices = 0; }
    public synchronized Stats stats() { return new Stats(hits, misses, builds, entries.size(), vertices); }
    public record Stats(long hits, long misses, long builds, int entries, long vertices) {}
}
