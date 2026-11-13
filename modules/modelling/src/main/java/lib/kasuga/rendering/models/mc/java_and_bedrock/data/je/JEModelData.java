package lib.kasuga.rendering.models.mc.java_and_bedrock.data.je;

import com.google.gson.JsonObject;
import lib.kasuga.utils.json.JsonFieldPolicy.NonNullFields;
import lib.kasuga.rendering.models.mc.util.ModelJson;
import lib.kasuga.utils.json.JsonFieldPolicy.DefaultOnMismatch;
import lib.kasuga.rendering.models.uml.structure.data.ModelData;
import lib.kasuga.rendering.models.mc.component.ModelComponents;
import lib.kasuga.rendering.models.mc.component.ModelComponentsHolder;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.*;

@Setter
@Getter
public class JEModelData implements ModelData, ModelComponentsHolder {
    
    @Nullable
    private JEModelData parent;

    private ResourceLocation identifier;
    private final List<JEElement> elements = new ArrayList<>();
    private JsonObject components;
    private ModelComponents kasugaComponents;
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
            if (components == null && parent.components != null) components = parent.components.deepCopy();
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
        Input input = ModelJson.GSON.fromJson(Objects.requireNonNull(json), Input.class);
        JEModelData data = new JEModelData();
        input.elements.forEach(element -> data.elements.add(element.build()));
        data.textures.putAll(input.textures);
        input.display.forEach((name, display) -> data.display.put(name, display.build()));
        data.ambientOcclusion = input.ambientocclusion;
        data.guiLight = input.guiLight;
        data.components = json.has("kasuga_lib") ? json.getAsJsonObject("kasuga_lib").deepCopy() : null;
        return data;
    }

    @Override
    public ModelComponents components() { return kasugaComponents; }

    @NonNullFields
    private static final class Input {
        List<JEElement.Input> elements = List.of();
        Map<String, String> textures = Map.of();
        Map<String, JEDisplay.Input> display = Map.of();
        @DefaultOnMismatch Boolean ambientocclusion = true;
        @DefaultOnMismatch String guiLight = "side";
    }
}
