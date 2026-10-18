package lib.kasuga.rendering.models.uml.loaders;

/** Converts an external format into common Skeleton definitions. Implementations are extensible. */
@FunctionalInterface
public interface SkeletonDynamicsReader<T> {
    void read(T source, SkeletonDynamicsBuilder builder);
}
