package lib.kasuga.registration.data_driven.source;

import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A stable, read-only logical view of one content source.
 *
 * <p>The file list is fixed when the source is opened. Callers own every returned stream and must
 * close it before closing the source. A source must not be reopened after {@link #close()}.
 */
public interface ContentSource extends AutoCloseable {
    SourceDescriptor descriptor();

    /**
     * Opens a file from the source's fixed file list.
     *
     * @return the stream, or empty when the logical path is not present in this source
     */
    Optional<InputStream> open(LogicalPath path) throws IOException;

    boolean exists(LogicalPath path);

    /**
     * Returns the immutable, path-sorted logical file list.
     */
    List<LocatedFile> files();

    /**
     * Returns index manifests directly below
     * {@code data/<declared-namespace>/kasuga_lib/data_driven/}.
     */
    default List<LocatedFile> indexes() {
        return files().stream()
                .filter(file -> isIndex(file))
                .sorted(Comparator.comparing(file -> file.path().value()))
                .toList();
    }

    default InputStream requireOpen(LogicalPath path) throws IOException {
        return open(path).orElseThrow(() -> new SourceAccessException(
                SourceAccessException.Reason.NOT_FOUND,
                descriptor(),
                path,
                "logical file does not exist: " + path
        ));
    }

    @Override
    default void close() throws IOException {
    }

    private boolean isIndex(LocatedFile file) {
        List<String> segments = file.path().segments();
        return segments.size() == 5
                && segments.get(0).equals("data")
                && descriptor().namespaces().contains(segments.get(1))
                && segments.get(2).equals("kasuga_lib")
                && segments.get(3).equals("data_driven")
                && segments.get(4).toLowerCase(Locale.ROOT).endsWith(".json");
    }
}
