package lib.kasuga.rendering.models.uml.dynamic.proxy;

import java.util.Objects;

/** Immutable registration entry. Per-element state belongs to the session, never the definition. */
public record ProxyDefinition<T, I, O, C, F>(
        ProxyKey<T> key,
        ProxyTemplate<T, I, O, C, F> template
) {

    public ProxyDefinition {
        key = Objects.requireNonNull(key, "key");
        template = Objects.requireNonNull(template, "template");
    }
}
