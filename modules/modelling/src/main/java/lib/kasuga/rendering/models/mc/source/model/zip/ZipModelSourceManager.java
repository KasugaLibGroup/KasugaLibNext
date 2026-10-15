package lib.kasuga.rendering.models.mc.source.model.zip;

import lib.kasuga.rendering.models.mc.Constants;
import lib.kasuga.rendering.models.mc.typo.pmx_entry.ZipHelper;
import lib.kasuga.rendering.models.uml.loaders.sources.SourceManager;
import lib.kasuga.rendering.models.uml.loaders.sources.SourceType;

public class ZipModelSourceManager extends SourceManager<ZipHelper> {

    public ZipModelSourceManager(String name) {
        super(Constants.MODEL_TYPE, name);
    }
}
