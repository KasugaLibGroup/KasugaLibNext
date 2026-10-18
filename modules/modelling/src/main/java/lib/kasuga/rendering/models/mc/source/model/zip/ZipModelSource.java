package lib.kasuga.rendering.models.mc.source.model.zip;

import lib.kasuga.rendering.models.mc.typo.pmx_entry.ZipHelper;
import lib.kasuga.rendering.models.uml.loaders.sources.Source;

import java.util.Optional;

public abstract class ZipModelSource<T> implements Source<T, ZipHelper> {

    private final String name;

    public ZipModelSource(String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Class<ZipHelper> getOutputType() {
        return null;
    }
}
