package lib.kasuga.test.registration.data_driven.source;

import lib.kasuga.registration.data_driven.source.ContentSource;
import lib.kasuga.registration.data_driven.source.DirectoryContentSource;
import lib.kasuga.registration.data_driven.source.LogicalPath;
import lib.kasuga.registration.data_driven.source.SourceAccessException;
import lib.kasuga.registration.data_driven.source.SourceDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DirectoryContentSourceTest {
    private static final SourceDescriptor DESCRIPTOR =
            SourceDescriptor.of("directory", "example", List.of("example"));

    @Test
    void enumeratesFilesStablyAndStillSupportsTheExistingEntryLayout(@TempDir Path root) throws IOException {
        write(root, "data/example/kasuga_lib/data_driven/b.json", "{}");
        write(root, "data/example/kasuga_lib/data_driven/a.json", "{}");
        write(root, "data/example/models/z.json", "model");
        write(root, "assets/example/lang/en_us.json", "{}");

        try (ContentSource source = DirectoryContentSource.open(root, DESCRIPTOR)) {
            assertEquals(List.of(
                            "assets/example/lang/en_us.json",
                            "data/example/kasuga_lib/data_driven/a.json",
                            "data/example/kasuga_lib/data_driven/b.json",
                            "data/example/models/z.json"),
                    source.files().stream().map(file -> file.path().value()).toList());
            assertEquals(List.of(
                            "data/example/kasuga_lib/data_driven/a.json",
                            "data/example/kasuga_lib/data_driven/b.json"),
                    source.indexes().stream().map(file -> file.path().value()).toList());

            try (InputStream input = source.open(LogicalPath.of("data/example/models/z.json")).orElseThrow()) {
                assertEquals("model", new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
            assertFalse(source.exists(LogicalPath.of("data/example/models/missing.json")));
            assertEquals(Optional.empty(), source.open(LogicalPath.of("data/example/models/missing.json")));
        }
    }

    @Test
    void closingIsIdempotentAndPreventsFurtherUse(@TempDir Path root) throws IOException {
        write(root, "data/example/file.json", "{}");
        ContentSource source = DirectoryContentSource.open(root, DESCRIPTOR);

        source.close();
        source.close();

        SourceAccessException error = assertThrows(SourceAccessException.class,
                () -> source.open(LogicalPath.of("data/example/file.json")));
        assertEquals(SourceAccessException.Reason.SOURCE_CLOSED, error.reason());
        assertThrows(IllegalStateException.class, source::files);
    }

    @Test
    void rejectsSymbolicLinksInTheTree(@TempDir Path root) throws IOException {
        Path target = write(root, "target.txt", "secret");
        Path link = root.resolve("data/example/link.txt");
        Files.createDirectories(link.getParent());
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException exception) {
            assumeTrue(false, "symbolic links are unavailable: " + exception);
            return;
        }

        SourceAccessException error = assertThrows(SourceAccessException.class,
                () -> DirectoryContentSource.open(root, DESCRIPTOR));
        assertEquals(SourceAccessException.Reason.SYMLINK_REJECTED, error.reason());
        assertTrue(Files.isSymbolicLink(link));
    }

    private static Path write(Path root, String path, String value) throws IOException {
        Path target = root.resolve(path);
        Files.createDirectories(target.getParent());
        Files.writeString(target, value);
        return target;
    }
}
