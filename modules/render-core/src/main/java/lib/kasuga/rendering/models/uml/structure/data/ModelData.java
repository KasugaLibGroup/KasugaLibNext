package lib.kasuga.rendering.models.uml.structure.data;

public interface ModelData {

    boolean isMeshTriangles();

    /** Optional format-reader hook, run while constructing a model, before any instance exists. */
    default void configureSkeleton(lib.kasuga.rendering.models.uml.structure.skeleton.Skeleton skeleton) {}
}
