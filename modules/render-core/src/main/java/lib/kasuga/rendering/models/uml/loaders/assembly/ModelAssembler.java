package lib.kasuga.rendering.models.uml.loaders.assembly;

/** Extension point for a host-specific assembler; the cache remains format-neutral. */
@FunctionalInterface
public interface ModelAssembler {
    ModelAssembly assemble(ModelAssembly.Request request);
    static ModelAssembler standard() { return DefaultModelAssembler.INSTANCE; }
    static ModelAssembler standard(MorphRemapper morphs) { return new DefaultModelAssembler(morphs); }
}
