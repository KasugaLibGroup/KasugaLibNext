package lib.kasuga.rendering.models.uml.dynamic.fsm;

import com.mojang.serialization.Codec;

import java.util.Objects;

/**
 * The FSM identity primitive — a pure-Java {@code namespace:path} identifier mirroring the subset of
 * {@code net.minecraft.resources.ResourceLocation}'s contract that the FSM uses, so the uml.fsm package has
 * zero {@code net.minecraft.*} dependency. Invariants match ResourceLocation: namespace in {@code [a-z0-9_.-]+}
 * (default {@code "minecraft"}), path in {@code [a-z0-9_./-]+}; canonical form {@code "namespace:path"}.
 *
 * <p>{@link #CODEC} is a plain string codec ({@code Codec.STRING.xmap(parse, toString)}) so the JSON encoding
 * is byte-identical to {@code ResourceLocation.CODEC} — important because {@code FsmDefinitions.hashOf} hashes
 * the codec-encoded form for sync identity, and the hash must not drift across the RL→Id change.
 */
public final class Id implements Comparable<Id> {

    private static final String DEFAULT_NAMESPACE = "minecraft";

    private final String namespace;
    private final String path;

    private Id(String namespace, String path) {
        this.namespace = namespace;
        this.path = path;
    }

    public static Id fromNamespaceAndPath(String namespace, String path) {
        if (namespace == null || namespace.isEmpty() || !isValidNamespace(namespace)) {
            throw new IllegalArgumentException("Invalid id namespace: " + namespace);
        }
        if (path == null || path.isEmpty() || !isValidPath(path)) {
            throw new IllegalArgumentException("Invalid id path: " + path);
        }
        return new Id(namespace, path);
    }

    /** Mirror {@code ResourceLocation.parse} — throws on invalid input. */
    public static Id parse(String input) {
        Id id = tryParse(input);
        if (id == null) {
            throw new IllegalArgumentException("Invalid id (expected namespace:path): " + input);
        }
        return id;
    }

    /** Mirror {@code ResourceLocation.tryParse} — null on invalid input (or null/empty input). */
    public static Id tryParse(String input) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        int colon = input.indexOf(':');
        String namespace;
        String path;
        if (colon >= 0) {
            namespace = input.substring(0, colon);
            path = input.substring(colon + 1);
            if (namespace.isEmpty()) {
                // ":path" — empty namespace is not valid (the default-namespace rule only applies to bare paths)
                return null;
            }
        } else {
            namespace = DEFAULT_NAMESPACE;
            path = input;
        }
        if (!isValidNamespace(namespace) || !isValidPath(path)) {
            return null;
        }
        return new Id(namespace, path);
    }

    public String getNamespace() {
        return namespace;
    }

    public String getPath() {
        return path;
    }

    @Override
    public String toString() {
        return namespace + ":" + path;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Id other)) {
            return false;
        }
        return namespace.equals(other.namespace) && path.equals(other.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(namespace, path);
    }

    @Override
    public int compareTo(Id other) {
        int c = namespace.compareTo(other.namespace);
        return c != 0 ? c : path.compareTo(other.path);
    }

    /**
     * DFU codec — a plain string codec so JSON bytes match {@code ResourceLocation.CODEC} exactly (keeps the
     * sync definition-hash stable across the RL→Id change). Not a structured {@code {namespace,path}} object.
     */
    public static final Codec<Id> CODEC = Codec.STRING.xmap(Id::parse, Id::toString);

    private static boolean isValidNamespace(String namespace) {
        for (int i = 0; i < namespace.length(); i++) {
            if (!isNamespaceChar(namespace.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidPath(String path) {
        for (int i = 0; i < path.length(); i++) {
            if (!isPathChar(path.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isNamespaceChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-';
    }

    private static boolean isPathChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-' || c == '/';
    }
}
