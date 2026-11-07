package lib.kasuga.rendering.models.uml.dynamic;

import lib.kasuga.rendering.models.uml.structure.Model;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The shared publication domain. Publication and instance lifecycle operations are confined
 * to the first thread that uses them; snapshots may be read and closed on any thread.
 * Publication does not freeze a Model: concurrent readers must not race mutations of
 * the model or its contents. Keys must have stable equality and hash codes.
 */
public final class ModelPublicationCoordinator {
    private static final ModelPublicationCoordinator GLOBAL = new ModelPublicationCoordinator();

    public static ModelPublicationCoordinator global() { return GLOBAL; }

    private record Entry(Model model, Object owner, long revision) {}
    private record Generation(long number, Map<ModelPipeLine<?, ?>, Map<Object, Entry>> pipelines) {}

    private final AtomicReference<Generation> current = new AtomicReference<>(new Generation(0, Map.of()));
    private final List<Consumer<Preparation>> participants = new ArrayList<>();
    private Thread gameThread;
    private boolean publishing;
    private long revision;

    /** An isolated publication domain; pipelines must explicitly opt into it through their builder. */
    public ModelPublicationCoordinator() {}

    /** Called by the owning thread at a quiescent lifecycle boundary before a replacement thread takes over. */
    public synchronized void releaseThread() {
        if (gameThread != null && gameThread != Thread.currentThread()) {
            throw new IllegalStateException("Only the publication owner may release its thread binding");
        }
        if (publishing) throw new IllegalStateException("Cannot release the publication thread during a transaction");
        gameThread = null;
    }

    private void requirePipeline(ModelPipeLine<?, ?> pipeline) {
        if (Objects.requireNonNull(pipeline).publicationCoordinator() != this) {
            throw new IllegalArgumentException("Pipeline belongs to another publication domain");
        }
    }

    synchronized void checkThread() {
        if (gameThread == null) gameThread = Thread.currentThread();
        else if (gameThread != Thread.currentThread()) {
            throw new IllegalStateException("Model publication and instance lifecycle must run on the game thread");
        }
    }

    public <K> Publisher<K> publisher() { return new Publisher<>(this); }

    /** A pin retains models and revisions only, not instances or backend resources.
     * Close it promptly to release its reference to the old generation. */
    public Snapshot pin() { return new Snapshot(this, current.get()); }

    public static final class Snapshot implements AutoCloseable {
        private final ModelPublicationCoordinator coordinator;
        private final AtomicReference<Generation> generation;
        private Snapshot(ModelPublicationCoordinator coordinator, Generation generation) {
            this.coordinator = coordinator;
            this.generation = new AtomicReference<>(generation);
        }
        private Generation generation() {
            Generation value = generation.get();
            if (value == null) throw new IllegalStateException("Publication snapshot is closed");
            return value;
        }
        public long generationNumber() { return generation().number(); }
        @Nullable public <K> Model getModel(ModelPipeLine<K, ?> pipeline, K key) {
            coordinator.requirePipeline(pipeline);
            Entry entry = lookup(generation(), pipeline, key);
            return entry == null ? null : entry.model();
        }
        public <K> long modelRevision(ModelPipeLine<K, ?> pipeline, K key) {
            coordinator.requirePipeline(pipeline);
            Entry entry = lookup(generation(), pipeline, key);
            return entry == null ? 0 : entry.revision();
        }
        @Override public void close() { generation.set(null); }
    }

    private static Entry lookup(Generation generation, ModelPipeLine<?, ?> pipeline, Object key) {
        Objects.requireNonNull(pipeline, "pipeline");
        Objects.requireNonNull(key, "key");
        Map<Object, Entry> entries = generation.pipelines().get(pipeline);
        return entries == null ? null : entries.get(key);
    }

    <K> Model getModel(ModelPipeLine<K, ?> pipeline, K key) {
        Entry entry = lookup(current.get(), pipeline, key);
        return entry == null ? null : entry.model();
    }
    <K> long modelRevision(ModelPipeLine<K, ?> pipeline, K key) {
        Entry entry = lookup(current.get(), pipeline, key);
        return entry == null ? 0 : entry.revision();
    }

