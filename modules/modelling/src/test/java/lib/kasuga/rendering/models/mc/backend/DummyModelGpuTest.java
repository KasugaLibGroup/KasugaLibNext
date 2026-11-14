package lib.kasuga.rendering.models.mc.backend;

import lib.kasuga.rendering.models.uml.dynamic.DummyModelInstance;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ForkJoinPool;

import static org.junit.jupiter.api.Assertions.*;

class DummyModelGpuTest {
    @Test
    void directConstructionCreatesNoBonePaletteOrDrawPartsWithoutAGlContext() throws Exception {
        try (var group = new DummyModelInstance()) {
            for (boolean cpuSkinning : new boolean[]{false, true}) {
                try (var backend = new BackendInstance(group, ForkJoinPool.commonPool(), cpuSkinning)) {
                    assertNull(backend.getBoneTransformTBO(), "dummy must not even stage a GPU bone palette");
                    assertNull(backend.getData());
                    for (var pass : ModelRenderPass.values()) {
                        assertFalse(backend.hasPass(pass));
                        assertFalse(backend.prepareForGlobalBatch(pass));
                    }
                }
            }
        }
    }
}
