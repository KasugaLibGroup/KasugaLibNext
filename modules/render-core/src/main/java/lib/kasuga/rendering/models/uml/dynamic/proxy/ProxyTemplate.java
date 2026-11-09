package lib.kasuga.rendering.models.uml.dynamic.proxy;

/** Stateless content-side factory for one element's proxy composition. */
@FunctionalInterface
public interface ProxyTemplate<T, I, O, C, F> {

    ProxyComposition<T, I, O, C, F> create(T element);
}
