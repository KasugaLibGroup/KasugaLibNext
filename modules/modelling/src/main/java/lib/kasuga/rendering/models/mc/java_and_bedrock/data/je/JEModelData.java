package lib.kasuga.rendering.models.mc.java_and_bedrock.data.je;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lib.kasuga.rendering.models.mc.util.JsonHelper;
import lib.kasuga.rendering.models.uml.structure.data.ModelData;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.*;

@Setter
@Getter
public class JEModelData implements ModelData {
    
    @Nullable
    private JEModelData parent;

    private ResourceLocation identifier;
    private final List<JEElement> elements = new ArrayList<>();
    private final Map<String, String> textures = new HashMap<>();
    private boolean ambientOcclusion = true;
    private String guiLight = "side";
    private final Map<String, JEDisplay> display = new HashMap<>();
    
    @Override
    public boolean isMeshTriangles() {
        return false;
    }
    
    public boolean hasElements() {
        return !elements.isEmpty();
    }
    
    public boolean hasTextures() {
        return !textures.isEmpty();
    }
    
    public boolean hasDisplay() {
        return !display.isEmpty();
    }

    public void mergeFromParent() {
        if (parent == null) return;

        if (elements.isEmpty()) {
            elements.addAll(parent.elements);
        }

        Map<String, String> mergedTextures = new HashMap<>(parent.textures);
        mergedTextures.putAll(this.textures);
        this.textures.clear();
        this.textures.putAll(mergedTextures);

        if (!ambientOcclusion && parent.ambientOcclusion) {
            ambientOcclusion = parent.ambientOcclusion;
        }

        if (guiLight == null || guiLight.equals("side")) {
            guiLight = parent.guiLight;
        }

        Map<String, JEDisplay> mergedDisplay = new HashMap<>(parent.display);
        mergedDisplay.putAll(this.display);
        this.display.clear();
        this.display.putAll(mergedDisplay);

        this.parent = null;
    }

    public static JEModelData fromJson(JsonObject json) {
        Objects.requireNonNull(json, "json must not be null");

        JEModelData data = new JEModelData();

        if (json.has("elements")) {
            for (JsonElement element : json.getAsJsonArray("elements")) {
                data.elements.add(JEElement.fromJson(element.getAsJsonObject()));
            }
        }

        if (json.has("textures")) {
            JsonObject texturesObj = json.getAsJsonObject("textures");
            for (String key : texturesObj.keySet()) {
                data.textures.put(key, texturesObj.get(key).getAsString());
            }
        }

        if (json.has("ambientocclusion")) {
            data.ambientOcclusion = JsonHelper.jsonToBool(json, "ambientocclusion", true);
        }

        if (json.has("gui_light")) {
            data.guiLight = JsonHelper.jsonToString(json, "gui_light", "side");
        }

        if (json.has("display")) {
            JsonObject displayObj = json.getAsJsonObject("display");
            for (String key : displayObj.keySet()) {
                data.display.put(key, JEDisplay.fromJson(displayObj.getAsJsonObject(key)));
            }
        }

        return data;
    }
}
