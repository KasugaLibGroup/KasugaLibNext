package lib.kasuga.rendering.models.uml.dynamic;

import lib.kasuga.rendering.models.uml.structure.Model;
import java.util.Map;

/** Convenience owner for complete resource generations in the global publication domain. */
public final class ModelPublicationBatch<K> implements AutoCloseable {
    private final ModelPublicationCoordinator.Publisher<K> publisher;
    public ModelPublicationBatch() { this(ModelPublicationCoordinator.global()); }
    public ModelPublicationBatch(ModelPublicationCoordinator coordinator) {
        publisher = java.util.Objects.requireNonNull(coordinator).publisher();
    }

    public void publish(Map<? extends ModelPipeLine<K, ?>, ? extends Map<K, Model>> prepared) {
        publisher.replace(prepared);
    }

    public void put(ModelPipeLine<K, ?> pipeline, Map<K, Model> models) {
        publisher.put(pipeline, models);
    }

    public boolean remove(ModelPipeLine<K, ?> pipeline, K key) {
        return publisher.remove(pipeline, key);
    }

    public ModelPublicationCoordinator.Publisher<K> publisher() { return publisher; }

    @Override public void close() { publisher.close(); }
}