    /** A participant may stage removals through any live publisher handle, including one
     * other than the publisher initiating the transaction, but must not mutate live state.
     * It may be invoked repeatedly until no further removals are requested. A thrown exception
     * aborts the transaction. Publication and participant registration are not reentrant. */
    public synchronized AutoCloseable onPrepare(Consumer<Preparation> participant) {
        if (gameThread != null) checkThread();
        if (publishing) throw new IllegalStateException("Cannot change publication participants during a transaction");
        Objects.requireNonNull(participant, "participant");
        participants.add(participant);
        boolean[] subscribed = {true};
        return () -> {
            synchronized (ModelPublicationCoordinator.this) {
                if (!subscribed[0]) return;
                if (gameThread != null) checkThread();
                if (publishing) throw new IllegalStateException("Cannot change publication participants during a transaction");
                participants.remove(participant);
                subscribed[0] = false;
            }
        };
    }

    public record Change(ModelPipeLine<?, ?> pipeline, Object key,
                         @Nullable Model previous, @Nullable Model current) {}

    public final class Preparation {
        private final Generation before;
        private final IdentityHashMap<ModelPipeLine<?, ?>, Map<Object, Entry>> next;
        private boolean changed;
        private boolean active = true;
        private final Map<Object, Runnable> beforeNotifications = new IdentityHashMap<>();
        private Preparation(Generation before, IdentityHashMap<ModelPipeLine<?, ?>, Map<Object, Entry>> next) {
            this.before = before;
            this.next = next;
        }
        private void requireActive() {
            synchronized (ModelPublicationCoordinator.this) {
                if (!active) throw new IllegalStateException("Preparation is no longer active");
                checkThread();
            }
        }
        /** A transaction-local action, deduplicated by owner identity, run only after commit and before any listener. */
        public void beforeNotifications(Object owner, Runnable action) {
            requireActive();
            beforeNotifications.put(Objects.requireNonNull(owner), Objects.requireNonNull(action));
        }

        public List<Change> changes() { requireActive(); return differences(before, next); }
        /** Stages a removal using the actual owning publisher's handle. */
        public <K> boolean remove(Publisher<K> owner, ModelPipeLine<K, ?> pipeline, K key) {
            requireActive();
            requireOwner(owner);
            requirePipeline(pipeline);
            boolean removed = removeOwned(next, owner, pipeline, key);
            changed |= removed;
            return removed;
        }

        /** Removes a dependent key only if the given publisher currently owns it. */
        public <K> boolean removeIfOwned(Publisher<K> owner, ModelPipeLine<K, ?> pipeline, K key) {
            requireActive();
            requireOwner(owner);
            requirePipeline(pipeline);
            Objects.requireNonNull(key, "key");
            var entries = next.get(pipeline);
            Entry entry = entries == null ? null : entries.get(key);
            if (entry == null || entry.owner() != owner) return false;
            entries.remove(key);
            changed = true;
            return true;
        }

        /** Stages removal of all keys belonging to a publisher in one pipeline. */
        public <K> void removeAll(Publisher<K> owner, ModelPipeLine<K, ?> pipeline) {
            requireActive();
            requireOwner(owner);
            requirePipeline(pipeline);
            var entries = next.get(Objects.requireNonNull(pipeline, "pipeline"));
            if (entries != null) changed |= entries.values().removeIf(entry -> entry.owner() == owner);
        }
    }

