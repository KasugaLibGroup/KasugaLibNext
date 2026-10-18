package lib.kasuga.rendering.effect;

import net.minecraft.resources.ResourceLocation;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/** Platform-independent ordered registry used by the public world-render registry. */
final class PipelineRegistryCore<S, P> {
    private final CopyOnWriteArrayList<Entry<S, P>> entries = new CopyOnWriteArrayList<>();
    private final Comparator<Entry<S, P>> order = Comparator
            .comparingInt(Entry<S, P>::priority)
            .thenComparing(entry -> entry.id().toString());
    private volatile long version;

    synchronized Registration register(ResourceLocation owner, ResourceLocation id, S stage, int priority,
                                       DuplicatePolicy duplicatePolicy, P pipeline) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(duplicatePolicy, "duplicatePolicy");
        Objects.requireNonNull(pipeline, "pipeline");

        Entry<S, P> existing = find(id);
        if (existing != null && duplicatePolicy == DuplicatePolicy.FAIL) {
            throw new IllegalStateException("Render pipeline ID " + id + " is already owned by " + existing.owner());
        }
        if (existing != null) entries.remove(existing);
        Entry<S, P> entry = new Entry<>(owner, id, stage, priority, pipeline);
        entries.add(entry);
        entries.sort(order);
        version++;
        return new Registration() {
            @Override
            public ResourceLocation id() {
                return entry.id();
            }

            @Override
            public ResourceLocation owner() {
                return entry.owner();
            }

            @Override
            public boolean isActive() {
                return entries.contains(entry);
            }

            @Override
            public void close() {
                synchronized (PipelineRegistryCore.this) {
                    if (entries.remove(entry)) version++;
                }
            }
        };
    }

    synchronized boolean unregister(ResourceLocation owner, ResourceLocation id) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(id, "id");
        boolean removed = entries.removeIf(entry -> entry.id().equals(id) && entry.owner().equals(owner));
        if (removed) version++;
        return removed;
    }

    boolean isRegistered(ResourceLocation id) {
        Objects.requireNonNull(id, "id");
        return entries.stream().anyMatch(entry -> entry.id().equals(id));
    }

    int size() {
        return entries.size();
    }

    List<Entry<S, P>> entries(S stage) {
        Objects.requireNonNull(stage, "stage");
        return entries.stream().filter(entry -> Objects.equals(entry.stage(), stage)).toList();
    }

    List<Entry<S, P>> entries() {
        return List.copyOf(entries);
    }

    long version() {
        return version;
    }

    private Entry<S, P> find(ResourceLocation id) {
        return entries.stream().filter(entry -> entry.id().equals(id)).findFirst().orElse(null);
    }

    interface Registration extends AutoCloseable {
        ResourceLocation id();
        ResourceLocation owner();
        boolean isActive();

        @Override
        void close();
    }

    record Entry<S, P>(ResourceLocation owner, ResourceLocation id, S stage, int priority, P pipeline) {}
}
