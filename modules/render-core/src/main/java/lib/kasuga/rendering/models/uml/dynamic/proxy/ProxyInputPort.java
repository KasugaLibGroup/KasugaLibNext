package lib.kasuga.rendering.models.uml.dynamic.proxy;

/** Reads one immutable element observation for the next logical step. */
@FunctionalInterface
public interface ProxyInputPort<T, I> {

    I observe(T element);
}
