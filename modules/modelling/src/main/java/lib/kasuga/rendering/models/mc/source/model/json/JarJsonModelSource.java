package lib.kasuga.rendering.models.mc.source.model.json;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.util.List;
import java.util.Optional;

public class JarJsonModelSource extends JsonModelSource<ResourceLocation> {

    public JarJsonModelSource(String name) {
        super(name);
    }

    @Override
    public Optional<JsonObject> getInput(ResourceLocation input) {
        ResourceManager manager = Minecraft.getInstance().getResourceManager();
        List<Resource> resources = manager.getResourceStack(input);
        if (resources.isEmpty()) return Optional.empty();
        try {
            JsonElement element = JsonParser.parseReader(resources.getFirst().openAsReader());
            if (!element.isJsonObject()) return Optional.empty();
            return Optional.of(element.getAsJsonObject());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public Class<ResourceLocation> getInputType() {
        return ResourceLocation.class;
    }

    @Override
    public boolean isValidInput(Object input) {
        return input instanceof ResourceLocation;
    }
}
