package test.kasuga.modelling.demo.cobalt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Authored-contract test for the custom-model beach; rendering itself is covered by client smoke. */
class CobaltBeachAssetTest {
    private static final Path ASSET_ROOT = Path.of(
            "modules/modelling/src/contentTesting/resources/assets/kasuga_demo/models/cobalt");
    private static final Pattern FACE = Pattern.compile("\\d+(?:/\\d*){0,2}");

    @Test
    void beachObjIsAClosedContractForTheVisibleCoast() throws IOException {
        var root = repositoryRoot();
        var obj = root.resolve(ASSET_ROOT).resolve("beach.obj");
        var mtl = root.resolve(ASSET_ROOT).resolve("beach.mtl");
        assertTrue(Files.isRegularFile(obj), "missing custom beach OBJ: " + obj);
        assertTrue(Files.isRegularFile(mtl), "missing custom beach MTL: " + mtl);

        var vertices = new ArrayList<Vec>();
        var normals = new ArrayList<Vec>();
        var uvs = new ArrayList<Vec>();
        var faces = new ArrayList<Face>();
        var materials = new HashSet<String>();
        var usedMaterials = new HashSet<String>();
        var shoreProfile = new ArrayList<Vec>();
        var waterlineProfile = new ArrayList<Waterline>();
        var mtlText = Files.readString(mtl);
        for (String raw : mtlText.split("\\R")) {
            var parts = raw.trim().split("\\s+");
            if (parts.length == 2 && parts[0].equals("newmtl")) materials.add(parts[1]);
        }
        String material = null;
        for (String raw : Files.readAllLines(obj)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                if (line.startsWith("# kasuga_shore_profile ")) {
                    var parts = line.substring("# kasuga_shore_profile ".length()).trim().split("\\s+");
                    assertEquals(2, parts.length);
                    shoreProfile.add(new Vec(Float.parseFloat(parts[0]), Float.parseFloat(parts[1])));
                } else if (line.startsWith("# kasuga_waterline_profile ")) {
                    var parts = line.substring("# kasuga_waterline_profile ".length()).trim().split("\\s+");
                    assertEquals(3, parts.length);
                    waterlineProfile.add(new Waterline(Float.parseFloat(parts[0]), Float.parseFloat(parts[1]),
                            Float.parseFloat(parts[2])));
                }
                continue;
            }
            var parts = line.split("\\s+");
            switch (parts[0]) {
                case "v" -> {
                    assertEquals(4, parts.length, "vertex must be finite xyz: " + line);
                    vertices.add(new Vec(Float.parseFloat(parts[1]), Float.parseFloat(parts[2]),
                            Float.parseFloat(parts[3])));
                }
                case "vt" -> {
                    assertEquals(3, parts.length, "UV must be finite uv: " + line);
                    uvs.add(new Vec(Float.parseFloat(parts[1]), Float.parseFloat(parts[2])));
                }
                case "vn" -> {
                    assertEquals(4, parts.length, "normal must be finite xyz: " + line);
                    normals.add(new Vec(Float.parseFloat(parts[1]), Float.parseFloat(parts[2]),
                            Float.parseFloat(parts[3])));
                }
                case "usemtl" -> {
                    material = parts[1];
                    usedMaterials.add(material);
                }
                case "f" -> {
                    assertTrue(parts.length >= 4, "OBJ face needs at least three corners: " + line);
                    var corners = new int[parts.length - 1];
                    for (int i = 1; i < parts.length; i++) {
                        assertTrue(FACE.matcher(parts[i]).matches(), "unsupported OBJ face index: " + parts[i]);
                        int slash = parts[i].indexOf('/');
                        corners[i - 1] = Integer.parseInt(slash < 0 ? parts[i] : parts[i].substring(0, slash));
                    }
                    faces.add(new Face(material, corners));
                }
                default -> {
                    // Normals/UVs and unrelated directives are validated through indexed faces below.
                }
            }
        }

        assertFalse(vertices.isEmpty());
        assertFalse(faces.isEmpty());
        assertTrue(vertices.stream().allMatch(Vec::finite), "OBJ contains non-finite vertices");
        assertTrue(normals.stream().allMatch(Vec::finite), "OBJ contains non-finite normals");
        assertEquals(vertices.size(), uvs.size(), "UV count must match the heightfield vertices");
        assertTrue(uvs.stream().allMatch(uv -> uv.finite()
                        && uv.x >= 0f && uv.x <= 1f && uv.y >= 0f && uv.y <= 1f),
                "OBJ UVs must stay inside the material sprite");

