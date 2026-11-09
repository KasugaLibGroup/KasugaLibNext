package lib.kasuga.rendering.models.uml.dynamic.proxy;

/** Applies a host command immediately before the next observed logical step. */
@FunctionalInterface
public interface ProxyCommandPort<T, C> {

    void accept(T element, C command);

    static <T, C> ProxyCommandPort<T, C> none() {
        return (element, command) -> {
        };
    }
}
