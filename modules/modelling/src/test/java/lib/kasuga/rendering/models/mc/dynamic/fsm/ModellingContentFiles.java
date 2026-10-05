package lib.kasuga.rendering.models.mc.dynamic.fsm;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads the modelling contentTesting JSON files straight from the working tree, so reload-domain tests
 * can exercise the <em>real</em> shipped files (index, clip, state machine) instead of in-test copies.
 *
 * <p>The files are ordinary tracked resources of the contentTesting source set — not local model
 * assets — so reading them is safe for CI. The path is resolved by walking up from the test working
 * directory to the repository root (the same technique used by
 * {@code KsgBbModelLoaderSkeletonTest.findFanAsset}), which works whether Gradle runs the test from the
 * module directory or the repository root.
 */
final class ModellingContentFiles {

    private static final Path MODULE = Path.of("modules/modelling");
    private static final String RESOURCES = "src/contentTesting/resources/data/kasuga_lib";

    /** The index manifest that declares both the registration and the reload domain. */
    static final String INDEX = RESOURCES + "/kasuga_lib/data_driven/modelling.json";

    /** The block content file listed under {@code on_register}. */
    static final String BLOCK_CONTENT = RESOURCES + "/kasuga_lib_content/fan_formula_data_driven.json";

    /** The state machine file found by the {@code state_machines/} directory glob. */
    static final String MACHINE = RESOURCES + "/state_machines/fan_machine_data_driven.json";

    /** The clip file listed under {@code on_reload}. */
    static final String CLIP = RESOURCES + "/animation_clips/fan_fsm_data_driven.json";

    static String read(String relativeToModelling) {
        try {
            return Files.readString(locate(relativeToModelling));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static JsonElement readJson(String relativeToModelling) {
        return JsonParser.parseString(read(relativeToModelling));
    }

    private static Path locate(String relativeToModelling) {
        Path relative = MODULE.resolve(relativeToModelling);
        for (Path dir = Path.of(System.getProperty("user.dir")); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve(relative);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("cannot locate " + relative
                + " from working dir " + System.getProperty("user.dir"));
    }

    private ModellingContentFiles() {
    }
}
