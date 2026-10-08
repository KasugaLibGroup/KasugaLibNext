package lib.kasuga.rendering.models.uml.structure.data;

public interface ModelData {

    boolean isMeshTriangles();

    /** Optional format-reader hook, run while constructing a model, before any instance exists. */
    default void configureSkeleton(lib.kasuga.rendering.models.uml.structure.skeleton.Skeleton skeleton) {}

    /** Registers converted subsystems after the shared model has been fully initialized. */
    default void configureModel(lib.kasuga.rendering.models.uml.structure.Model model) {}
}
