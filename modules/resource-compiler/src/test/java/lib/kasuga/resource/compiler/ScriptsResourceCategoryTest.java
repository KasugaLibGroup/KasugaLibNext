package lib.kasuga.resource.compiler;

import lib.kasuga.resource.category.AllResourceCategory;
import lib.kasuga.resource.category.ResourceCategory;
import lib.kasuga.resource.model.SourceResource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptsResourceCategoryTest {

    @Test
    void scriptsCategoryPreservesThePackagePath() {
        ResourceCategory category = AllResourceCategory.get("scripts");

        assertNotNull(category);
        assertEquals(
                Path.of("scripts/packages/qj-motion/index.js"),
                category.getResourceTargetPath(new SourceResource(
                        category,
                        Path.of("packages/qj-motion/index.js"),
                        Path.of("index.js").toFile(),
                        "kuayue"
                ))
        );
        assertTrue(category.getTransformers().isEmpty());
    }

    @Test
    void scannerCopiesScriptsFromAModuleRoot(@TempDir Path temporaryDirectory)
            throws Exception {
        Path moduleRoot = temporaryDirectory.resolve("bogeys/qj");
        Path packageRoot = moduleRoot.resolve("scripts/packages/qj-motion");
        Files.createDirectories(packageRoot);
        Files.writeString(moduleRoot.resolve("module.toml"), "");
        Files.writeString(packageRoot.resolve("index.js"), "module.exports = {};");

        var grouped = ResourceScanner.scan(Set.of(moduleRoot), "kuayue");

        Path target = Path.of("scripts/packages/qj-motion/index.js");
        assertTrue(grouped.containsKey(target));
        assertEquals(1, grouped.get(target).size());
    }
}
