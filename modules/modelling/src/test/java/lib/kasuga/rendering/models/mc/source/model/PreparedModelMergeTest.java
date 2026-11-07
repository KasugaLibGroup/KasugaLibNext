package lib.kasuga.rendering.models.mc.source.model;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstanceFixture;
import lib.kasuga.rendering.models.uml.structure.Model;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PreparedModelMergeTest {
    @Test void sharedCachedParentsAreIdempotentButDifferentDefinitionsStillConflict() {
        try (var parent = ModelInstanceFixture.minimal(); var child = ModelInstanceFixture.minimal()) {
            Map<String, Model> prepared = new HashMap<>();
            KasugaModelManager.mergePrepared(prepared, Map.of("parent", parent.getModel()), "parent.json");
            assertDoesNotThrow(() -> KasugaModelManager.mergePrepared(prepared,
                    Map.of("parent", parent.getModel(), "child", child.getModel()), "child.json"));
            assertEquals(2, prepared.size());
            assertThrows(IllegalStateException.class, () -> KasugaModelManager.mergePrepared(prepared,
                    Map.of("parent", child.getModel()), "conflicting.json"));
            assertSame(parent.getModel(), prepared.get("parent"));
        }
    }
}
