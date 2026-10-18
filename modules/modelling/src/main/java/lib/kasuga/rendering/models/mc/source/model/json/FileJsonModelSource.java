package lib.kasuga.rendering.models.mc.source.model.json;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileReader;
import java.nio.file.Path;
import java.util.Optional;

public class FileJsonModelSource extends JsonModelSource<Path> {

    public FileJsonModelSource(String name) {
        super(name);
    }

    @Override
    public Optional<JsonObject> getInput(Path path) {
        File file = path.toFile();
        if (!file.exists() || !file.isFile()) return Optional.empty();
        try (FileReader content = new FileReader(file)) {
            JsonElement element = JsonParser.parseReader(content);
            if (!element.isJsonObject()) return Optional.empty();
            return Optional.of(element.getAsJsonObject());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public Class<Path> getInputType() {
        return Path.class;
    }

    @Override
    public boolean isValidInput(Object input) {
        return input instanceof Path;
    }
}
