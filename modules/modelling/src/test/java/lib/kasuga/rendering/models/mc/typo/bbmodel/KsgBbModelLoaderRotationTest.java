package lib.kasuga.rendering.models.mc.typo.bbmodel;

import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.material.MaterialSet;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Element/group rotation semantics of {@link KsgBbModelLoader}.
 *
 * <p>Blockbench composes euler angles as intrinsic Z → Y → X (matrix {@code Rz·Ry·Rx}, three.js
 * {@code Euler.order = "ZYX"} and Minecraft's {@code ModelPart} {@code ZP}/{@code YP}/{@code XP} order).
 * Rotations are relative: each element rotates around its own {@code origin}, inside its parent group's
 * already-rotated frame. Angles are used exactly as stored — Blockbench's Bedrock export writes the
 * negated X/Y values ({@code test_fan_be.bbmodel} {@code [0,45,0]} ↔ {@code test_fan_be.geo.json}
 * {@code [0,-45,0]}), so the bbmodel loader must not negate anything itself.
 */
class KsgBbModelLoaderRotationTest {

    private static final float EPS = 1e-4f;

    @Test
    void composesMultiAxisElementRotationInBlockbenchZyxOrder() {
        // 30/60 on two axes: Rz·Ry·Rx and Rx·Ry·Rz disagree, single-axis rotations would not notice.
        Model model = cubeModel(cube("[0,0,0]", "[16,16,16]", "[0,0,0]", "[30,60,0]", null), null);
        Quaternionf zyx = zyx(30f, 60f, 0f);
        Quaternionf xyz = new Quaternionf().rotationX(rad(30f)).rotationY(rad(60f));

        assertCornerInstances(model, cornerPositions(zyx), 3);
        List<Vector3f> wrong = cornerPositions(xyz);
        assertTrue(countMatches(model, wrong) < 24,
                "an XYZ (Rx·Ry·Rz) composition would produce different geometry for [30,60,0]");
    }

    @Test
    void rotatesElementAroundItsOwnOriginAndKeepsStoredSigns() {
        // The origin sits outside the cube: rotating around it must move the cube, not just spin it.
        Model rotated = cubeModel(cube("[0,0,0]", "[8,8,8]", "[16,16,16]", "[0,90,0]", null), null);
        Vector3f origin = new Vector3f(1f, 1f, 1f); // 16px
        List<Vector3f> expected = new ArrayList<>();
        Quaternionf y90 = zyx(0f, 90f, 0f);
        for (Vector3f corner : corners(0f, 0.5f)) {
            expected.add(new Vector3f(corner).sub(origin).rotate(y90).add(origin));
        }
        assertCornerInstances(rotated, expected, 3);

        // +90 is +90: the bbmodel stores Blockbench's own angles, which the Bedrock export negates
        // (bbmodel [0,45,0] ↔ geo.json [0,-45,0]) — negating here would flip the visible rotation.
        List<Vector3f> negated = new ArrayList<>();
        for (Vector3f corner : corners(0f, 0.5f)) {
            negated.add(new Vector3f(corner).sub(origin).rotate(zyx(0f, -90f, 0f)).add(origin));
        }
        assertEquals(0, countMatches(rotated, negated), "Y rotation must not be negated");
    }

    @Test
    void composesNestedGroupRotationsRelativeToTheirParents() {
        // outer(0,90,0 around [8,8,8]) → inner(45,0,0 around [8,16,8]) → cube(0,0,45 around [8,8,8]).
        String outliner = """
                [{"name": "outer", "origin": [8, 8, 8], "rotation": [0, 90, 0],
                  "children": [{"name": "inner", "origin": [8, 16, 8], "rotation": [45, 0, 0],
                                "children": ["cube"]}]}]
                """;
        Model model = cubeModel(cube("[0,0,0]", "[16,16,16]", "[8,8,8]", "[0,0,45]", null), outliner);

        // Independent reference: the Blockbench transform chain T(O)·R·T(−O) per level, parents leftmost,
        // applied to the cube's absolute (project space) corner positions.
        Matrix4f world = pivot(8, 8, 8, 0f, 90f, 0f)
                .mul(pivot(8, 16, 8, 45f, 0f, 0f))
                .mul(pivot(8, 8, 8, 0f, 0f, 45f));
        List<Vector3f> expected = new ArrayList<>();
        for (Vector3f corner : corners(0f, 16f)) {
            expected.add(world.transformPosition(corner).mul(1f / 16f));
        }
        assertCornerInstances(model, expected, 3);
    }

    //region helpers

    private static float rad(float degrees) {
        return (float) Math.toRadians(degrees);
    }

    /** The reference composition: intrinsic Z, then Y, then X (JOML's own axis rotations, post-multiplied). */
    private static Quaternionf zyx(float x, float y, float z) {
        return new Quaternionf()
                .mul(new Quaternionf().rotationZ(rad(z)))
                .mul(new Quaternionf().rotationY(rad(y)))
                .mul(new Quaternionf().rotationX(rad(x)));
    }

    private static Matrix4f pivot(float ox, float oy, float oz, float x, float y, float z) {
        Vector3f origin = new Vector3f(ox, oy, oz);
        return new Matrix4f()
                .translation(origin)
                .rotate(zyx(x, y, z))
                .mul(new Matrix4f().translation(new Vector3f(origin).negate()));
    }

    /** The 8 corners of the box spanned by {@code from}/{@code to} on each axis. */
    private static List<Vector3f> corners(float from, float to) {
        List<Vector3f> corners = new ArrayList<>(8);
        for (int i = 0; i < 8; i++) {
            corners.add(new Vector3f((i & 1) == 0 ? from : to, (i & 2) == 0 ? from : to, (i & 4) == 0 ? from : to));
        }
        return corners;
    }

    /** Corner positions (in blocks) of a unit-ish cube rotated by {@code rotation} around its own origin. */
    private static List<Vector3f> cornerPositions(Quaternionf rotation) {
        List<Vector3f> positions = new ArrayList<>();
        for (Vector3f corner : corners(0f, 1f)) {
            positions.add(corner.rotate(rotation));
        }
        return positions;
    }

    private static int countMatches(Model model, List<Vector3f> expected) {
        int matches = 0;
        for (Vertex vertex : model.getVertices()) {
            for (Vector3f candidate : expected) {
                if (vertex.getPosition().distance(candidate) < EPS) {
                    matches++;
                    break;
                }
            }
        }
        return matches;
    }

    /** Every cube corner is shared by three faces, so each expected position occurs exactly 3 times. */
    private static void assertCornerInstances(Model model, List<Vector3f> expected, int instances) {
        assertEquals(expected.size() * instances, model.getVertices().length);
        for (Vector3f candidate : expected) {
            int found = 0;
            for (Vertex vertex : model.getVertices()) {
                if (vertex.getPosition().distance(candidate) < EPS) {
                    found++;
                }
            }
            assertEquals(instances, found, "no vertex at " + candidate);
        }
    }

    private static String cube(String from, String to, String origin, String rotation, String extra) {
        StringBuilder faces = new StringBuilder();
        for (String direction : new String[]{"north", "south", "east", "west", "up", "down"}) {
            if (faces.length() > 0) faces.append(',');
            faces.append('"').append(direction)
                    .append("\": {\"uv\": [0, 0, 64, 64], \"texture\": 0}");
        }
        return """
                {"uuid": "cube", "type": "cube", "from": %s, "to": %s, "origin": %s, "rotation": %s%s,
                 "faces": {%s}}
                """.formatted(from, to, origin, rotation, extra == null ? "" : ", " + extra, faces);
    }

    private static Model cubeModel(String element, String outliner) {
        BbModelDefinition definition = BbModelDefinition.parse("""
                {"resolution": {"width": 64, "height": 64},
                 "textures": [{"id": "t", "name": "t.png", "source": "test:t.png"}],
                 "elements": [%s]%s}
                """.formatted(element, outliner == null ? "" : ", \"outliner\": " + outliner));
        Texture texture = new Texture("t", 64f, 64f, null);
        Material material = new Material(new Texture[]{texture}, null);
        return KsgBbModelLoader.buildSkeletonAndGeometry(definition, Map.of(0, material),
                new MaterialSet(texture, material));
    }

    //endregion
}
