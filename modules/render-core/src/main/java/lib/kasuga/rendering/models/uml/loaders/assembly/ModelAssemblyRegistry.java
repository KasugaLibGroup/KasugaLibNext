package lib.kasuga.rendering.models.uml.loaders.assembly;

import lib.kasuga.rendering.models.uml.structure.Model;

import java.util.*;
import java.util.function.Function;

/** Resource recipes and their latest complete results. Host publication/instance ownership stays outside. */
public final class ModelAssemblyRegistry<K, I> {
    private final Function<? super K, ModelAssembly.Source> sources;
    private final ModelAssemblyCache cache;
    private final Map<I, ModelAssemblyDefinition<K>> definitions = new LinkedHashMap<>();
    private final Map<I, ModelAssembly> resolved = new LinkedHashMap<>();

    public ModelAssemblyRegistry(Function<? super K, ModelAssembly.Source> sources, ModelAssemblyCache cache) {
        this.sources = Objects.requireNonNull(sources); this.cache = Objects.requireNonNull(cache);
    }
    public void register(I id, ModelAssemblyDefinition<K> definition) {
        definitions.put(Objects.requireNonNull(id), Objects.requireNonNull(definition));
    }
    public ModelAssemblyDefinition<K> definition(I id) { return definitions.get(id); }
    public Map<I, ModelAssemblyDefinition<K>> definitions() { return Collections.unmodifiableMap(definitions); }
    public boolean remove(I id) { resolved.remove(id); return definitions.remove(id) != null; }

    /** A pending recipe keeps its previous complete result alive; the caller can defer changing outfit. */
    public ModelAssembly resolve(I id) {
        ModelAssemblyDefinition<K> definition = definitions.get(id);
        if (definition == null) return null;
        ModelAssembly.Request request = definition.resolve(sources);
        if (request == null) return null;
        ModelAssembly previous = resolved.get(id);
        if (previous != null && previous.request().equals(request)) return previous;
        ModelAssembly next = cache.getOrAssemble(request);
        resolved.put(id, next);
        return next;
    }

    /** Removes results borrowing a retired source; recipes survive and resolve against the next generation. */
    public Set<I> invalidate(Model source) {
        cache.invalidate(source);
        Set<I> affected = new LinkedHashSet<>();
        resolved.entrySet().removeIf(entry -> {
            boolean match = entry.getValue().request().parts().stream().anyMatch(part -> part.source().model() == source);
            if (match) affected.add(entry.getKey());
            return match;
        });
        return Set.copyOf(affected);
    }
    public void clearResolved() { resolved.clear(); cache.clear(); }
    public ModelAssemblyCache.Stats cacheStats() { return cache.stats(); }
}
