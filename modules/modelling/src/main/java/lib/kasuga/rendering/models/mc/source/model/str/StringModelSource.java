package lib.kasuga.rendering.models.mc.source.model.str;

import lib.kasuga.rendering.models.uml.loaders.sources.Source;

public abstract class StringModelSource<T> implements Source<T, String> {

    private final String name;

    public StringModelSource(String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Class<String> getOutputType() {
        return String.class;
    }
}
