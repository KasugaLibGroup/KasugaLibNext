package lib.kasuga.core.vfs.backend;

import lib.kasuga.core.vfs.*;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** Lazy-content planning view for cross-backend moves, including earlier staged operations. */
final class TransactionTree {
    @FunctionalInterface
    private interface Content { byte[] read() throws IOException; }
    private record Node(boolean directory, Content content) {}

    private final TreeMap<VfsPath, Node> nodes = new TreeMap<>();

    TransactionTree(VirtualFileSystem backend) throws IOException {
        load(backend, VfsPath.ROOT);
    }

    private void load(VirtualFileSystem backend, VfsPath path) throws IOException {
        boolean directory = backend.isDirectory(path);
        if (!directory && !backend.isRegularFile(path)) return;
        nodes.put(path, new Node(directory, directory ? null : () -> backend.readBytes(path)));
        if (directory) for (String child : backend.list(path)) load(backend, path.resolve(child));
    }

    void put(VfsPath path, byte[] bytes) throws IOException {
        if (path.isRoot() || nodes.containsKey(path) && nodes.get(path).directory()) {
            throw new FileAlreadyExistsException(path.toString());
        }
        mkdirs(path.parent());
        nodes.put(path, new Node(false, () -> bytes));
    }

    void mkdirs(VfsPath path) throws IOException {
        if (path == null || path.isRoot()) return;
        mkdirs(path.parent());
        Node old = nodes.get(path);
        if (old != null && !old.directory()) throw new FileAlreadyExistsException(path.toString());
        nodes.putIfAbsent(path, new Node(true, null));
    }

    void delete(VfsPath path) throws IOException {
        if (path.isRoot()) throw new VfsException("Cannot delete root");
        nodes.keySet().removeIf(candidate -> candidate.startsWith(path));
    }

    void move(VfsPath from, VfsPath to) throws IOException {
        Node source = requireSource(from);
        if (from.equals(to)) return;
        if (from.isRoot() || from.startsWith(to) || source.directory() && to.startsWith(from)) {
            throw new VfsException("Invalid move: " + from + " -> " + to);
        }
        var subtree = nodes.entrySet().stream().filter(e -> e.getKey().startsWith(from))
                .map(e -> java.util.Map.entry(e.getKey(), e.getValue())).toList();
        mkdirs(to.parent());
        delete(to);
        delete(from);
        for (var entry : subtree) nodes.put(to.resolve(from.relativize(entry.getKey())), entry.getValue());
    }

    void copyTo(VfsPath from, TransactionTree destination, VfsPath to, VfsWriteTransaction destinationTx) throws IOException {
        requireSource(from);
        // Read sources before changing either view; lazy sources can refer to original disk paths.
        record Copy(VfsPath target, boolean directory, byte[] bytes) {}
        List<Copy> copies = new ArrayList<>();
        for (var entry : nodes.entrySet()) {
            if (!entry.getKey().startsWith(from)) continue;
            Node node = entry.getValue();
            copies.add(new Copy(to.resolve(from.relativize(entry.getKey())), node.directory(),
                    node.directory() ? null : node.content().read()));
        }
        destination.delete(to);
        destinationTx.delete(to);
        for (Copy copy : copies) {
            if (copy.directory()) {
                destination.mkdirs(copy.target()); destinationTx.mkdirs(copy.target());
            } else {
                destination.put(copy.target(), copy.bytes()); destinationTx.putBytes(copy.target(), copy.bytes());
            }
        }
    }

    private Node requireSource(VfsPath path) throws IOException {
        Node node = nodes.get(path);
        if (node == null) throw new NoSuchFileException(path.toString());
        return node;
    }
}
