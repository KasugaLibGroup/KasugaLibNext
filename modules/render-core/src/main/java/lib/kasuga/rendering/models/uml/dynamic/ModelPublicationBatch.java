package lib.kasuga.rendering.models.uml.dynamic;

import lib.kasuga.rendering.models.uml.structure.Model;
import java.util.*;

/** Publishes complete resource generations, retiring only the keys owned by this publisher. */
public final class ModelPublicationBatch<K> {
    private final Map<ModelPipeLine<?, ?, K, ?, ?>, Set<K>> owned = new IdentityHashMap<>();

    public void publish(Map<? extends ModelPipeLine<?, ?, K, ?, ?>, ? extends Map<K, Model>> prepared) {
        Map<ModelPipeLine<?, ?, K, ?, ?>, Map<K, Model>> next = new IdentityHashMap<>();
        prepared.forEach((pipeline, models) -> next.put(Objects.requireNonNull(pipeline), Map.copyOf(models)));
        Set<ModelPipeLine<?, ?, K, ?, ?>> pipelines = Collections.newSetFromMap(new IdentityHashMap<>());
        pipelines.addAll(owned.keySet()); pipelines.addAll(next.keySet());
        RuntimeException failure = null;
        for (var pipeline : pipelines) {
            Map<K, Model> models = next.getOrDefault(pipeline, Map.of());
            Set<K> removed = new HashSet<>(owned.getOrDefault(pipeline, Set.of()));
            removed.removeAll(models.keySet());
            for (K key : removed) {
                try { pipeline.removeModel(key); }
                catch (RuntimeException cleanup) { failure = combine(failure, cleanup); }
            }
            try { pipeline.publishModels(models); }
            catch (RuntimeException cleanup) { failure = combine(failure, cleanup); }
        }
        owned.clear(); next.forEach((pipeline, models) -> owned.put(pipeline, Set.copyOf(models.keySet())));
        if (failure != null) throw failure;
    }

    private static RuntimeException combine(RuntimeException failure, RuntimeException next) {
        if (failure == null) return next;
        if (failure != next) failure.addSuppressed(next);
        return failure;
    }
}
