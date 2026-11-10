package lib.kasuga.rendering.models.mc.component;

/** Model data that carries the immutable Kasuga extension parsed from a resource. */
public interface ModelComponentsHolder extends lib.kasuga.rendering.models.uml.structure.data.ModelData {
    ModelComponents components();

    @Override
    default void configureInstance(lib.kasuga.rendering.models.uml.dynamic.ModelInstance instance) {
        var definition = components();
        if (definition != null && !definition.emitters().isEmpty())
            lib.kasuga.rendering.models.mc.dynamic.ModelParticleRuntime.install(instance, definition);
    }
}
