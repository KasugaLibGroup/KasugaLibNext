package lib.kasuga.rendering.models.mc.typo.bbmodel;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lib.kasuga.rendering.models.mc.util.ModelJson;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.JsonDeserializer;
import lib.kasuga.rendering.models.mc.util.Direction;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector2f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Immutable subset of Blockbench's native format used by the UML loader. */
public record BbModelDefinition(
        int textureWidth,
        int textureHeight,
        List<Texture> textures,
        Map<String, Element> elements,
        List<OutlineNode> outliner,
        List<BbModelAnimation> animations
) {
    public static BbModelDefinition parse(String input) {
        Input root = ModelJson.GSON.fromJson(input, Input.class);
        int width = root.resolution.width, height = root.resolution.height;
        List<Texture> textures = new ArrayList<>();
        for (int index = 0; index < root.textures.size(); index++) {
            TextureInput texture = root.textures.get(index);
            textures.add(new Texture(index, texture.id == null ? Integer.toString(index) : texture.id,
                    texture.name == null ? "texture_" + index : texture.name, texture.source,
                    texture.width == null ? width : texture.width, texture.height == null ? height : texture.height,
                    texture.uvWidth == null ? width : texture.uvWidth, texture.uvHeight == null ? height : texture.uvHeight));
        }
        Map<String, Element> elements = new HashMap<>();
        for (JsonObject value : root.elements) {
            ElementInput element = ModelJson.GSON.fromJson(value, ElementInput.class);
            String id = element.uuid != null ? element.uuid : element.id != null ? element.id : "element_" + value.hashCode();
            Map<Direction, Face> cubeFaces = new HashMap<>();
            List<MeshFace> meshFaces = new ArrayList<>();
            if ("mesh".equals(element.type)) {
                for (JsonObject faceJson : element.faces.values()) {
                    MeshFaceInput face = ModelJson.GSON.fromJson(faceJson, MeshFaceInput.class);
                    meshFaces.add(new MeshFace(face.vertices, face.uv, face.texture == null ? -1 : face.texture));
                }
            } else {
                element.faces.forEach((name, faceJson) -> {
                    try {
                        Direction direction = Direction.fromString(name);
                        CubeFaceInput face = ModelJson.GSON.fromJson(faceJson, CubeFaceInput.class);
                        if (face.uv == null || face.uv.length != 4) throw new IllegalArgumentException("Expected four UV components");
                        cubeFaces.put(direction, new Face(face.uv, face.texture == null ? -1 : face.texture, face.rotation));
                    } catch (IllegalArgumentException ignored) {
                        // Blockbench includes editor-only face keys with no Minecraft direction.
                    }
                });
            }
            elements.put(id, new Element(id, element.type, element.visibility && element.export,
                    element.from, element.to, element.origin, element.rotation, Map.copyOf(cubeFaces),
                    "mesh".equals(element.type) ? Map.copyOf(element.vertices) : Map.of(), List.copyOf(meshFaces)));
        }
        Map<String, GroupInput> groups = new HashMap<>();
        for (GroupInput group : root.groups) if (!group.uuid.isEmpty()) groups.put(group.uuid, group);
        List<OutlineNode> outliner = root.outliner.stream().map(value -> outline(value, groups)).toList();
        List<BbModelAnimation> animations = root.animations.stream().map(BbModelAnimation::parse).toList();
        return new BbModelDefinition(width, height, List.copyOf(textures), Map.copyOf(elements), outliner, animations);
    }

    private static OutlineNode outline(JsonElement value, Map<String, GroupInput> groups) {
        if (value.isJsonPrimitive()) return new ElementNode(ModelJson.GSON.fromJson(value, String.class));
        GroupInput group = ModelJson.GSON.fromJson(value, GroupInput.class);
        GroupInput meta = groups.getOrDefault(group.uuid, group);
        return new GroupNode(meta.name, meta.visibility && meta.export, meta.origin, meta.rotation,
                group.children.stream().map(child -> outline(child, groups)).toList());
    }

    private static final class Input {
        Resolution resolution = new Resolution();
        List<TextureInput> textures = List.of();
        List<JsonObject> elements = List.of(), animations = List.of();
        List<GroupInput> groups = List.of();
        List<JsonElement> outliner = List.of();
    }
    private static final class Resolution { Integer width = 16, height = 16; }
    private static final class TextureInput {
        String id, name, source = "";
        Integer width, height, uvWidth, uvHeight;
    }
    private static final class ElementInput {
        String type = "cube", uuid, id;
        Boolean visibility = true, export = true;
        Vector3f from = new Vector3f(), to = new Vector3f(), origin = new Vector3f(), rotation = new Vector3f();
        Map<String, Vector3f> vertices = Map.of();
        Map<String, JsonObject> faces = Map.of();
    }
    private static class FaceInput {
        @JsonAdapter(TextureIndexAdapter.class) Integer texture = -1;
    }
    private static final class CubeFaceInput extends FaceInput {
        float[] uv;
        Integer rotation = 0;
    }
    private static final class MeshFaceInput extends FaceInput {
        List<String> vertices;
        Map<String, Vector2f> uv = Map.of();
    }
    private static final class GroupInput {
        String uuid = "", name = "";
        Vector3f origin, rotation = new Vector3f();
        Boolean visibility = true, export = true;
        List<JsonElement> children = List.of();
    }
    private static final class TextureIndexAdapter implements JsonDeserializer<Integer> {
        @Override public Integer deserialize(JsonElement value, java.lang.reflect.Type type,
                                             com.google.gson.JsonDeserializationContext context) {
            if (value.isJsonNull() || value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) return -1;
            return context.deserialize(value, Integer.class);
        }
    }

    public record Texture(int index, String id, String name, String source, int width, int height, int uvWidth, int uvHeight) {}

    public record Element(String id, String type, boolean visible, Vector3f from, Vector3f to, Vector3f origin,
                          Vector3f rotation, Map<Direction, Face> cubeFaces, Map<String, Vector3f> vertices,
                          List<MeshFace> meshFaces) {}

    public record Face(float[] uv, int texture, int rotation) {}

    public record MeshFace(List<String> vertices, Map<String, Vector2f> uvs, int texture) {}

    public sealed interface OutlineNode permits ElementNode, GroupNode {}

    public record ElementNode(String elementId) implements OutlineNode {}

    /**
     * @param name     the explicit outliner name, or {@code ""} when the group is unnamed (auto-exported
     *                 models often omit it). Empty names FLATTEN the group into its nearest named ancestor —
     *                 only explicitly named groups become skeleton bones. (The default was previously
     *                 {@code "group"}, which silently collided every unnamed group into one bone name.)
     * @param origin   the group pivot in Blockbench pixels; {@code null} when the model omits it. The loader
     *                 falls back to the average of the subtree element origins for the bone pivot.
     */
    public record GroupNode(String name, boolean visible, @Nullable Vector3f origin, Vector3f rotation,
                            List<OutlineNode> children) implements OutlineNode {}
}
