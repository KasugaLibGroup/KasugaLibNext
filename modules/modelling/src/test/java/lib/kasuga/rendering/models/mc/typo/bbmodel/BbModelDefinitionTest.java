package lib.kasuga.rendering.models.mc.typo.bbmodel;

import lib.kasuga.rendering.models.mc.util.Direction;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector2f;
import org.junit.jupiter.api.Test;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BbModelDefinitionTest {
    @Test
    void sortsCrossedMeshQuadVerticesIntoABoundaryLoop() {
        List<String> sorted = KsgBbModelLoader.sortMeshFaceVertices(
                Map.of(
                        "a", new Vector3f(0.0f, 0.0f, 0.0f),
                        "b", new Vector3f(1.0f, 0.0f, 0.0f),
                        "c", new Vector3f(0.0f, 1.0f, 0.0f),
                        "d", new Vector3f(1.0f, 1.0f, 0.0f)
                ),
                List.of("a", "b", "c", "d")
        );

        assertEquals(List.of("c", "a", "b", "d"), sorted);
    }

    @Test
    void usesBlockbenchTextureArrayOrderInsteadOfTextureId() {
        BbModelDefinition.Texture texture = new BbModelDefinition.Texture(
                0, "5", "track.png", "data:image/png;base64,", 128, 128, 128, 128
        );

        assertEquals(0, KsgBbModelLoader.textureReference(texture));
    }

    @Test
    void keepsBlockbenchUvsInPixelCoordinatesForTextureData() {
        // Corner tour is left-top, left-bottom, right-bottom, right-top, and rotation turns clockwise:
        // a 90° face rotation puts the rectangle's left-bottom pixel in the face's top-left slot.
        Vector2f[] uvs = KsgBbModelLoader.rectangularUvs(new float[]{16, 32, 48, 64}, 90);

        assertEquals(new Vector2f(16, 64), uvs[0]);
        assertEquals(new Vector2f(48, 64), uvs[1]);
        assertEquals(new Vector2f(48, 32), uvs[2]);
        assertEquals(new Vector2f(16, 32), uvs[3]);
    }

    @Test
    void normalizesBlockbenchPixelUvsAtLoaderBoundary() {
        Material material = new Material(new Texture[]{new Texture("test", 128, 64, null)}, null);

        assertEquals(new Vector2f(0.5f, 0.5f),
                KsgBbModelLoader.normalizePixelUv(new Vector2f(64, 32), material));
    }

    @Test
    void normalizesExternalTextureLocationForJarTextureSource() {
        ResourceLocation location = KsgBbModelLoader.resolveTextureLocation(
                ResourceLocation.fromNamespaceAndPath("kasuga_lib", "models/test.bbmodel"),
                "example:textures/vehicles/engine.png", "ignored.png"
        );

        assertEquals(ResourceLocation.fromNamespaceAndPath("example", "vehicles/engine"), location);
    }

    @Test
    void parsesCubeMeshTexturesAndNestedOutliner() {
        BbModelDefinition definition = BbModelDefinition.parse("""
                {
                  "resolution": {"width": 32, "height": 16},
                  "textures": [{"id": "main", "name": "main.png", "source": "example:textures/main.png"}],
                  "elements": [
                    {
                      "uuid": "cube", "type": "cube", "from": [0, 0, 0], "to": [16, 16, 16],
                      "origin": [8, 8, 8], "rotation": [0, 45, 0],
                      "faces": {"north": {"uv": [0, 0, 16, 16], "texture": 0, "rotation": 90}}
                    },
                    {
                      "uuid": "mesh", "type": "mesh", "origin": [0, 0, 0],
                      "vertices": {"a": [0, 0, 0], "b": [16, 0, 0], "c": [0, 16, 0]},
                      "faces": {"face": {"vertices": ["a", "b", "c"], "uv": {"a": [0, 0], "b": [16, 0], "c": [0, 16]}, "texture": 0}}
                    }
                  ],
                  "outliner": [{"name": "arm", "origin": [8, 8, 8], "rotation": [0, 0, 30], "children": ["cube", "mesh"]}]
                }
                """);

        assertEquals(32, definition.textureWidth());
        assertEquals(16, definition.textureHeight());
        assertEquals("example:textures/main.png", definition.textures().getFirst().source());

        BbModelDefinition.Element cube = definition.elements().get("cube");
        assertEquals(45.0f, cube.rotation().y);
        assertEquals(90, cube.cubeFaces().get(Direction.NORTH).rotation());

        BbModelDefinition.Element mesh = definition.elements().get("mesh");
        assertEquals(3, mesh.vertices().size());
        assertEquals(3, mesh.meshFaces().getFirst().vertices().size());

        assertTrue(definition.outliner().getFirst() instanceof BbModelDefinition.GroupNode);
        BbModelDefinition.GroupNode group = (BbModelDefinition.GroupNode) definition.outliner().getFirst();
        assertTrue(group.visible());
        assertEquals(2, group.children().size());
    }
}
