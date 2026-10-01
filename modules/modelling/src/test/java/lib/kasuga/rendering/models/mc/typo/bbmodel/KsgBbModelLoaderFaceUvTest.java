package lib.kasuga.rendering.models.mc.typo.bbmodel;

import lib.kasuga.rendering.models.mc.util.Direction;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.material.MaterialSet;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Cube-face UV mapping of {@link KsgBbModelLoader}, pinned to Blockbench's own semantics
 * ({@code CubeFace.UVToLocal}): for every face {@code u} runs to the viewer's right and {@code v} downward
 * as seen from OUTSIDE the cube, and {@code rotation} turns the texture clockwise.
 *
 * <p>The corner tour is top-left, bottom-left, bottom-right, top-right as seen from outside, which is also
 * the winding the backend needs for outward normals. The previous table mirrored the four side faces
 * vertically and gave the top/bottom faces inward normals.
 */
class KsgBbModelLoaderFaceUvTest {

    private static final float EPS = 1e-4f;

    /** UV canvas used by the synthetic cube (also the size normalizePixelUv() divides by). */
    private static final int CANVAS = 64;

    /**
     * Expected position of each UV rectangle corner, in 0/1 min-max coordinates, for the unrotated face.
     * Derived from Blockbench's {@code CubeFace.UVToLocal} axis table, not from the loader.
     */
    private static final Map<Direction, Map<String, Vector3f>> EXPECTED = expectedFaceCorners();

    @Test
    void mapsEveryCubeFaceToItsBlockbenchUvCornersAndOutwardNormal() {
        Model model = cubeModel(0);
        Map<Direction, Mesh> faces = facesByDirection(model);

        for (Direction direction : Direction.values()) {
            Mesh mesh = faces.get(direction);
            assertNotNull(mesh, "no outward-facing mesh for " + direction);
            assertEquals(EXPECTED.get(direction), cornerPositions(mesh, direction), direction.toString());
        }
    }

    @Test
    void rotatesFaceUvsClockwise() {
        // Blockbench rotates the texture clockwise: rotation 90 puts the rectangle's left-bottom pixel in
        // the face's top-left slot, so a north face receives (u0,v1) at its (maxX,maxY) corner.
        Map<String, Vector3f> corners = cornerPositions(facesByDirection(cubeModel(90)).get(Direction.NORTH),
                Direction.NORTH);

        assertEquals(new Vector3f(0, 1, 0), corners.get("00"));
        assertEquals(new Vector3f(0, 0, 0), corners.get("10"));
        assertEquals(new Vector3f(1, 0, 0), corners.get("11"));
        assertEquals(new Vector3f(1, 1, 0), corners.get("01"));
    }

    @Test
    void usesTheUvCanvasInsteadOfTheDecodedImageSize() {
        // qj_bogey_main.bbmodel style: a 64x64 png stretched over the project's 128x128 UV canvas, with
        // face UVs up to 128. Parsing falls back to the project resolution, which must stay the divisor.
        BbModelDefinition scaled = BbModelDefinition.parse("""
                {"resolution": {"width": 128, "height": 128},
                 "textures": [{"id": "t", "name": "half.png", "source": "data:image/png;base64,",
                               "width": 64, "height": 64, "uv_width": 128, "uv_height": 128}]}
                """);
        assertArrayEquals(new int[]{128, 128},
                KsgBbModelLoader.uvCanvas(scaled.textures().getFirst(), scaled));

        // Files that omit every size key at all (Blockbench 4.5 era) still resolve to the project resolution.
        BbModelDefinition undeclared = BbModelDefinition.parse("""
                {"resolution": {"width": 128, "height": 64},
                 "textures": [{"id": "t", "name": "half.png", "source": "data:image/png;base64,"}]}
                """);
        assertArrayEquals(new int[]{128, 64},
                KsgBbModelLoader.uvCanvas(undeclared.textures().getFirst(), undeclared));
    }

    private static void assertArrayEquals(int[] expected, int[] actual) {
        assertEquals(expected[0], actual[0]);
        assertEquals(expected[1], actual[1]);
    }

