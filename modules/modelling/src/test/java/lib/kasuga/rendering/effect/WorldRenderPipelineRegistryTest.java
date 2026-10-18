package lib.kasuga.rendering.effect;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldRenderPipelineRegistryTest {

    @Test
    void ordersPipelinesByPriorityThenIdAndSeparatesStages() {
        PipelineRegistryCore<String, String> registry = new PipelineRegistryCore<>();
        ResourceLocation late = id("late");
        ResourceLocation alpha = id("alpha");
        ResourceLocation beta = id("beta");
        ResourceLocation otherStage = id("other_stage");

        try (var ignoredLate = registry.register(owner(), late, "world", 100, DuplicatePolicy.FAIL, "late");
             var ignoredBeta = registry.register(owner(), beta, "world", 10, DuplicatePolicy.FAIL, "beta");
             var ignoredAlpha = registry.register(owner(), alpha, "world", 10, DuplicatePolicy.FAIL, "alpha");
             var ignoredOther = registry.register(owner(), otherStage, "post", 0, DuplicatePolicy.FAIL, "other")
        ) {
            List<ResourceLocation> ids = registry.entries("world")
                    .stream()
                    .map(PipelineRegistryCore.Entry::id)
                    .toList();

            assertEquals(List.of(alpha, beta, late), ids);
        }
    }

    @Test
    void staleRegistrationCannotRemoveItsReplacement() {
        PipelineRegistryCore<String, String> registry = new PipelineRegistryCore<>();
        ResourceLocation id = id("replace");
        var oldRegistration = registry.register(owner(), id, "world", 0, DuplicatePolicy.FAIL, "old");
        assertThrows(IllegalStateException.class, () ->
                registry.register(owner(), id, "post", 0, DuplicatePolicy.FAIL, "duplicate"));
        var replacement = registry.register(owner(), id, "post", 0, DuplicatePolicy.REPLACE, "new");

        try {
            oldRegistration.close();
            assertTrue(registry.isRegistered(id));

            replacement.close();
            assertFalse(registry.isRegistered(id));
        } finally {
            registry.unregister(owner(), id);
        }
    }

    @Test
    void versionChangesOnlyWhenRegistryContentsChange() {
        PipelineRegistryCore<String, String> registry = new PipelineRegistryCore<>();
        ResourceLocation value = id("versioned");
        long emptyVersion = registry.version();
        var registration = registry.register(
                owner(), value, "world", 0, DuplicatePolicy.FAIL, "pipeline"
        );

        assertTrue(registry.version() > emptyVersion);
        assertEquals(List.of(value), registry.entries().stream()
                .map(PipelineRegistryCore.Entry::id)
                .toList());

        long registeredVersion = registry.version();
        assertFalse(registry.unregister(owner(), id("missing")));
        assertEquals(registeredVersion, registry.version());

        registration.close();
        assertTrue(registry.version() > registeredVersion);
        long closedVersion = registry.version();
        registration.close();
        assertEquals(closedVersion, registry.version());
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("kasuga_pipeline_test", path);
    }

    private static ResourceLocation owner() {
        return id("owner");
    }
}
