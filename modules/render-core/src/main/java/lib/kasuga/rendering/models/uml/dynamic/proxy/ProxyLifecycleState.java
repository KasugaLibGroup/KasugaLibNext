package lib.kasuga.rendering.models.uml.dynamic.proxy;

/** Public lifecycle state of one proxy session. */
public enum ProxyLifecycleState {
    NEW,
    ACTIVE,
    SUSPENDED,
    RESOURCE_WAIT,
    CLOSED
}
