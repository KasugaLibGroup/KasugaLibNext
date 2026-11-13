package lib.kasuga.core.resource.pack;

import lib.kasuga.core.resource.identity.ResourcePaths;
import lib.kasuga.core.resource.routing.ResourceRouting;
import lib.kasuga.core.resource.source.ResourceSnapshot;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Legacy package resolver adapter over one captured provider, retaining exact generation bytes. */
public final class CapturedScopedPackResources implements HierarchicalScopedPackResources, AutoCloseable {
    private record Entry(ResourceSnapshot.LocatedResourceFile file, ResourceSnapshot.ResourceLease lease) {}
    private final String name;
    private final NavigableMap<String, Entry> files = new TreeMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    public CapturedScopedPackResources(String name, List<ResourceRouting.Route> routes) {
        this.name = name;
        try {
            for (var route : routes) {
                if (files.containsKey(route.targetPath())) throw new IllegalArgumentException("Duplicate package path");
                files.put(route.targetPath(), new Entry(route.file(), route.file().lease()));
            }
        } catch (RuntimeException error) { close(); throw error; }
    }
    public Set<String> paths() { return Collections.unmodifiableSet(files.navigableKeySet()); }
    private String path(String prefix, String path) { return ResourcePaths.join(ResourcePaths.path(prefix, true), ResourcePaths.path(path, true)); }
    @Override public InputStream open(String prefix, String path) throws IOException {
        if (closed.get()) throw new IOException("STALE_REFERENCE: package closed");
        Entry file = files.get(path(prefix, path));
        if (file == null) throw new FileNotFoundException("Package file unavailable");
        return file.file().open(file.lease());
    }
    @Override public boolean exists(String prefix, String path) { return isRegularFile(prefix, path) || isDirectory(prefix, path); }
    @Override public boolean isRegularFile(String prefix, String path) { return !closed.get() && files.containsKey(path(prefix, path)); }
    @Override public boolean isDirectory(String prefix, String path) {
        String directory = path(prefix, path); String child = files.ceilingKey(directory.isEmpty() ? "" : directory + "/");
        return !closed.get() && child != null && (directory.isEmpty() || child.startsWith(directory + "/"));
    }
    @Override public List<String> list(String prefix, String path) {
        String directory = path(prefix, path); String start = directory.isEmpty() ? "" : directory + "/";
        Set<String> children = new TreeSet<>();
        for (String file : files.tailMap(start).keySet()) {
            if (!file.startsWith(start)) break;
            String relative = file.substring(start.length()); int slash = relative.indexOf('/');
            children.add(slash < 0 ? relative : relative.substring(0, slash));
        }
        return List.copyOf(children);
    }
    @Override public String getName() { return name; }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        UncheckedIOException error = null;
        for (Entry file : files.values()) try { file.lease().close(); }
        catch (IOException e) { if (error == null) error = new UncheckedIOException(e); else error.addSuppressed(e); }
        if (error != null) throw error;
    }
}
