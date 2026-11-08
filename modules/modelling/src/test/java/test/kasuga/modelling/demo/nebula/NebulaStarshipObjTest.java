package test.kasuga.modelling.demo.nebula;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class NebulaStarshipObjTest {
    @Test void sharedObjAndMtlProcessorsRetainPbrValuesNegativeIndicesAndMissingNormals() {
        var mesh = lib.kasuga.rendering.models.mc.typo.KsgObjLoader.loadFlatMesh("""
                v 0 0 0
                v 1 0 0
                v 0 1 0
                usemtl fixture
                f -3 -2 -1
                """, """
                newmtl fixture
                Kd 0.1 0.2 0.3
                Ke 0.3 0.4 0
                Pr 0.42
                d 1
                map_Kd base.png
                map_Bump normal.png
                map_Pr orm.png
                map_Ke emission.png
                """, ignored -> 5f);
        assertEquals(1, mesh.triangleCount());
        assertEquals(48, mesh.vertices().length);
        assertEquals(1, mesh.vertices()[5], .00001f);
        assertEquals(.5f, mesh.vertices()[12], .00001f);
        assertEquals(.42f, mesh.vertices()[13], .00001f);
        assertEquals(5, mesh.vertices()[14]);
        var material = mesh.materials().get("fixture");
        assertEquals("base.png", material.getMap_Kd());
        assertEquals("normal.png", material.getMap_Bump());
        assertEquals("orm.png", material.getMap_Pr());
        assertEquals("emission.png", material.getMap_Ke());
    }

    @Test void imagegenHullAlbedoIsUsedByEveryStandaloneMaterial() throws Exception {
        String mtl = resource("assets/kasuga_demo/models/constitution_starship.mtl");
        long materials = mtl.lines().filter(line -> line.startsWith("newmtl ")).count();
        assertEquals(materials, mtl.lines().filter(line -> line.equals(
                "map_Kd constitution_starship_imagegen_basecolor.png")).count(),
                "Standalone materials still point at the old procedural checkerboard");
        try (var input = NebulaStarshipObjTest.class.getClassLoader().getResourceAsStream(
                "assets/kasuga_demo/models/constitution_starship_imagegen_basecolor.png")) {
            assertNotNull(input);
            var image = javax.imageio.ImageIO.read(input);
            assertNotNull(image);
            assertEquals(image.getWidth(), image.getHeight());
            assertTrue(image.getWidth() >= 1024 && image.getWidth() <= 2048);
            for (int y = 0; y < image.getHeight(); y += 17)
                for (int x = 0; x < image.getWidth(); x += 17)
                    assertEquals(255, image.getRGB(x, y) >>> 24, "Hull albedo must be opaque");
        }
    }

    @Test void runtimeParserProducesFiniteInterleavedGpuVertices() throws Exception {
        String obj = resource("assets/kasuga_demo/models/constitution_starship.obj");
        String mtl = resource("assets/kasuga_demo/models/constitution_starship.mtl");
        var mesh = NebulaStarshipRenderer.parseMesh(obj, mtl);
        assertTrue(mesh.triangleCount() >= 1_000 && mesh.triangleCount() <= 30_000);
        assertEquals(mesh.triangleCount() * 3, mesh.vertexCount());
        assertEquals(mesh.vertexCount() * 16, mesh.vertices().length);
        for (float value : mesh.vertices()) assertTrue(Float.isFinite(value));
        assertEquals(-.56f, mesh.minZ(), .0001f);
        assertTrue(mesh.minY() >= -.23f && mesh.maxY() <= .23f);
        assertTrue(mesh.minX() >= -.42f && mesh.maxX() <= .42f);
        assertTrue(mesh.minZ() >= -.66f && mesh.maxZ() <= .66f);
    }

    @Test void standaloneObjMatchesNebulaEnvelopeWithoutDegenerateGeometry() throws Exception {
        String obj = resource("assets/kasuga_demo/models/constitution_starship.obj");
        String mtl = resource("assets/kasuga_demo/models/constitution_starship.mtl");
        assertTrue(obj.contains("mtllib constitution_starship.mtl"));

        List<float[]> positions = new ArrayList<>();
        List<float[]> texCoords = new ArrayList<>();
        List<float[]> normals = new ArrayList<>();
        Set<String> groups = new HashSet<>();
        Set<String> usedMaterials = new HashSet<>();
        int triangles = 0;
        String group = "", material = "";
        for (String raw : obj.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] p = line.split("\\s+");
            switch (p[0]) {
                case "g" -> { group = p[1]; groups.add(group); }
                case "usemtl" -> { material = p[1]; usedMaterials.add(material); }
                case "v" -> positions.add(vec(p, 1));
                case "vt" -> texCoords.add(new float[]{Float.parseFloat(p[1]), Float.parseFloat(p[2])});
                case "vn" -> normals.add(vec(p, 1));
                case "f" -> {
                    assertFalse(group.isEmpty(), "Every OBJ face must belong to a named group");
                    assertFalse(material.isEmpty(), "Every OBJ face must declare a material");
                    List<int[]> corners = new ArrayList<>();
                    for (int i = 1; i < p.length; i++) corners.add(binding(p[i], positions.size(), texCoords.size(), normals.size()));
                    assertEquals(3, corners.size(), "This compact asset must triangulate every face explicitly");
                    float[] a = positions.get(corners.get(0)[0]);
                    float[] b = positions.get(corners.get(1)[0]);
                    float[] c = positions.get(corners.get(2)[0]);
                    assertTrue(area2(a, b, c) > 1e-10f, "Degenerate triangle in group " + group);
                    triangles++;
                }
                default -> { }
            }
        }

        assertTrue(triangles >= 1_000 && triangles <= 30_000,
                "OBJ triangle budget should preserve near-detail without pointless cost: " + triangles);
        assertEquals(positions.size(), texCoords.size(), "Every generated vertex needs a PBR UV");
        for (float[] uv : texCoords) for (float v : uv) assertTrue(Float.isFinite(v));
        for (float[] p : positions) for (float v : p) assertTrue(Float.isFinite(v));
        for (float[] n : normals) {
            float length = (float) Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
            assertTrue(Float.isFinite(length) && length > .98f && length < 1.02f, "OBJ normal is not unit length");
        }

        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        for (float[] p : positions) {
            minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]);
            minY = Math.min(minY, p[1]); maxY = Math.max(maxY, p[1]);
            minZ = Math.min(minZ, p[2]); maxZ = Math.max(maxZ, p[2]);
        }
        assertTrue(minX >= -.42f && maxX <= .42f, "OBJ escaped X envelope: " + minX + ".." + maxX);
        assertTrue(minY >= -.23f && maxY <= .23f, "OBJ escaped Y envelope: " + minY + ".." + maxY);
        assertTrue(minZ >= -.66f && maxZ <= .66f, "OBJ escaped Z envelope: " + minZ + ".." + maxZ);
        assertEquals(-.56f, minZ, .0001f, "Nozzle plane must stay at the CPU particle origin");
        assertTrue(maxZ > .52f, "Saucer did not retain a credible forward silhouette");
        assertTrue(maxX > .32f && Math.abs(minX) > .32f, "Saucer is too narrow for the reference silhouette");
        assertEquals(.255f, NebulaTextures.NACELLE_AXIS_X);
        assertEquals(.115f, NebulaTextures.NACELLE_AXIS_Y);
        assertEquals(-.56f, NebulaTextures.NACELLE_NOZZLE_Z);
        assertTrue(hasVertex(positions, -.255f, .115f, -.56f), "Left OBJ nozzle drifted from the CPU emitter");
        assertTrue(hasVertex(positions, .255f, .115f, -.56f), "Right OBJ nozzle drifted from the CPU emitter");

        assertTrue(groups.contains("saucer_top"));
        assertTrue(groups.contains("bridge"));
        assertTrue(groups.contains("neck"));
        assertTrue(groups.contains("engineering_hull"));
        assertTrue(groups.contains("pylon_left"));
        assertTrue(groups.contains("pylon_right"));
        assertTrue(groups.contains("nacelle_left"));
        assertTrue(groups.contains("nacelle_right"));
        assertTrue(groups.stream().filter(g -> g.startsWith("coil_left_")).count() >= 18,
                "Left nacelle needs readable refit-style warp grille bays");
        assertTrue(groups.stream().filter(g -> g.startsWith("coil_right_")).count() >= 18,
                "Right nacelle needs readable refit-style warp grille bays");

        Map<String, String> mtlColors = new HashMap<>();
        String current = null;
        for (String raw : mtl.split("\\R")) {
            String line = raw.strip();
            if (line.startsWith("newmtl ")) current = line.substring(7);
            else if (current != null && line.startsWith("Kd ")) mtlColors.put(current, line);
        }
        assertFalse(usedMaterials.isEmpty());
        for (String used : usedMaterials) assertTrue(mtlColors.containsKey(used), "Undefined OBJ material " + used);
        for (String map : new String[]{"map_Kd", "map_Bump", "map_Pr", "map_Ke"})
            assertTrue(mtl.contains(map + " constitution_starship_"), "Missing PBR slot " + map);
        assertPbrImage("assets/kasuga_demo/models/constitution_starship_basecolor.png");
        assertPbrImage("assets/kasuga_demo/models/constitution_starship_normal.png");
        assertPbrImage("assets/kasuga_demo/models/constitution_starship_metallic_roughness.png");
        assertPbrImage("assets/kasuga_demo/models/constitution_starship_emissive.png");
    }

    private static float[] vec(String[] parts, int offset) {
        return new float[]{Float.parseFloat(parts[offset]), Float.parseFloat(parts[offset + 1]),
                Float.parseFloat(parts[offset + 2])};
    }

    private static boolean hasVertex(List<float[]> positions, float x, float y, float z) {
        for (float[] p : positions) {
            if (Math.abs(p[0] - x) < .0001f && Math.abs(p[1] - y) < .0001f && Math.abs(p[2] - z) < .0001f)
                return true;
        }
        return false;
    }

    private static int[] binding(String token, int vertices, int texCoords, int normals) {
        String[] parts = token.split("/", -1);
        int vi = Integer.parseInt(parts[0]);
        if (vi < 0) vi = vertices + vi;
        else vi--;
        if (vi < 0 || vi >= vertices) throw new AssertionError("Vertex index out of range: " + token);
        assertTrue(parts.length >= 2 && !parts[1].isEmpty(), "Every face needs a PBR UV");
        int ti = Integer.parseInt(parts[1]);
        if (ti < 0) ti = texCoords + ti;
        else ti--;
        if (ti < 0 || ti >= texCoords) throw new AssertionError("UV index out of range: " + token);
        assertTrue(parts.length >= 3 && !parts[2].isEmpty(), "Every face needs a normal");
        int ni = Integer.parseInt(parts[2]);
        if (ni < 0) ni = normals + ni;
        else ni--;
        if (ni < 0 || ni >= normals) throw new AssertionError("Normal index out of range: " + token);
        return new int[]{vi, ni};
    }

    private static void assertPbrImage(String path) throws Exception {
        var stream = NebulaStarshipObjTest.class.getClassLoader().getResourceAsStream(path);
        assertNotNull(stream, "Missing PBR texture: " + path);
        try (stream) {
            var image = javax.imageio.ImageIO.read(stream);
            assertNotNull(image, "Cannot decode PBR texture: " + path);
            assertEquals(512, image.getWidth());
            assertEquals(512, image.getHeight());
            boolean varying = false;
            int first = image.getRGB(0, 0);
            for (int y = 0; y < image.getHeight() && !varying; y += 16)
                for (int x = 0; x < image.getWidth(); x += 16)
                    if (image.getRGB(x, y) != first) { varying = true; break; }
            assertTrue(varying, "PBR texture is a flat placeholder without usable detail: " + path);
        }
    }

    private static float area2(float[] a, float[] b, float[] c) {
        float ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
        float vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
        float x = uy * vz - uz * vy, y = uz * vx - ux * vz, z = ux * vy - uy * vx;
        return x * x + y * y + z * z;
    }

    private static String resource(String path) throws Exception {
        var stream = NebulaStarshipObjTest.class.getClassLoader().getResourceAsStream(path);
        assertNotNull(stream, "Missing standalone OBJ resource: " + path);
        try (stream) { return new String(stream.readAllBytes(), StandardCharsets.UTF_8); }
    }
}
