package lib.kasuga.registration.data_driven.shape;

import lib.kasuga.geometry.Shape;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable result of compiling a complete shape definition domain.
 */
public final class ShapeCatalog {
    private final Map<String, Entry> entries;

    public ShapeCatalog(Map<String, Entry> entries) {
        Objects.requireNonNull(entries, "entries");
        this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    public Shape get(String id) {
        Entry entry = entries.get(id);
        return entry == null ? null : entry.shape();
    }

    public Entry entry(String id) {
        return entries.get(id);
    }

    public boolean contains(String id) {
        return entries.containsKey(id);
    }

    public Map<String, Entry> entries() {
        return entries;
    }

    public record Entry(String id, String output, Shape shape) {
        public Entry {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(output, "output");
            Objects.requireNonNull(shape, "shape");
        }
    }
}
