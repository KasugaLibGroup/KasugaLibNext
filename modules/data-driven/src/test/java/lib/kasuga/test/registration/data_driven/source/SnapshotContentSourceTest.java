package lib.kasuga.test.registration.data_driven.source;

import lib.kasuga.core.resource.source.InputPolicy;
import lib.kasuga.core.resource.source.ResourceSnapshot;
import lib.kasuga.core.resource.source.ResourceSource;
import lib.kasuga.core.resource.source.ResourceSourceDescriptor;
import lib.kasuga.core.resource.source.ResourceSources;
import lib.kasuga.registration.data_driven.source.ContentSource;
import lib.kasuga.registration.data_driven.source.ContentSourceFactory;
import lib.kasuga.registration.data_driven.source.LogicalPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SnapshotContentSourceTest {
    @TempDir
    Path root;

    @Test
    void exposesCapturedFilesAndIndexesThroughPlatformIdentity() throws Exception {
        Path index = root.resolve("data/example/kasuga_lib/data_driven/main.json");
        Files.createDirectories(index.getParent());
        Files.writeString(index, "{\"on_register\":[]}", StandardCharsets.UTF_8);

        ResourceSourceDescriptor resource = new ResourceSourceDescriptor(
                "example",
                "example-pack",
                "main",
                9,
                ResourceSourceDescriptor.Origin.EXTERNAL
        );
        ResourceSnapshot snapshot;
        try (ResourceSource source = ResourceSources.directory(
                resource,
                root,
                InputPolicy.DEFAULT
        )) {
            snapshot = source.capture();
        }

        try (ContentSource source = ContentSourceFactory.snapshot(
                snapshot,
                resource,
                List.of("example"),
                "example snapshot"
        )) {
            assertEquals("example-pack", source.descriptor().sourceId());
            assertEquals("main", source.descriptor().rootId());
            assertEquals(9, source.descriptor().rank());
            assertEquals(
                    "data/example/kasuga_lib/data_driven/main.json",
                    source.indexes().getFirst().path().value()
            );
            assertEquals(
                    "main",
                    source.indexes().getFirst().uri().rootId()
            );
            assertFalse(source.indexes().getFirst().revision().isBlank());
            try (InputStream input = source.open(
                    LogicalPath.of("data/example/kasuga_lib/data_driven/main.json")
            ).orElseThrow()) {
                assertEquals(
                        "{\"on_register\":[]}",
                        new String(input.readAllBytes(), StandardCharsets.UTF_8)
                );
            }
        }
    }
}
