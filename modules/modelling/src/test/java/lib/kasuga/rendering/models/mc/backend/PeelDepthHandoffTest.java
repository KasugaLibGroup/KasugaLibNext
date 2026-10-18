package lib.kasuga.rendering.models.mc.backend;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

/** Source contract; actual fixed24/float32 GPU coverage lives in peel_depth_handoff.c. */
class PeelDepthHandoffTest {
    @Test
    void finalResolveUsesPinnedNearestDepthAndDoesNotTouchUncoveredPixels() throws Exception {
        try (var resource = getClass().getResourceAsStream("/assets/kasuga_lib/shaders/core/ksglib_peel_resolve.fsh")) {
            assertNotNull(resource);
            String shader = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
            int main = shader.indexOf("void main()");
            int initialDepth = shader.indexOf("gl_FragDepth = gl_FragCoord.z;", main);
            int branch = shader.indexOf("if (WriteDepth != 0)", main);
            int empty = shader.indexOf("if (fragColor.a <= 0.0) discard;", branch);
            int nearest = shader.indexOf("gl_FragDepth = texelFetch(NearestDepth, pixel, 0).r;", empty);
            assertTrue(initialDepth > main && initialDepth < branch);
            assertTrue(empty > branch && nearest > empty);
        }
    }
}
