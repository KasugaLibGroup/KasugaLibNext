package test.kasuga.modelling.demo.cobalt;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class CobaltCoastTest {
    @Test
    void coastKeepsAWadeableShelfAndReachesDeepSeafloor() {
        float shore = CobaltCoast.shoreZ(0);
        assertTrue(CobaltCoast.height(0, shore + 8) > CobaltCoast.SEA_LEVEL - 1.5f,
                "the near shore should remain a shallow walking shelf");
        assertTrue(CobaltCoast.height(0, shore + 220) <= -31.9f,
                "the far ocean floor should be at least 96 blocks below the waterline");
        assertTrue(CobaltCoast.MIN_X <= -48 && CobaltCoast.MAX_X >= 48);
        assertTrue(CobaltCoast.MIN_Z <= -36 && CobaltCoast.MAX_Z >= shore + 220,
                "collision terrain must cover the authored deep coast");
    }

    @Test
    void waterlineAndSixteenthBlockCollisionShareTheAuthoredHeightfield() {
        for (float x = -48; x <= 48; x += 4) {
            float line = CobaltCoast.waterlineZ(x);
            assertEquals(CobaltCoast.SEA_LEVEL, CobaltCoast.height(x, line), .002f,
                    "waterline mismatch at x=" + x);
        }

        for (float x = -47.5f; x < 48; x += 3.75f) {
            for (float z = -35.5f; z < 224; z += 7.25f) {
                float exact = CobaltCoast.height(x, z);
                float collision = CobaltCoast.collisionHeight(x, z);
                float blockTop = CobaltCoast.topBlockY(x, z) + CobaltCoast.topLayers(x, z) / 16f;
                assertEquals(collision, blockTop, 1e-6f, "block shape top mismatch at " + x + "," + z);
                assertTrue(Math.abs(exact - collision) <= 1f / 32f,
                        "collision quantization exceeds 1/32 block at " + x + "," + z);
            }
        }
    }

    @Test
    void stageDimensionIsAnUnsealedVoidThatContainsTheDeepSeabed() throws IOException {
        try (var dimensionStream = CobaltCoastTest.class.getResourceAsStream(
                "/data/kasuga_demo/dimension/cobalt_blue_ocean.json");
             var typeStream = CobaltCoastTest.class.getResourceAsStream(
                     "/data/kasuga_demo/dimension_type/cobalt_ocean_stage.json")) {
            assertNotNull(dimensionStream, "missing deep-ocean stage dimension");
            assertNotNull(typeStream, "missing deep-ocean dimension type");
            var dimension = JsonParser.parseReader(new InputStreamReader(dimensionStream, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            var type = JsonParser.parseReader(new InputStreamReader(typeStream, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            assertEquals("kasuga_demo:cobalt_ocean_stage", dimension.get("type").getAsString());
            var layers = dimension.getAsJsonObject("generator").getAsJsonObject("settings").getAsJsonArray("layers");
            assertEquals(1, layers.size());
            assertEquals("minecraft:air", layers.get(0).getAsJsonObject().get("block").getAsString());
            assertTrue(type.get("min_y").getAsInt() <= CobaltCoast.DEEP_SEABED);
            assertTrue(type.get("min_y").getAsInt() + type.get("height").getAsInt() > 65);
        }
    }
}
