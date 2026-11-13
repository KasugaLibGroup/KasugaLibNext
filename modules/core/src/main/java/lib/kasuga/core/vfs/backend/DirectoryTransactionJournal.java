package lib.kasuga.core.vfs.backend;

import lib.kasuga.core.vfs.VfsPath;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** Before-images of the minimal affected subtrees, including missing parents and empty directories. */
final class DirectoryTransactionJournal {
    private record BeforeImage(Path target, Path backup, boolean existed) {}

    private final Path journalDir;
    private final List<BeforeImage> images = new ArrayList<>();

    DirectoryTransactionJournal(Path root, List<VfsPath> affected) throws IOException {
        TreeSet<VfsPath> roots = new TreeSet<>();
        for (VfsPath path : affected) {
            VfsPath cursor = VfsPath.ROOT;
            for (String segment : path.segments()) {
                cursor = cursor.resolve(segment);
                if (!Files.exists(root.resolve(cursor.asString()), LinkOption.NOFOLLOW_LINKS)) break;
            }
            if (!cursor.isRoot()) roots.add(cursor);
        }
        journalDir = roots.isEmpty() ? null : Files.createTempDirectory(root, VfsSupport.TEMP_FILE_PREFIX + "journal-");
        try {
            for (VfsPath path : roots) {
                Path target = root.resolve(path.asString());
                if (images.stream().anyMatch(image -> target.startsWith(image.target()))) continue;
                boolean existed = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
                Path backup = journalDir.resolve(Integer.toString(images.size()));
                if (existed) copyTree(target, backup);
                images.add(new BeforeImage(target, backup, existed));
            }
        } catch (IOException | RuntimeException failure) {
            try { cleanup(); } catch (IOException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
    }

    void restore() throws IOException {
        IOException failure = null;
        for (int i = images.size() - 1; i >= 0; i--) {
            BeforeImage image = images.get(i);
            try {
                // Do not try to replace unchanged files in a directory that rejected the write.
                if (image.existed() && sameTree(image.target(), image.backup())) continue;
                deleteTree(image.target());
                if (image.existed()) copyTree(image.backup(), image.target());
            } catch (IOException recoveryFailure) {
                if (failure == null) failure = new IOException("VFS recovery incomplete; before-images retained at " + journalDir);
                failure.addSuppressed(recoveryFailure);
            }
        }
        if (failure != null) throw failure;
        cleanup();
    }

    void cleanup() throws IOException {
        if (journalDir != null) deleteTree(journalDir);
    }

    private static boolean sameTree(Path first, Path second) throws IOException {
        if (!Files.exists(first, LinkOption.NOFOLLOW_LINKS)) return false;
        if (Files.isSymbolicLink(first) || Files.isSymbolicLink(second)) {
            return Files.isSymbolicLink(first) && Files.isSymbolicLink(second)
                    && Files.readSymbolicLink(first).equals(Files.readSymbolicLink(second));
        }
        boolean directory = Files.isDirectory(first, LinkOption.NOFOLLOW_LINKS);
        if (directory != Files.isDirectory(second, LinkOption.NOFOLLOW_LINKS)) return false;
        if (!Files.getLastModifiedTime(first).equals(Files.getLastModifiedTime(second))) return false;
        var firstPosix = Files.getFileAttributeView(first, PosixFileAttributeView.class);
        var secondPosix = Files.getFileAttributeView(second, PosixFileAttributeView.class);
        if (firstPosix != null && secondPosix != null
                && !firstPosix.readAttributes().permissions().equals(secondPosix.readAttributes().permissions())) return false;
        if (!directory) return Files.mismatch(first, second) == -1L;
        List<String> firstChildren;
        List<String> secondChildren;
        try (var entries = Files.list(first)) { firstChildren = entries.map(p -> p.getFileName().toString()).sorted().toList(); }
        try (var entries = Files.list(second)) { secondChildren = entries.map(p -> p.getFileName().toString()).sorted().toList(); }
        if (!firstChildren.equals(secondChildren)) return false;
        for (String child : firstChildren) if (!sameTree(first.resolve(child), second.resolve(child))) return false;
        return true;
    }

    private static void copyTree(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectory(target.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file)), LinkOption.NOFOLLOW_LINKS, StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                if (failure != null) throw failure;
                BasicFileAttributes attrs = Files.readAttributes(dir, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                Files.getFileAttributeView(target.resolve(source.relativize(dir)), BasicFileAttributeView.class)
                        .setTimes(attrs.lastModifiedTime(), attrs.lastAccessTime(), attrs.creationTime());
                var posix = Files.getFileAttributeView(dir, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                if (posix != null) {
                    Files.getFileAttributeView(target.resolve(source.relativize(dir)), PosixFileAttributeView.class)
                            .setPermissions(posix.readAttributes().permissions());
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteTree(Path target) throws IOException {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(target, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                clearReadOnly(dir);
                // Only journal copies or affected subtrees being restored reach this helper.
                var posix = Files.getFileAttributeView(dir, PosixFileAttributeView.class);
                if (posix != null) {
                    var permissions = new java.util.HashSet<>(posix.readAttributes().permissions());
                    if (permissions.add(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE)) posix.setPermissions(permissions);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isSymbolicLink()) clearReadOnly(file);
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void clearReadOnly(Path path) throws IOException {
        var dos = Files.getFileAttributeView(path, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (dos != null && dos.readAttributes().isReadOnly()) dos.setReadOnly(false);
    }
}
