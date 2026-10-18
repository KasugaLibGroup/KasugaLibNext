package lib.kasuga.rendering.models.mc.source.model.json;

import com.google.gson.JsonObject;
import lib.kasuga.rendering.models.mc.Constants;
import lib.kasuga.rendering.models.uml.loaders.sources.SourceManager;
import lib.kasuga.rendering.models.uml.loaders.sources.SourceType;

public class JsonModelSourceManager extends SourceManager<JsonObject> {

    public JsonModelSourceManager(String name) {
        super(Constants.MODEL_TYPE, name);
    }
}
