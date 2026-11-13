package lib.kasuga.core.vfs.backend;

import lib.kasuga.core.vfs.VfsPath;

import java.util.*;

/** Ordered logical paths with bounded descendant ranges and an optional direct-child index. */
final class PathIndex<V> {
    record Entry<V>(VfsPath path, V value) {}
    private final NavigableMap<String, Entry<V>> entries = new TreeMap<>();
    private final Map<String, NavigableSet<String>> children;

    PathIndex() { this(true); }
    PathIndex(boolean indexChildren) { children = indexChildren ? new HashMap<>() : null; }

    V get(VfsPath path) { Entry<V> entry = entries.get(path.asString()); return entry == null ? null : entry.value(); }
    boolean containsKey(VfsPath path) { return entries.containsKey(path.asString()); }
    Collection<Entry<V>> entries() { return entries.values(); }

    void put(VfsPath path, V value) {
        if (entries.put(path.asString(), new Entry<>(path, value)) == null && children != null && !path.isRoot()) {
            children.computeIfAbsent(path.parent().asString(), ignored -> new TreeSet<>()).add(path.fileName());
        }
    }

    V remove(VfsPath path) {
        Entry<V> removed = entries.remove(path.asString());
        if (removed != null && children != null && !path.isRoot()) {
            String parent = path.parent().asString();
            NavigableSet<String> names = children.get(parent);
            if (names != null) { names.remove(path.fileName()); if (names.isEmpty()) children.remove(parent); }
        }
        return removed == null ? null : removed.value();
    }

    List<String> children(VfsPath path) {
        NavigableSet<String> names = children.get(path.asString());
        return names == null ? List.of() : List.copyOf(names);
    }

    List<Entry<V>> subtree(VfsPath path) {
        if (path.isRoot()) return new ArrayList<>(entries.values());
        List<Entry<V>> found = new ArrayList<>();
        Entry<V> self = entries.get(path.asString());
        if (self != null) found.add(self);
        // '/' is immediately below '0'; a-b/a.b lie before this interval, a0 lies after it.
        found.addAll(entries.subMap(path.asString() + "/", true, path.asString() + "0", false).values());
        return found;
    }

    void removeSubtree(VfsPath path) { for (Entry<V> entry : subtree(path)) remove(entry.path()); }
    void putAll(Collection<Entry<V>> values) { for (Entry<V> entry : values) put(entry.path(), entry.value()); }

    static List<VfsPath> minimalRoots(Collection<VfsPath> candidates) {
        Set<VfsPath> chosen = new HashSet<>();
        List<VfsPath> result = new ArrayList<>();
        for (VfsPath path : new TreeSet<>(candidates)) {
            boolean covered = false;
            for (VfsPath parent = path.parent(); parent != null; parent = parent.parent()) {
                if (chosen.contains(parent)) { covered = true; break; }
            }
            if (!covered) { chosen.add(path); result.add(path); }
        }
        return result;
    }
}