    /** Builds a one-cube model through the real geometry path with stub (runtime-free) materials. */
    private static Model cubeModel(int northRotation) {
        String rotation = northRotation == 0 ? "" : ", \"rotation\": " + northRotation;
        StringBuilder faces = new StringBuilder();
        for (Direction direction : Direction.values()) {
            if (faces.length() > 0) faces.append(',');
            faces.append('"').append(direction).append("\": {\"uv\": [0, 0, ").append(CANVAS).append(", ")
                    .append(CANVAS).append("], \"texture\": 0")
                    .append(direction == Direction.NORTH ? rotation : "").append('}');
        }
        BbModelDefinition definition = BbModelDefinition.parse("""
                {"resolution": {"width": 64, "height": 64},
                 "textures": [{"id": "t", "name": "t.png", "source": "test:t.png"}],
                 "elements": [{"uuid": "cube", "type": "cube", "from": [0, 0, 0], "to": [16, 16, 16],
                               "origin": [0, 0, 0], "faces": {%s}}]}
                """.formatted(faces));

        Texture texture = new Texture("t", CANVAS, CANVAS, null);
        Material material = new Material(new Texture[]{texture}, null);
        return KsgBbModelLoader.buildSkeletonAndGeometry(definition, Map.of(0, material),
                new MaterialSet(texture, material));
    }

    /**
     * Collects the model's six faces into an enum map keyed by the outward direction their geometric normal
     * points at — a face with inverted winding finds no direction and fails the test.
     */
    private static Map<Direction, Mesh> facesByDirection(Model model) {
        Map<Direction, Mesh> result = new EnumMap<>(Direction.class);
        assertEquals(6, model.getMeshes().length, "expected one mesh per cube face");
        for (Mesh mesh : model.getMeshes()) {
            Direction direction = null;
            for (Direction candidate : Direction.values()) {
                if (candidate.toVec3f().distance(mesh.getNormal()) <= EPS) {
                    direction = candidate;
                    break;
                }
            }
            assertNotNull(direction, "face normal " + mesh.getNormal() + " is not an outward axis direction");
            assertNull(result.put(direction, mesh), "two faces claim " + direction);
        }
        return result;
    }

    /** Maps the face's UV rectangle corners to world positions, numbered {@code uv.x}{@code uv.y}. */
    private static Map<String, Vector3f> cornerPositions(Mesh mesh, Direction direction) {
        Map<String, Vector3f> corners = new HashMap<>();
        for (Vertex vertex : mesh.getVertices()) {
            Vector2f uv = vertex.getUV(mesh, mesh.getMaterials()[0]);
            assertNotNull(uv, "vertex without UV on " + direction);
            corners.put(Math.round(uv.x) + "" + Math.round(uv.y), new Vector3f(vertex.getPosition()));
        }
        assertEquals(4, corners.size(), "face does not carry four distinct UV corners: " + corners);
        return corners;
    }

    /**
     * The expected corner-to-position mapping of an unrotated face, built from Blockbench's axis table
     * ({@code u} = the viewer's right, {@code v} = down, top face {@code u=+X, v=+Z}, bottom face
     * {@code u=+X, v=-Z}). On a 0..1 cube the corner {@code (u,v)} sits at {@code (u0,v0) + u·û + v·v̂}.
     */
    private static Map<Direction, Map<String, Vector3f>> expectedFaceCorners() {
        Map<Direction, Map<String, Vector3f>> result = new EnumMap<>(Direction.class);
        for (Direction direction : Direction.values()) {
            Vector3f uAxis = axis(direction, true);
            Vector3f vAxis = axis(direction, false);
            Map<String, Vector3f> corners = new HashMap<>();
            for (int u = 0; u <= 1; u++) {
                for (int v = 0; v <= 1; v++) {
                    corners.put(u + "" + v, new Vector3f(uvOrigin(direction))
                            .fma(u, uAxis)
                            .fma(v, vAxis));
                }
            }
            result.put(direction, corners);
        }
        return result;
    }

    /** Where the rectangle's {@code (u0,v0)} (top-left) corner sits on the face, in 0/1 min-max coordinates. */
    private static Vector3f uvOrigin(Direction direction) {
        return switch (direction) {
            case NORTH -> new Vector3f(1, 1, 0);
            case SOUTH -> new Vector3f(0, 1, 1);
            case WEST -> new Vector3f(0, 1, 0);
            case EAST -> new Vector3f(1, 1, 1);
            case UP -> new Vector3f(0, 1, 0);
            case DOWN -> new Vector3f(0, 0, 1);
        };
    }

    private static Vector3f axis(Direction direction, boolean uAxis) {
        return switch (direction) {
            case NORTH -> uAxis ? new Vector3f(-1, 0, 0) : new Vector3f(0, -1, 0);
            case SOUTH -> uAxis ? new Vector3f(1, 0, 0) : new Vector3f(0, -1, 0);
            case WEST -> uAxis ? new Vector3f(0, 0, 1) : new Vector3f(0, -1, 0);
            case EAST -> uAxis ? new Vector3f(0, 0, -1) : new Vector3f(0, -1, 0);
            case UP -> uAxis ? new Vector3f(1, 0, 0) : new Vector3f(0, 0, 1);
            case DOWN -> uAxis ? new Vector3f(1, 0, 0) : new Vector3f(0, 0, -1);
        };
    }
}