        float minX = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        for (var vertex : vertices) {
            minX = Math.min(minX, vertex.x); maxX = Math.max(maxX, vertex.x);
            minZ = Math.min(minZ, vertex.z); maxZ = Math.max(maxZ, vertex.z);
        }
        assertTrue(maxX - minX >= 96f, "beach must span at least 96 blocks laterally");
        assertTrue(maxZ - minZ >= 96f, "beach/shore must span at least 96 blocks in depth");
        assertEquals(CobaltCoast.MIN_X, minX, 1e-5f);
        assertEquals(CobaltCoast.MAX_X, maxX, 1e-5f);
        assertEquals(CobaltCoast.MIN_Z, minZ, 1e-5f);
        assertEquals(CobaltCoast.MAX_Z, maxZ, 1e-5f);
        assertTrue(vertices.stream().anyMatch(vertex -> vertex.y <= CobaltCoast.DEEP_SEABED + .1f),
                "visible seabed must reach the deep-ocean floor");

        int columns = Math.round(maxX - minX) + 1;
        int rows = Math.round((maxZ - minZ) * 2) + 1;
        assertEquals(columns * rows, vertices.size(), "OBJ grid does not match its authored bounds");
        for (int row = 0; row < rows; row += Math.max(1, rows / 17)) {
            for (int column = 0; column < columns; column += 8) {
                var vertex = vertices.get(row * columns + column);
                assertEquals(CobaltCoast.height(vertex.x, vertex.z), vertex.y, .0002f,
                        "visible beach and collision heightfields diverge at " + vertex.x + "," + vertex.z);
            }
        }

        for (var face : faces) {
            assertNotNull(face.material, "face has no usemtl");
            assertTrue(materials.contains(face.material), "undefined material: " + face.material);
            assertTrue(face.corners.length >= 3);
            for (int corner : face.corners)
                assertTrue(corner > 0 && corner <= vertices.size(), "invalid vertex index: " + corner);
            var a = vertices.get(face.corners[0] - 1);
            var b = vertices.get(face.corners[1] - 1);
            var c = vertices.get(face.corners[2] - 1);
            float crossY = (b.z - a.z) * (c.x - a.x) - (b.x - a.x) * (c.z - a.z);
            assertTrue(crossY > 1e-5f, "top-facing beach face has reversed/degenerate winding: " + face.material);
            assertTrue(a.y > CobaltCoast.DEEP_SEABED - .1f && a.y < 70f,
                    "coast must stay in the authored terrain band");
        }

        assertEquals(java.util.Set.of("coast"), usedMaterials,
                "the terrain must use one atlas material so the shoreline blends inside the texture");

        assertTrue(shoreProfile.size() >= 9, "shore profile metadata is too sparse");
        float minShore = Float.POSITIVE_INFINITY, maxShore = Float.NEGATIVE_INFINITY;
        float previousX = Float.NEGATIVE_INFINITY, previousZ = Float.NaN, previousDelta = Float.NaN;
        boolean reversedSlope = false;
        for (var point : shoreProfile) {
            assertTrue(point.x > previousX, "shore profile x must be ordered");
            minShore = Math.min(minShore, point.z); maxShore = Math.max(maxShore, point.z);
            if (Float.isFinite(previousZ)) {
                float delta = point.z - previousZ;
                if (Float.isFinite(previousDelta) && delta * previousDelta < 0) reversedSlope = true;
                previousDelta = delta;
            }
            previousX = point.x;
            previousZ = point.z;
        }
        assertTrue(maxShore - minShore >= 3f, "shoreline must not be straight");
        assertTrue(reversedSlope, "shoreline must contain non-uniform reversals");
        assertTrue(waterlineProfile.size() >= 9, "waterline profile metadata is too sparse");
        for (var point : waterlineProfile) {
            assertEquals(CobaltCoast.SEA_LEVEL, point.y, .0002f);
            assertEquals(CobaltCoast.SEA_LEVEL, CobaltCoast.height(point.x, point.z), .002f,
                    "generated waterline differs from the runtime heightfield");
        }

        for (var used : usedMaterials)
            assertTrue(mtlText.contains("newmtl " + used), "MTL misses material " + used);
        assertTrue(mtlText.contains("map_Kd kasuga_demo:cobalt/coast"));
        assertTrue(Files.isRegularFile(root.resolve(
                        "modules/modelling/src/contentTesting/resources/assets/kasuga_demo/textures/cobalt/coast.png")),
                "missing coast atlas texture");
    }

    private static Path repositoryRoot() {
        for (Path directory = Path.of(System.getProperty("user.dir")); directory != null;
             directory = directory.getParent()) {
            if (Files.isRegularFile(directory.resolve("settings.gradle"))
                    && Files.isRegularFile(directory.resolve("modules/modelling/build.gradle")))
                return directory;
        }
        throw new IllegalStateException("cannot locate repository root from " + System.getProperty("user.dir"));
    }

    private record Vec(float x, float y, float z) {
        Vec(float x, float z) { this(x, 0, z); }
        boolean finite() { return Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z); }
    }

    private record Face(String material, int[] corners) {}
    private record Waterline(float x, float z, float y) {}
}
