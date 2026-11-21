package lib.kasuga.core.resource.platform;

import lib.kasuga.core.resource.source.InputPolicy;
import lib.kasuga.core.resource.source.ResourceSnapshot;
import lib.kasuga.core.resource.source.ResourceSource;
import lib.kasuga.core.resource.source.ResourceSourceDescriptor;
import lib.kasuga.core.resource.source.ResourceSources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FixedResourceSourceTest {
    @TempDir
    Path root;

    @Test
    void repeatedCaptureUsesOriginalBytesAndIgnoresSourceFileChanges() throws Exception {
        Path file = root.resolve("data/example/value.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"value\":\"original\"}", StandardCharsets.UTF_8);

        ResourceSourceDescriptor descriptor = new ResourceSourceDescriptor(
                "example",
                "example-pack",
                "main",
                7,
                ResourceSourceDescriptor.Origin.EXTERNAL
        );
        ResourceSource fixed = ResourceSources.fixed(
                descriptor,
                ResourceSources.directory(descriptor, root, InputPolicy.DEFAULT),
                InputPolicy.DEFAULT
        );

        try (fixed) {
            ResourceSnapshot first = fixed.capture();
            byte[] firstBytes = first.files().getFirst().readBytes();
            Files.writeString(file, "{\"value\":\"changed!\"}", StandardCharsets.UTF_8);
            first.close();

            ResourceSnapshot second = fixed.capture();
            assertArrayEquals(firstBytes, second.files().getFirst().readBytes());
            second.close();

            ResourceSnapshot third = fixed.capture();
            assertArrayEquals(firstBytes, third.files().getFirst().readBytes());
            third.close();
        }

        assertThrows(Exception.class, fixed::capture);
    }
}
