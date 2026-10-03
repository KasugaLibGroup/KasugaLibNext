package lib.kasuga.rendering.models.uml.dynamic.multiplexer;

@FunctionalInterface
public interface VariantFactory<V extends Variant<V>> {

    V create(String id);
}