    public static final class Publisher<K> implements AutoCloseable {
        private final ModelPublicationCoordinator coordinator;
        private volatile boolean closed;
        private Publisher(ModelPublicationCoordinator coordinator) { this.coordinator = coordinator; }
        /** Replaces this owner's complete contribution across all pipelines. */
        public void replace(Map<? extends ModelPipeLine<K, ?>, ? extends Map<K, Model>> models) {
            coordinator.update(this, models, true, null, null);
        }
        /** Stages dependent removals and applies associated state before notifications.
         * The committed action must not publish recursively; failures are reported as post-commit failures. */
        public void replace(Map<? extends ModelPipeLine<K, ?>, ? extends Map<K, Model>> models,
                            Consumer<Preparation> prepare, Runnable committed) {
            coordinator.update(this, models, true, Objects.requireNonNull(prepare), Objects.requireNonNull(committed));
        }
        /** Adds or replaces this owner's keys without affecting its other keys. */
        public void put(ModelPipeLine<K, ?> pipeline, Map<K, Model> models) {
            coordinator.update(this, Map.of(pipeline, models), false, null, null);
        }
        /** Removes a key owned by this publisher, returning false if it is absent. */
        public boolean remove(ModelPipeLine<K, ?> pipeline, K key) {
            return coordinator.remove(this, pipeline, key);
        }
        @Override public void close() {
            if (closed) return;
            try { coordinator.update(this, Map.of(), true, null, null); }
            catch (PublicationCommittedException committed) { closed = true; throw committed; }
            closed = true;
        }
    }

    /** The new generation is already visible; retrying the transaction is not rollback. */
    public static final class PublicationCommittedException extends RuntimeException {
        private final long generation;
        private PublicationCommittedException(long generation, Throwable cause) {
            super("Model generation " + generation + " committed, but notification or retirement failed", cause);
            this.generation = generation;
        }
        public long generationNumber() { return generation; }
    }

    private void requireOwner(Publisher<?> owner) {
        Objects.requireNonNull(owner, "owner");
        if (owner.coordinator != this || owner.closed) throw new IllegalStateException("Publisher is closed or belongs to another coordinator");
    }
    private synchronized void begin(Publisher<?> owner) {
        checkThread();
        requireOwner(owner);
        if (publishing) throw new IllegalStateException("Reentrant model publication is not allowed");
        publishing = true;
    }
    private synchronized void endPublication() { publishing = false; }
    private synchronized List<Consumer<Preparation>> participantSnapshot() { return List.copyOf(participants); }

