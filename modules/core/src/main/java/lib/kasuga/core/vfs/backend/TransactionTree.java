package lib.kasuga.core.vfs.backend;

import lib.kasuga.core.vfs.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.util.*;

/** Sparse planning overlay. Only queried paths and explicitly enumerated source subtrees are loaded. */
final class TransactionTree {
    private static final class Node {
        final boolean directory;
        final VfsPath original;
        final VfsWriteTransaction.ContentSource content;
        final Map<String, Node> children = new TreeMap<>();
        boolean loaded;
        Node(boolean directory, VfsPath original, VfsWriteTransaction.ContentSource content) {
            this.directory = directory; this.original = original; this.content = content;
        }
    }

    private final VirtualFileSystem backend;
    private final Node root = new Node(true, VfsPath.ROOT, null);

    TransactionTree(VirtualFileSystem backend) { this.backend = backend; }

    private Node child(Node parent, String name) throws IOException {
        if (!parent.directory) throw new FileAlreadyExistsException(name, null, "Ancestor is a file");
        if (!parent.children.containsKey(name) && !parent.loaded && parent.original != null) {
            VfsPath original = parent.original.resolve(name);
            Node node = backend.isDirectory(original) ? new Node(true, original, null)
                    : backend.isRegularFile(original) ? new Node(false, original, () -> backend.openRead(original)) : null;
            parent.children.put(name, node);
        }
        return parent.children.get(name);
    }

    private Node get(VfsPath path) throws IOException {
        Node node = root;
        for (String part : path.segments()) {
            if (node == null || !node.directory) return null;
            node = child(node, part);
        }
        return node;
    }

    void put(VfsPath path, byte[] bytes) throws IOException { putStream(path, () -> new ByteArrayInputStream(bytes)); }

    void putStream(VfsPath path, VfsWriteTransaction.ContentSource source) throws IOException {
        if (path.isRoot()) throw new FileAlreadyExistsException("/");
        Node parent = directory(path.parent());
        Node old = child(parent, path.fileName());
        if (old != null && old.directory) throw new FileAlreadyExistsException(path.toString());
        parent.children.put(path.fileName(), new Node(false, null, source));
    }

    private Node directory(VfsPath path) throws IOException {
        Node node = root;
        if (path == null) return node;
        for (String part : path.segments()) {
            Node next = child(node, part);
            if (next == null) { next = new Node(true, null, null); next.loaded = true; node.children.put(part, next); }
            if (!next.directory) throw new FileAlreadyExistsException(path.toString(), null, "Ancestor is a file");
            node = next;
        }
        return node;
    }

    void mkdirs(VfsPath path) throws IOException { directory(path); }

    void delete(VfsPath path) throws IOException {
        if (path.isRoot()) throw new VfsException("Cannot delete root");
        Node parent = get(path.parent());
        if (parent != null) {
            if (!parent.directory) return;
            parent.children.put(path.fileName(), null);
        }
    }

    void move(VfsPath from, VfsPath to) throws IOException {
        if (from.isRoot() || to.isRoot()) throw new VfsException("Cannot move root");
        if (from.equals(to)) return;
        Node source = get(from);
        if (source == null) throw new NoSuchFileException(from.toString());
        if (from.startsWith(to) || source.directory && to.startsWith(from)) throw new VfsException("Invalid move: " + from + " -> " + to);
        Node destinationParent = directory(to.parent());
        get(from.parent()).children.put(from.fileName(), null);
        destinationParent.children.put(to.fileName(), source);
    }

    private void children(Node node) throws IOException {
        if (node.loaded) return;
        if (node.original != null) for (String name : backend.list(node.original)) child(node, name);
        node.loaded = true;
    }

    private record Copy(VfsPath path, boolean directory, VfsWriteTransaction.ContentSource content) {}
    private void collect(Node node, VfsPath target, List<Copy> copies) throws IOException {
        copies.add(new Copy(target, node.directory, node.content));
        if (node.directory) {
            children(node);
            for (var entry : node.children.entrySet()) if (entry.getValue() != null) collect(entry.getValue(), target.resolve(entry.getKey()), copies);
        }
    }

    void copyTo(VfsPath from, TransactionTree destination, VfsPath to, VfsWriteTransaction destinationTx) throws IOException {
        Node source = get(from);
        if (source == null) throw new NoSuchFileException(from.toString());
        List<Copy> copies = new ArrayList<>(); collect(source, to, copies);
        destination.delete(to); destinationTx.delete(to);
        for (Copy copy : copies) {
            if (copy.directory()) { destination.mkdirs(copy.path()); destinationTx.mkdirs(copy.path()); }
            else {
                destination.putStream(copy.path(), copy.content());
                destinationTx.putStream(copy.path(), copy.content());
            }
        }
    }
}
