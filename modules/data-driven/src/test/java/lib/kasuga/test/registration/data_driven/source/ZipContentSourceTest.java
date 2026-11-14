package lib.kasuga.test.registration.data_driven.source;

import lib.kasuga.registration.data_driven.source.ContentSource;
import lib.kasuga.registration.data_driven.source.DirectoryContentSource;
import lib.kasuga.registration.data_driven.source.LogicalPath;
import lib.kasuga.registration.data_driven.source.SourceAccessException;
import lib.kasuga.registration.data_driven.source.SourceDescriptor;
import lib.kasuga.registration.data_driven.source.SourceLimits;
import lib.kasuga.registration.data_driven.source.ZipContentSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ZipContentSourceTest {
    private static final SourceDescriptor DESCRIPTOR =
            SourceDescriptor.of("zip", "example", List.of("example"));

    @Test
    void readsTheSameLogicalLayoutAsADirectoryAndEnclosesTheStream(@TempDir Path tempDir) throws IOException {
        Path zip = tempDir.resolve("pack.zip");
        try (ZipOutputStream output = new ZipOutputStream(java.nio.file.Files.newOutputStream(zip))) {
            entry(output, "data/example/models/b.json", "b");
            entry(output, "data/example/models/a.json", "a");
        }

        ContentSource source = ZipContentSource.open(zip, DESCRIPTOR);
        assertEquals(List.of(
                        "data/example/models/a.json",
                        "data/example/models/b.json"),
                source.files().stream().map(file -> file.path().value()).toList());
        InputStream input = source.open(LogicalPath.of("data/example/models/a.json")).orElseThrow();
        try (input) {
            assertEquals("a", new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        source.close();
        source.close();

        SourceAccessException error = assertThrows(SourceAccessException.class,
                () -> source.open(LogicalPath.of("data/example/models/a.json")));
        assertEquals(SourceAccessException.Reason.SOURCE_CLOSED, error.reason());
    }

    @Test
    void directoryAndZipSourcesExposeEquivalentLogicalFiles(@TempDir Path tempDir) throws IOException {
        Path directory = tempDir.resolve("directory");
        java.nio.file.Files.createDirectories(directory.resolve("data/example/models"));
        java.nio.file.Files.writeString(directory.resolve("data/example/models/main.json"), "{}");
        java.nio.file.Files.createDirectories(directory.resolve("assets/example/lang"));
        java.nio.file.Files.writeString(directory.resolve("assets/example/lang/en_us.json"), "{}");

        Path zip = tempDir.resolve("equivalent.zip");
        try (ZipOutputStream output = new ZipOutputStream(java.nio.file.Files.newOutputStream(zip))) {
            entry(output, "data/example/models/main.json", "{}");
            entry(output, "assets/example/lang/en_us.json", "{}");
        }

        try (ContentSource directorySource = DirectoryContentSource.open(directory, DESCRIPTOR);
             ContentSource zipSource = ZipContentSource.open(zip, DESCRIPTOR)) {
            assertEquals(
                    directorySource.files().stream().map(file -> file.path().value()).toList(),
                    zipSource.files().stream().map(file -> file.path().value()).toList()
            );
            for (var file : directorySource.files()) {
                try (InputStream directoryInput = directorySource.requireOpen(file.path());
                     InputStream zipInput = zipSource.requireOpen(file.path())) {
                    assertArrayEquals(directoryInput.readAllBytes(), zipInput.readAllBytes(), file.path().value());
                }
            }
        }
    }

    @Test
    void rejectsDuplicateEntries(@TempDir Path tempDir) throws IOException {
        Path zip = tempDir.resolve("duplicate.zip");
        try (ZipOutputStream output = new ZipOutputStream(java.nio.file.Files.newOutputStream(zip))) {
            entry(output, "data/example/value.json", "{}");
        }
        SourceTestSupport.duplicateOnlyEntry(zip);

        SourceAccessException error = assertThrows(SourceAccessException.class,
                () -> ZipContentSource.open(zip, DESCRIPTOR));
        assertEquals(SourceAccessException.Reason.DUPLICATE_ENTRY, error.reason());
    }

    @Test
    void rejectsDangerousNamesAndDirectoryEntriesCarryingContent(@TempDir Path tempDir) throws IOException {
        Path traversal = zip(tempDir.resolve("traversal.zip"), output ->
                entry(output, "../escape.json", "{}"));
        assertEquals(SourceAccessException.Reason.INVALID_ENTRY,
                assertThrows(SourceAccessException.class,
                        () -> ZipContentSource.open(traversal, DESCRIPTOR)).reason());

        Path directoryWithContent = zip(tempDir.resolve("directory-content.zip"), output ->
                entry(output, "data/example/directory/", "not-empty"));
        assertEquals(SourceAccessException.Reason.INVALID_ENTRY,
                assertThrows(SourceAccessException.class,
                        () -> ZipContentSource.open(directoryWithContent, DESCRIPTOR)).reason());
    }

    @Test
    void rejectsSymbolicLinkEntries(@TempDir Path tempDir) throws IOException {
        Path zip = zip(tempDir.resolve("symlink.zip"), output ->
                entry(output, "data/example/link.json", "../../secret"));
        SourceTestSupport.markUnixEntry(zip, "data/example/link.json", 0120777);

        SourceAccessException error = assertThrows(SourceAccessException.class,
                () -> ZipContentSource.open(zip, DESCRIPTOR));
        assertEquals(SourceAccessException.Reason.SYMLINK_REJECTED, error.reason());
    }

    @Test
    void rejectsSpecialEntriesAndAmbiguousFileDirectories(@TempDir Path tempDir) throws IOException {
        Path special = zip(tempDir.resolve("special.zip"), output ->
                entry(output, "data/example/pipe", ""));
        SourceTestSupport.markUnixEntry(special, "data/example/pipe", 0010777);
        assertEquals(SourceAccessException.Reason.INVALID_ENTRY,
                assertThrows(SourceAccessException.class,
                        () -> ZipContentSource.open(special, DESCRIPTOR)).reason());

        Path ambiguous = zip(tempDir.resolve("ambiguous.zip"), output -> {
            entry(output, "data/example/node", "file");
            entry(output, "data/example/node/child.json", "{}");
        });
        assertEquals(SourceAccessException.Reason.INVALID_ENTRY,
                assertThrows(SourceAccessException.class,
                        () -> ZipContentSource.open(ambiguous, DESCRIPTOR)).reason());
    }

    @Test
    void rejectsTruncatedArchives(@TempDir Path tempDir) throws IOException {
        Path truncated = tempDir.resolve("truncated.zip");
        java.nio.file.Files.writeString(truncated, "not a zip");

        SourceAccessException error = assertThrows(SourceAccessException.class,
                () -> ZipContentSource.open(truncated, DESCRIPTOR));
        assertEquals(SourceAccessException.Reason.CORRUPT_ARCHIVE, error.reason());
    }

    @Test
    void enforcesActualInflatedBytesAndFileCount(@TempDir Path tempDir) throws IOException {
        Path tooLarge = zip(tempDir.resolve("too-large.zip"), output ->
                entry(output, "data/example/main.json", "x".repeat(32)));
        SourceLimits limits = new SourceLimits(16, 64, 128, 10);
        assertEquals(SourceAccessException.Reason.LIMIT_EXCEEDED,
                assertThrows(SourceAccessException.class,
                        () -> ZipContentSource.open(tooLarge, DESCRIPTOR, limits)).reason());

        Path tooMany = zip(tempDir.resolve("too-many.zip"), output -> {
            entry(output, "data/example/a.json", "{}");
            entry(output, "data/example/b.json", "{}");
        });
        SourceLimits fileCountLimit = new SourceLimits(16, 64, 128, 1);
        assertEquals(SourceAccessException.Reason.LIMIT_EXCEEDED,
                assertThrows(SourceAccessException.class,
                        () -> ZipContentSource.open(tooMany, DESCRIPTOR, fileCountLimit)).reason());

        Path tooLargeInTotal = zip(tempDir.resolve("too-large-in-total.zip"), output -> {
            entry(output, "data/example/a.json", "x".repeat(12));
            entry(output, "data/example/b.json", "x".repeat(12));
        });
        SourceLimits totalLimit = new SourceLimits(16, 16, 20, 10);
        assertEquals(SourceAccessException.Reason.LIMIT_EXCEEDED,
                assertThrows(SourceAccessException.class,
                        () -> ZipContentSource.open(tooLargeInTotal, DESCRIPTOR, totalLimit)).reason());
    }

    private static Path zip(Path path, ThrowingConsumer<ZipOutputStream> writer) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(java.nio.file.Files.newOutputStream(path))) {
            writer.accept(output);
        }
        return path;
    }

    private static void entry(ZipOutputStream output, String name, String value) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        output.putNextEntry(entry);
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
    }

    @FunctionalInterface
    private interface ThrowingConsumer<T> {
        void accept(T value) throws IOException;
    }
}
