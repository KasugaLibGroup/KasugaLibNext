package lib.kasuga.rendering.models.mc.source.model.binary;

import lib.kasuga.rendering.models.mc.Constants;
import lib.kasuga.rendering.models.uml.loaders.sources.SourceManager;

public final class BinaryModelSourceManager extends SourceManager<byte[]> {
    public BinaryModelSourceManager(String name) { super(Constants.MODEL_TYPE, name); }
}
