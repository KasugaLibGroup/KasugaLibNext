package lib.kasuga.core.resource.view;

import lib.kasuga.core.resource.identity.ResourcePaths;
import lib.kasuga.core.resource.identity.ResourceUri;
import lib.kasuga.core.resource.source.ResourceSnapshot;
import lib.kasuga.core.resource.source.ResourceSnapshot.LocatedResourceFile;
import java.util.*;
import java.util.function.Predicate;

/** One scope/generation's directory, retaining every root provider. No decoding during queries. */
public final class ResourceCatalog {
    public static final Comparator<LocatedResourceFile> ORDER = Comparator
            .comparingInt((LocatedResourceFile f) -> f.descriptor().source().rank())
            .thenComparing(f -> f.descriptor().uri());
    private final String scopeId;
    private final long generation;
    private final Map<ResourceUri, LocatedResourceFile> concrete = new TreeMap<>();
    private final Map<String, List<LocatedResourceFile>> layers = new TreeMap<>();

    public ResourceCatalog(String scopeId, long generation, Collection<ResourceSnapshot> snapshots) {
        this.scopeId = Objects.requireNonNull(scopeId); this.generation = generation;
        Set<String> roots = new HashSet<>();
        for (ResourceSnapshot snapshot : snapshots) {
            Set<String> snapshotRoots = new HashSet<>();
            for (LocatedResourceFile file : snapshot.files()) {
                var d = file.descriptor();
                String root = d.uri().sourceId() + "/" + d.uri().rootId();
                if (roots.contains(root)) throw new IllegalArgumentException("DUPLICATE_ENTRY: source/root: " + root);
                snapshotRoots.add(root);
                if (concrete.putIfAbsent(d.uri(), file) != null) throw new IllegalArgumentException("DUPLICATE_ENTRY: " + d.uri());
                layers.computeIfAbsent(d.logicalPath(), ignored -> new ArrayList<>()).add(file);
            }
            roots.addAll(snapshotRoots);
        }
        layers.values().forEach(stack -> {
            stack.sort(ORDER);
            for (int i = 1; i < stack.size(); i++) {
                if (stack.get(i - 1).descriptor().source().rank() == stack.get(i).descriptor().source().rank())
                    throw new IllegalArgumentException("ROUTING_CONFLICT: equal rank providers for " + stack.get(i).descriptor().logicalPath());
            }
        });
    }

    public String scopeId() { return scopeId; }
    public long generation() { return generation; }
    public List<LocatedResourceFile> files() { return List.copyOf(concrete.values()); }
    public Optional<LocatedResourceFile> concrete(ResourceUri uri) { return Optional.ofNullable(concrete.get(uri)); }
    public Optional<LocatedResourceFile> find(ResourceKey key) { return find(key, ignored -> true); }
    public Optional<LocatedResourceFile> find(ResourceKey key, Predicate<ResourceDescriptor> visible) {
        var stack = stack(key.logicalPath(), visible);
        if (stack.isEmpty()) return Optional.empty();
        var winner = stack.getLast(); key.check(winner.descriptor()); return Optional.of(winner);
    }
    public List<LocatedResourceFile> stack(String path) { return stack(path, ignored -> true); }
    public List<LocatedResourceFile> stack(String path, Predicate<ResourceDescriptor> visible) {
        return layers.getOrDefault(path, List.of()).stream().filter(file -> visible.test(file.descriptor())).toList();
    }

    public List<ResourceDescriptor> query(ResourceQuery query, Predicate<ResourceDescriptor> visible) {
        List<ResourceDescriptor> results = new ArrayList<>();
        for (var entry : layers.entrySet()) {
            List<LocatedResourceFile> allowed = entry.getValue().stream().filter(f -> visible.test(f.descriptor())).toList();
            if (!query.allProviders() && !allowed.isEmpty()) allowed = List.of(allowed.getLast());
            for (LocatedResourceFile file : allowed) {
                ResourceDescriptor d = file.descriptor();
                if (ResourcePaths.contains(query.prefix(), d.logicalPath())
                        && (query.type().isEmpty() || query.type().equals(d.type()))
                        && (query.subtype().isEmpty() || query.subtype().equals(d.subtype()))
                        && d.tags().containsAll(query.tags())
                        && (query.sources().isEmpty() || query.sources().contains(d.source().sourceId()))
                        && (query.owners().isEmpty() || query.owners().contains(d.source().ownerId()))) results.add(d);
            }
        }
        return results.stream().skip(query.offset()).limit(query.limit()).toList();
    }
}
