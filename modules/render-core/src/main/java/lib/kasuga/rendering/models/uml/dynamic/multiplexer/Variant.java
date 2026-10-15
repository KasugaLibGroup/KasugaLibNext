package lib.kasuga.rendering.models.uml.dynamic.multiplexer;

/**
 * A node in a {@link Multiplexer} graph. Concrete variants are self-typed ({@code V extends Variant<V>})
 * so the builder can return the typed handle and it can be captured directly in transition definitions.
 *
 * <p>The variant object itself is typically the payload that the owner reads after selection; subclasses
 * may attach extra data (model references, material overrides, etc.).
 */
public class Variant<V extends Variant<V>> {

    private final String id;

    public Variant(String id) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("variant id required");
        }
        this.id = id;
    }

    public String id() {
        return id;
    }

    /** The concrete variant handle; equivalent to a typed {@code this}. */
    @SuppressWarnings("unchecked")
    public V self() {
        return (V) this;
    }
}
