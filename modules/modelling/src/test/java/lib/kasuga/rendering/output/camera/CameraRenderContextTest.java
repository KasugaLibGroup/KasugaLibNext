package lib.kasuga.rendering.output.camera;

import lib.kasuga.rendering.output.mc.CameraRenderContext;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class CameraRenderContextTest {
    @Test void nestedContextsRestoreAndWorkersDoNotReadRenderThreadState() {
        var first = CameraRenderSettings.defaults();
        var second = new CameraRenderSettings(2, CameraRenderSettings.Quality.FAST, CameraRenderSettings.Shader.disabled());
        assertNull(CameraRenderContext.current());
        try (var ignored = new CameraRenderContext(first)) {
            assertSame(first, CameraRenderContext.current());
            CompletableFuture.runAsync(() -> {
                assertNull(CameraRenderContext.current());
                try (var worker = new CameraRenderContext(second)) { assertSame(second, CameraRenderContext.current()); }
                assertNull(CameraRenderContext.current());
            }).join();
            try (var nested = new CameraRenderContext(second)) { assertSame(second, CameraRenderContext.current()); }
            assertSame(first, CameraRenderContext.current());
        }
        assertNull(CameraRenderContext.current());
    }
    @Test void settingsDefensivelyCopyAndValidate() {
        var source = new java.util.HashMap<String, String>(); source.put("quality", "high");
        var shader = new CameraRenderSettings.Shader(Path.of("shaders.zip"), source); source.clear();
        assertEquals("high", shader.options().get("quality"));
        assertThrows(UnsupportedOperationException.class, () -> shader.options().put("x", "y"));
        var settings = new CameraRenderSettings(8, CameraRenderSettings.Quality.FANCY, shader, List.of(Path.of("textures.zip")), 4, false);
        assertTrue(settings.resourcePacks().getFirst().isAbsolute());
        assertThrows(IllegalArgumentException.class, () -> new CameraRenderSettings(1, settings.quality(), shader));
        assertThrows(IllegalArgumentException.class, () -> new CameraRenderSettings(33, settings.quality(), shader));
        assertThrows(IllegalArgumentException.class, () -> new CameraRenderSettings(8, settings.quality(), shader, List.of(), 5, true));
    }
}
