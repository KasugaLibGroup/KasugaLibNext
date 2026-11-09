package lib.kasuga.rendering.models.uml.dynamic.proxy;

import java.util.Objects;
import java.util.UUID;

/** Explicit identity of one attach attempt, independent of the element or definition. */
public record ProxyAttachToken(UUID value) {

    public ProxyAttachToken {
        value = Objects.requireNonNull(value, "value");
    }

    public static ProxyAttachToken random() {
        return new ProxyAttachToken(UUID.randomUUID());
    }

    public static ProxyAttachToken of(UUID value) {
        return new ProxyAttachToken(value);
    }

    public static ProxyAttachToken parse(String value) {
        return of(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
