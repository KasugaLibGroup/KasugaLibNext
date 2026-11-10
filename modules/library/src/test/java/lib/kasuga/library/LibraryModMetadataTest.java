package lib.kasuga.library;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LibraryModMetadataTest {

    @Test
    void aggregateMetadataLoadsAllMixinConfigs() throws IOException {
        String metadata = resource("META-INF/neoforge.mods.toml");

        assertTrue(metadata.contains("config=\"kasuga_lib.mixins.json\""));
        assertTrue(metadata.contains("config=\"kasuga_lib_create.mixins.json\""));
    }

    @Test
    void createMixinConfigDeclaresBezierConnectionMixinOnClient() throws IOException {
        String config = resource("kasuga_lib_create.mixins.json");

        assertTrue(config.contains("client.BezierConnectionMixin"));
    }

    private static String resource(String path) throws IOException {
        try (InputStream stream = LibraryModMetadataTest.class.getClassLoader().getResourceAsStream(path)) {
            if (stream == null) {
                throw new IOException("Missing resource " + path);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
