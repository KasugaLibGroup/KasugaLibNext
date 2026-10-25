package lib.kasuga.rendering.models.mc.proxies;

import lib.kasuga.rendering.models.uml.math.Transform;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Supplier;

public class Proxies {
    private static final Set<ProxyInstance<?>> LIVE =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private Proxies() {}

    public static <T> ProxyInstance<T> install(T element, ElementProxy<T> template) {
        return new ProxyInstance<>(element, element, template, null, null);
    }

    public static <T> ProxyInstance<T> installExternal(Object owner,
                                                       T element,
                                                       ElementProxy<T> template,
                                                       ProxyIdentity identity,
                                                       @Nullable Supplier<Transform> root) {
        return new ProxyInstance<>(owner, element, template, identity ,root);
    }

    static void remember(ProxyInstance<?> instance) {
        LIVE.add(instance);
    }

    static void forget(ProxyInstance<?> instance) {
        LIVE.remove(instance);
    }

    public static List<ProxyInstance<?>> all() {
        synchronized (LIVE) {
            return List.copyOf(LIVE);
        }
    }
}