    private IdentityHashMap<ModelPipeLine<?, ?>, Map<Object, Entry>> copy(Generation generation) {
        var result = new IdentityHashMap<ModelPipeLine<?, ?>, Map<Object, Entry>>();
        generation.pipelines().forEach((pipeline, entries) -> result.put(pipeline, new HashMap<>(entries)));
        return result;
    }
    private <K> void update(Publisher<K> owner,
                            Map<? extends ModelPipeLine<K, ?>, ? extends Map<K, Model>> models,
                            boolean replace, @Nullable Consumer<Preparation> prepare, @Nullable Runnable committed) {
        begin(owner);
        try {
            Generation before = current.get();
            var next = copy(before);
            if (replace) next.values().forEach(entries -> entries.values().removeIf(entry -> entry.owner() == owner));
            Objects.requireNonNull(models, "models").forEach((pipeline, values) -> {
                requirePipeline(pipeline);
                Objects.requireNonNull(values, "models");
                var entries = next.computeIfAbsent(pipeline, ignored -> new HashMap<>());
                values.forEach((key, model) -> {
                    Objects.requireNonNull(key, "key");
                    Objects.requireNonNull(model, "model");
                    Entry existing = entries.get(key);
                    if (existing != null && existing.owner() != owner) throw new IllegalStateException("Model key is owned by another publisher: " + key);
                    Entry old = lookup(before, pipeline, key);
                    if (old != null && old.owner() != owner) throw new IllegalStateException("Model key is owned by another publisher: " + key);
                    entries.put(key, new Entry(model, owner, old != null && old.model() == model ? old.revision() : 0));
                });
            });
            commit(before, next, prepare, committed);
        } finally { endPublication(); }
    }
    private <K> boolean remove(Publisher<K> owner, ModelPipeLine<K, ?> pipeline, K key) {
        begin(owner);
        try {
            requirePipeline(pipeline);
            Generation before = current.get();
            var next = copy(before);
            boolean removed = removeOwned(next, owner, pipeline, key);
            if (removed) commit(before, next, null, null);
            return removed;
        } finally { endPublication(); }
    }
    private static boolean removeOwned(IdentityHashMap<ModelPipeLine<?, ?>, Map<Object, Entry>> next,
                                       Publisher<?> owner, ModelPipeLine<?, ?> pipeline, Object key) {
        Objects.requireNonNull(pipeline, "pipeline"); Objects.requireNonNull(key, "key");
        var entries = next.get(pipeline);
        Entry entry = entries == null ? null : entries.get(key);
        if (entry == null) return false;
        if (entry.owner() != owner) throw new IllegalStateException("Model key is owned by another publisher: " + key);
        entries.remove(key);
        return true;
    }
    private static List<Change> differences(Generation before,
            IdentityHashMap<ModelPipeLine<?, ?>, Map<Object, Entry>> next) {
        var pipelines = Collections.newSetFromMap(new IdentityHashMap<ModelPipeLine<?, ?>, Boolean>());
        pipelines.addAll(before.pipelines().keySet()); pipelines.addAll(next.keySet());
        var changes = new ArrayList<Change>();
        for (var pipeline : pipelines) {
            Map<Object, Entry> old = before.pipelines().getOrDefault(pipeline, Map.of());
            Map<Object, Entry> updated = next.getOrDefault(pipeline, Map.of());
            Set<Object> keys = new HashSet<>(old.keySet()); keys.addAll(updated.keySet());
            for (Object key : keys) {
                Entry a = old.get(key), b = updated.get(key);
                Model previous = a == null ? null : a.model(), current = b == null ? null : b.model();
                if (previous != current) changes.add(new Change(pipeline, key, previous, current));
            }
        }
        return List.copyOf(changes);
    }
    private void commit(Generation before, IdentityHashMap<ModelPipeLine<?, ?>, Map<Object, Entry>> next,
                        @Nullable Consumer<Preparation> prepare, @Nullable Runnable committed) {
        Preparation preparation = new Preparation(before, next);
        try {
            if (prepare != null) prepare.accept(preparation);
            // Preparation only removes entries: each changed pass must remove at least one.
            // The extra pass observes the fixed point, bounding work without truncating deep valid chains.
            long remainingPasses = next.values().stream().mapToLong(Map::size).sum() + 1;
            do {
                if (remainingPasses-- == 0) throw new IllegalStateException("Publication preparation did not converge");
                preparation.changed = false;
                for (var participant : participantSnapshot()) participant.accept(preparation);
            } while (preparation.changed);
        } finally {
            synchronized (this) { preparation.active = false; }
        }
        List<Change> changes = differences(before, next);
        if (changes.isEmpty() && committed == null) return;
        long nextRevision = revision;
        var versions = new ArrayList<Long>(changes.size());
        for (Change change : changes) {
            long version = ++nextRevision;
            versions.add(version);
            var entries = next.get(change.pipeline());
            Entry entry = entries == null ? null : entries.get(change.key());
            if (entry != null) entries.put(change.key(), new Entry(entry.model(), entry.owner(), version));
        }
        next.values().removeIf(Map::isEmpty);
        var frozen = new IdentityHashMap<ModelPipeLine<?, ?>, Map<Object, Entry>>();
        next.forEach((pipeline, entries) -> frozen.put(pipeline, Map.copyOf(entries)));
        Generation after = new Generation(before.number() + 1, Collections.unmodifiableMap(frozen));
        var retired = new ArrayList<ModelPipeLine.RetiredChange>(changes.size());
        for (int i = 0; i < changes.size(); i++) {
            Change change = changes.get(i);
            retired.add(change.pipeline().prepareForPublication(change.key(), change.previous(), change.current(), versions.get(i)));
        }
        revision = nextRevision;
        current.set(after);
        Throwable failure = null;
        for (var change : retired) failure = change.detach(failure);
        for (Runnable action : preparation.beforeNotifications.values()) {
            try { action.run(); }
            catch (RuntimeException | Error problem) { failure = combine(failure, problem); }
        }
        if (committed != null) {
            try { committed.run(); }
            catch (RuntimeException | Error problem) { failure = combine(failure, problem); }
        }
        for (var change : retired) failure = change.notifyListeners(failure);
        for (var change : retired) failure = change.retire(failure);
        if (failure != null) throw new PublicationCommittedException(after.number(), failure);
    }

    static Throwable combine(@Nullable Throwable failure, Throwable next) {
        if (failure == null) return next;
        if (failure != next) failure.addSuppressed(next);
        return failure;
    }
}
