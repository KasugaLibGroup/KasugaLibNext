package lib.kasuga.rendering.models.uml.dynamic.data;

import org.jetbrains.annotations.Nullable;

/**
 * String-keyed property bag — the shared abstraction between the collaborator-proxy system
 * ({@code mc.proxies}, which reads foreign block/entity data via reflection) and the new FSM's
 * typed {@code ParameterStore} (which implements this interface so the proxies keep compiling
 * unchanged).
 *
 * <p>Relocated verbatim from {@code lib.kasuga.rendering.models.uml.dynamic.state_machine.DataProvider}
 * (signature preserved). The old state_machine package was fully removed; this interface survives
 * because the proxy layer depends on it.
 */
public interface DataProvider {

    @Nullable Object getValue(String name);

    boolean setValue(String name, Object value);

    boolean canGet(String name);

    boolean canSet(String name, Object value);

    boolean has(String name);

    Class<?> getType(String name);
}
