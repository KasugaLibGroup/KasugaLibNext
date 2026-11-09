package lib.kasuga.rendering.models.uml.dynamic.proxy;

import java.util.Objects;

/**
 * Typed registration key. The generic parameter prevents a caller from accidentally attaching a
 * definition to the wrong element family while the domain/kind/path remain serializable
 * diagnostics.
 */
public record ProxyKey<T>(String domain, ProxyKind kind, String path) {

    public ProxyKey {
        domain = requirePart(domain, "domain");
        kind = Objects.requireNonNull(kind, "kind");
        path = requirePart(path, "path");
    }

    public static <T> ProxyKey<T> blockEntity(String domain, String path) {
        return new ProxyKey<>(domain, ProxyKind.BLOCK_ENTITY, path);
    }

    public static <T> ProxyKey<T> entity(String domain, String path) {
        return new ProxyKey<>(domain, ProxyKind.ENTITY, path);
    }

    public static <T> ProxyKey<T> item(String domain, String path) {
        return new ProxyKey<>(domain, ProxyKind.ITEM, path);
    }

    public static <T> ProxyKey<T> external(String domain, String path) {
        return new ProxyKey<>(domain, ProxyKind.EXTERNAL, path);
    }

    public String id() {
        return domain + ":" + kind.name().toLowerCase() + "/" + path;
    }

    private static String requirePart(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
