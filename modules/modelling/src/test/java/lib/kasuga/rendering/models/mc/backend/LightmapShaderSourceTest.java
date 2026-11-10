package lib.kasuga.rendering.models.mc.backend;

import net.minecraft.client.renderer.LightTexture;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LightmapShaderSourceTest {

    @Test
    void minecraftPackedLightUsesSixteenStepComponents() {
        int packed = LightTexture.pack(15, 15);

        assertEquals(240, packed & 0xffff);
        assertEquals(240, (packed >>> 16) & 0xffff);
        assertEquals(15, (packed & 0xffff) / 16);
        assertEquals(15, ((packed >>> 16) & 0xffff) / 16);
    }

    @Test
    void modelShadersScalePackedLightComponentsToSixteenTexelCoordinates() throws IOException {
        String main = resource("/assets/kasuga_lib/shaders/core/ksglib_main.vsh");
        String globalBatch = resource("/assets/kasuga_lib/shaders/core/ksglib_global_batch.vsh");
        String fragment = resource("/assets/kasuga_lib/shaders/core/ksglib_main.fsh");

        assertTrue(main.contains("texelFetch(Sampler2, ksg_PackedLight / 16, 0)"));
        assertTrue(globalBatch.contains("texelFetch(Sampler2, ivec2(packedLighting.xy) / 16, 0)"));
        assertTrue(fragment.contains("float albedoVisibilityFloor"));
    }

    private static String resource(String path) throws IOException {
        try (var stream = LightmapShaderSourceTest.class.getResourceAsStream(path)) {
            if (stream == null) throw new IOException("Missing resource: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
