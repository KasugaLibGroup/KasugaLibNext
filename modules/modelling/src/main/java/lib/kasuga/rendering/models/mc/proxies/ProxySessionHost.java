package lib.kasuga.rendering.models.mc.proxies;

import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyAttachToken;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxySession;
import org.jetbrains.annotations.Nullable;

/**
 * MC host-facing lifecycle seam for the new proxy kernel.
 *
 * <p>This interface deliberately has no {@code StateMachine<?>} accessor. Hosts expose an explicit
 * attach token and only forward lifecycle/tick events.
 */
public interface ProxySessionHost {

    ProxyAttachToken proxyAttachToken();

    @Nullable ProxySession<?, ?, ?, ?, ?> proxySession();

    void proxySession(@Nullable ProxySession<?, ?, ?, ?, ?> session);

    default void closeProxySession() {
        ProxySession<?, ?, ?, ?, ?> session = proxySession();
        proxySession(null);
        if (session != null) {
            session.close();
        }
    }
}
