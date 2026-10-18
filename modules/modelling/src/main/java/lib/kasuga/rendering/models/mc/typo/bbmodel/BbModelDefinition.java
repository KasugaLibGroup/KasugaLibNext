package lib.kasuga.rendering.models.mc.typo.bbmodel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
        JsonObject root = JsonParser.parseString(input).getAsJsonObject();
        JsonObject resolution = root.has("resolution") ? root.getAsJsonObject("resolution") : new JsonObject();
        int width = getInt(resolution, "width", 16);
        int height = getInt(resolution, "height", 16);

        List<Texture> textures = new ArrayList<>();
        if (root.has("textures")) {
            JsonArray textureArray = root.getAsJsonArray("textures");
            for (int index = 0; index < textureArray.size(); index++) {
                JsonObject texture = textureArray.get(index).getAsJsonObject();
                textures.add(new Texture(
                        index,
                        getString(texture, "id", Integer.toString(index)),
                        getString(texture, "name", "texture_" + index),
                        getString(texture, "source", ""),
                        getInt(texture, "width", width),
                        getInt(texture, "height", height),
                        getInt(texture, "uv_width", width),
                        getInt(texture, "uv_height", height)
                ));
            }
        }

        Map<String, Element> elements = new HashMap<>();
        if (root.has("elements")) {
            for (JsonElement value : root.getAsJsonArray("elements")) {
                Element element = parseElement(value.getAsJsonObject());
                elements.put(element.id(), element);
            }
        }

        // Blockbench v5 stores group metadata (name / origin / rotation / visibility / export) in the
        // top-level "groups" array, keyed by uuid; the "outliner" tree references groups by uuid only.
        Map<String, GroupMeta> groups = new HashMap<>();
        if (root.has("groups")) {
            for (JsonElement value : root.getAsJsonArray("groups")) {
                JsonObject group = value.getAsJsonObject();
                String uuid = getString(group, "uuid", "");
                if (uuid.isEmpty()) continue;
                groups.put(uuid, new GroupMeta(
                        getString(group, "name", ""),
                        group.has("origin") ? vector3(group, "origin", new Vector3f()) : null,
                        vector3(group, "rotation", new Vector3f()),
                        getBoolean(group, "visibility", true),
                        getBoolean(group, "export", true)
                ));
            }
        }

        List<OutlineNode> outliner = new ArrayList<>();
        if (root.has("outliner")) {
            for (JsonElement value : root.getAsJsonArray("outliner")) {
                outliner.add(parseOutlineNode(value, groups));
            }
        }

        List<BbModelAnimation> animations = new ArrayList<>();
        if (root.has("animations")) {
            for (JsonElement value : root.getAsJsonArray("animations")) {
                animations.add(BbModelAnimation.parse(value.getAsJsonObject()));
            }
        }
        return new BbModelDefinition(width, height, List.copyOf(textures), Map.copyOf(elements), List.copyOf(outliner), List.copyOf(animations));
    }

    private static Element parseElement(JsonObject element) {
        String type = getString(element, "type", "cube");
        String id = getString(element, "uuid", getString(element, "id", "element_" + element.hashCode()));
        Map<Direction, Face> cubeFaces = new HashMap<>();
        Map<String, Vector3f> vertices = new HashMap<>();
        List<MeshFace> meshFaces = new ArrayList<>();

        if ("mesh".equals(type)) {
            JsonObject vertexObject = getObject(element, "vertices");
            for (String name : vertexObject.keySet()) {
                vertices.put(name, vector3(vertexObject.getAsJsonArray(name)));
            }
            JsonObject faceObject = getObject(element, "faces");
            for (String name : faceObject.keySet()) {
                JsonObject face = faceObject.getAsJsonObject(name);
                List<String> faceVertices = new ArrayList<>();
                for (JsonElement vertex : face.getAsJsonArray("vertices")) {
                    faceVertices.add(vertex.getAsString());
                }
                Map<String, Vector2f> uvs = new HashMap<>();
                JsonObject uvObject = getObject(face, "uv");
                for (String vertex : uvObject.keySet()) {
                    uvs.put(vertex, vector2(uvObject.getAsJsonArray(vertex)));
                }
                meshFaces.add(new MeshFace(faceVertices, uvs, getTextureIndex(face)));
            }
        } else {
            JsonObject faceObject = getObject(element, "faces");
            for (String name : faceObject.keySet()) {
                try {
                    Direction direction = Direction.fromString(name);
                    JsonObject face = faceObject.getAsJsonObject(name);
                    cubeFaces.put(direction, new Face(
                            vector4(face.getAsJsonArray("uv")),
                            getTextureIndex(face),
                            getInt(face, "rotation", 0)
                    ));
                } catch (IllegalArgumentException ignored) {
                    // Blockbench may include editor-only face keys which have no Minecraft direction.
                }
            }
        }

        return new Element(
                id,
                type,
                getBoolean(element, "visibility", true) && getBoolean(element, "export", true),
                vector3(element, "from", new Vector3f()),
                vector3(element, "to", new Vector3f()),
                vector3(element, "origin", new Vector3f()),
                vector3(element, "rotation", new Vector3f()),
                Map.copyOf(cubeFaces),
                Map.copyOf(vertices),
                List.copyOf(meshFaces)
        );
    }

    private static OutlineNode parseOutlineNode(JsonElement value, Map<String, GroupMeta> groups) {
        if (value.isJsonPrimitive()) {
            return new ElementNode(value.getAsString());
        }
        JsonObject group = value.getAsJsonObject();
        GroupMeta meta = group.has("uuid") ? groups.get(group.get("uuid").getAsString()) : null;
        String name = meta != null ? meta.name() : getString(group, "name", "");
        Vector3f origin = meta != null ? meta.origin()
                : (group.has("origin") ? vector3(group, "origin", new Vector3f()) : null);
        Vector3f rotation = meta != null ? meta.rotation() : vector3(group, "rotation", new Vector3f());
        boolean visible = (meta != null ? meta.visible() : getBoolean(group, "visibility", true))
                && (meta != null ? meta.export() : getBoolean(group, "export", true));
        List<OutlineNode> children = new ArrayList<>();
        if (group.has("children")) {
            for (JsonElement child : group.getAsJsonArray("children")) {
                children.add(parseOutlineNode(child, groups));
            }
        }
        return new GroupNode(
                name,
                visible,
                origin,
                rotation,
                List.copyOf(children)
        );
    }

    /** Blockbench v5 group metadata looked up from the top-level {@code groups} array by uuid. */
    private record GroupMeta(String name, @Nullable Vector3f origin, Vector3f rotation,
                             boolean visible, boolean export) {}

    private static JsonObject getObject(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonObject() ? object.getAsJsonObject(key) : new JsonObject();
    }

    private static String getString(JsonObject object, String key, String fallback) {
        return object.has(key) ? object.get(key).getAsString() : fallback;
    }

    private static int getInt(JsonObject object, String key, int fallback) {
        return object.has(key) ? object.get(key).getAsInt() : fallback;
    }

    private static int getTextureIndex(JsonObject object) {
        if (!object.has("texture") || object.get("texture").isJsonNull()) return -1;
        JsonElement texture = object.get("texture");
        if (texture.isJsonPrimitive() && texture.getAsJsonPrimitive().isBoolean()) return -1;
        return texture.getAsInt();
    }

    private static boolean getBoolean(JsonObject object, String key, boolean fallback) {
        return object.has(key) ? object.get(key).getAsBoolean() : fallback;
    }

    private static Vector3f vector3(JsonObject object, String key, Vector3f fallback) {
        return object.has(key) ? vector3(object.getAsJsonArray(key)) : new Vector3f(fallback);
    }

    private static Vector3f vector3(JsonArray values) {
        return new Vector3f(values.get(0).getAsFloat(), values.get(1).getAsFloat(), values.get(2).getAsFloat());
    }

    private static Vector2f vector2(JsonArray values) {
        return new Vector2f(values.get(0).getAsFloat(), values.get(1).getAsFloat());
    }

    private static float[] vector4(JsonArray values) {
        return new float[]{values.get(0).getAsFloat(), values.get(1).getAsFloat(), values.get(2).getAsFloat(), values.get(3).getAsFloat()};
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
