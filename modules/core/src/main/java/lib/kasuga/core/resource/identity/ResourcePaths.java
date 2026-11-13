package lib.kasuga.core.resource.identity;

import java.util.Objects;

/** Validation before the deliberately permissive legacy VfsPath adapter. */
public final class ResourcePaths {
    private ResourcePaths() {}

    public static String path(String value, boolean allowRoot) {
        Objects.requireNonNull(value, "path");
        if (value.isEmpty() && allowRoot) return value;
        if (value.isEmpty() || value.startsWith("/") || value.endsWith("/"))
            throw new IllegalArgumentException("UNSAFE_PATH: expected a relative path: " + value);
        for (String part : value.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals(".."))
                throw new IllegalArgumentException("UNSAFE_PATH: empty or dot segment");
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if (c < 32 || c == 127 || c == '\\' || c == ':' || c == '?' || c == '#')
                    throw new IllegalArgumentException("UNSAFE_PATH: forbidden character");
                if (Character.isSurrogate(c)) {
                    if (!Character.isHighSurrogate(c) || i + 1 == part.length()
                            || !Character.isLowSurrogate(part.charAt(++i)))
                        throw new IllegalArgumentException("UNSAFE_PATH: malformed Unicode");
                }
            }
        }
        return value;
    }

    public static String id(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-][A-Za-z0-9._-]{0,127}")
                || value.equals(".") || value.equals(".."))
            throw new IllegalArgumentException("Invalid source/root/owner ID: " + value);
        return value;
    }

    public static boolean contains(String prefix, String path) {
        return prefix.isEmpty() || path.equals(prefix) || path.startsWith(prefix + "/");
    }

    public static String join(String prefix, String relative) {
        return prefix.isEmpty() ? relative : relative.isEmpty() ? prefix : prefix + "/" + relative;
    }
}
