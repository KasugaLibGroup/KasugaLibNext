package lib.kasuga.rendering.models.mc.source.model.binary;

import lib.kasuga.rendering.models.uml.loaders.sources.Source;

public abstract class BinaryModelSource<T> implements Source<T, byte[]> {
    private final String name;
    protected BinaryModelSource(String name) { this.name = name; }
    @Override public String name() { return name; }
    @Override public Class<byte[]> getOutputType() { return byte[].class; }
}
