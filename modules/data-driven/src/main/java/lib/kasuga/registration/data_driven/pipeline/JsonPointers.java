package lib.kasuga.registration.data_driven.pipeline;

import java.util.Objects;

/** RFC 6901 helpers for definition-relative JSON pointers. */
public final class JsonPointers {
    private JsonPointers() {
    }

    public static String append(String base, String relative) {
        Objects.requireNonNull(base, "base");
        if (relative == null || relative.isEmpty()) {
            return base;
        }
        return relative.startsWith("/") ? base + relative : base + "/" + escape(relative);
    }

    public static String escape(String token) {
        Objects.requireNonNull(token, "token");
        return token.replace("~", "~0").replace("/", "~1");
    }
}
