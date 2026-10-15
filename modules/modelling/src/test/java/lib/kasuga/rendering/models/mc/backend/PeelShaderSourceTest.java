package lib.kasuga.rendering.models.mc.backend;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PeelShaderSourceTest {
    @Test
    void preservesVersionAndLightingAndOnlyWrapsEntryPoint() {
        String source = "#version 330 core\nvoid main() { fragColor = fog(lighting()); }";
        String wrapped = PeelShaderSource.wrap(source);
        assertTrue(wrapped.startsWith("#version 330 core"));
        assertTrue(wrapped.contains("void ksg_original_main() { fragColor = fog(lighting()); }"));
        assertTrue(wrapped.contains("gl_FragCoord.z <= texelFetch(ksg_PeelPrevious"));
        assertTrue(wrapped.contains("gl_FragCoord.z > texelFetch(ksg_PeelScene"));
        assertTrue(wrapped.contains("fragColor.rgb *= fragColor.a"));
        assertTrue(wrapped.contains("texelFetch(ksg_PeelCoverage, pixel, 0).a >= 1.0"));
        assertTrue(wrapped.contains("ksg_PeelEnabled == 3 && texelFetch(ksg_PeelFootprint, pixel, 0).r > 0.0"));
        assertTrue(wrapped.contains("texelFetch(ksg_PeelFootprint, pixel, 0).r == 0.0"));
        // Ordinary terrain outside the footprint must keep straight alpha.
        assertTrue(wrapped.contains("if (ksg_PeelEnabled == 1) {\n        if (fragColor.a"));
        assertEquals(wrapped, PeelShaderSource.wrap(wrapped));
    }

    @Test
    void depthWriteIsUnconditionalAndUsesTheComparedValue() throws java.io.IOException {
        String terrain = PeelShaderSource.wrap("#version 330 core\nvoid main() { fragColor = vec4(1); }");
        String model;
        try (var resource = getClass().getResourceAsStream("/assets/kasuga_lib/shaders/core/ksglib_main.fsh")) {
            assertNotNull(resource);
            model = new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        for (String source : new String[]{terrain, model}) {
            int main = source.lastIndexOf("void main() {");
            int write = source.indexOf("gl_FragDepth = gl_FragCoord.z;", main);
            int branch = source.indexOf("if (", main);
            assertTrue(write > main && write < branch, "Depth must be assigned before any conditional/return");
        }
    }

    @Test
    void modelSamplesAlphaBeforeDivergentPeelDiscard() throws java.io.IOException {
        String source;
        try (var resource = getClass().getResourceAsStream("/assets/kasuga_lib/shaders/core/ksglib_main.fsh")) {
            assertNotNull(resource);
            source = new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        int main = source.indexOf("void main() {");
        int albedo = source.indexOf("vec4 albedo = texture(Sampler0, texCoord);", main);
        int reject = source.indexOf("discard;", main);
        int peel = source.indexOf("if (ksg_PeelEnabled == 1)", main);
        int lighting = source.indexOf("vec3 normalTexture", main);
        assertTrue(albedo > main && albedo < reject,
                "Atlas alpha/parallax sampling must precede per-pixel rejection");
        assertTrue(peel > albedo && peel < lighting,
                "Reject hidden layers before expensive lighting, but after material sampling");
        assertFalse(source.contains("ksg_PeelEnabled == 6"));
        assertFalse(source.contains("ksg_PeelEnabled == 7"));
    }

    @Test
    void failsClosedForUnexpectedShaderEntryPoint() {
        assertThrows(IllegalArgumentException.class, () -> PeelShaderSource.wrap("#version 330 core\n"));
    }
}
