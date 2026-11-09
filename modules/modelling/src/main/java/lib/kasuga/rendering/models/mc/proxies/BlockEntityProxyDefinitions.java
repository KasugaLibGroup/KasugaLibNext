package lib.kasuga.rendering.models.mc.proxies;

import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyAttachToken;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyDefinition;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyKey;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyRuntime;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxySession;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyTemplate;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Registration-time association between a block-entity type and its proxy definition.
 *
 * <p>The association is kept by type identity so the generic ticker never reconstructs a key from
 * the registry or duplicates registration metadata. Content declares the definition once with its
 * known resource id; the first host tick asks this catalog to attach it.
 */
public final class BlockEntityProxyDefinitions {

    private static final Object LOCK = new Object();
    private static final Map<Object, ProxyDefinition<?, ?, ?, ?, ?>> DEFINITIONS =
            new IdentityHashMap<>();

    private BlockEntityProxyDefinitions() {
    }

    public static <T extends BlockEntity, I, O, C, F> ProxyDefinition<T, I, O, C, F> register(
            BlockEntityType<T> type,
            ResourceLocation id,
            ProxyTemplate<T, I, O, C, F> template
    ) {
        Objects.requireNonNull(type, "type");
        return registerForType(type, id, template);
    }

    static <T, I, O, C, F> ProxyDefinition<T, I, O, C, F> registerForType(
            Object type,
            ResourceLocation id,
            ProxyTemplate<T, I, O, C, F> template
    ) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(template, "template");
        ProxyDefinition<T, I, O, C, F> definition = new ProxyDefinition<>(
                ProxyKey.blockEntity(id.getNamespace(), id.getPath()),
                template);
        synchronized (LOCK) {
            if (DEFINITIONS.containsKey(type)) {
                throw new IllegalStateException("block-entity type already has a proxy definition: "
                        + id);
            }
            ProxyRuntime.GLOBAL.register(definition);
            DEFINITIONS.put(type, definition);
        }
        return definition;
    }

    public static Optional<ProxyDefinition<?, ?, ?, ?, ?>> definition(
            BlockEntityType<?> type
    ) {
        Objects.requireNonNull(type, "type");
        return definitionForType(type);
    }

    static Optional<ProxyDefinition<?, ?, ?, ?, ?>> definitionForType(Object type) {
        Objects.requireNonNull(type, "type");
        synchronized (LOCK) {
            return Optional.ofNullable(DEFINITIONS.get(type));
        }
    }

    public static <T extends BlockEntity, I, O, C, F>
    Optional<ProxySession<T, I, O, C, F>> attach(
            BlockEntityType<T> type,
            T element,
            ProxyAttachToken token
    ) {
        Objects.requireNonNull(type, "type");
        return attachForType(type, element, token);
    }

    static <T, I, O, C, F> Optional<ProxySession<T, I, O, C, F>> attachForType(
            Object type,
            T element,
            ProxyAttachToken token
    ) {
        Objects.requireNonNull(element, "element");
        Objects.requireNonNull(token, "token");
        ProxyDefinition<?, ?, ?, ?, ?> found = definitionForType(type).orElse(null);
        if (found == null) {
            return Optional.empty();
        }
        ProxyDefinition<T, I, O, C, F> definition = narrow(found);
        ensureRuntimeRegistered(definition);
        return ProxyRuntime.GLOBAL.attach(definition, element, token);
    }

    public static void unregister(BlockEntityType<?> type) {
        Objects.requireNonNull(type, "type");
        unregisterType(type);
    }

    static void unregisterType(Object type) {
        Objects.requireNonNull(type, "type");
        ProxyDefinition<?, ?, ?, ?, ?> definition;
        synchronized (LOCK) {
            definition = DEFINITIONS.remove(type);
        }
        if (definition != null) {
            unregisterRuntimeDefinition(definition);
        }
    }

    /** Removes the catalog entries owned by this helper; live sessions remain host-owned. */
    static void clear() {
        synchronized (LOCK) {
            for (ProxyDefinition<?, ?, ?, ?, ?> definition : DEFINITIONS.values()) {
                unregisterRuntimeDefinition(definition);
            }
            DEFINITIONS.clear();
        }
    }

    public static Set<BlockEntityType<?>> registeredTypes() {
        @SuppressWarnings("unchecked")
        Set<BlockEntityType<?>> types = (Set<BlockEntityType<?>>) (Set<?>) registeredTypeKeys();
        return types;
    }

    static Set<Object> registeredTypeKeys() {
        synchronized (LOCK) {
            return Collections.unmodifiableSet(Set.copyOf(DEFINITIONS.keySet()));
        }
    }

    @SuppressWarnings("unchecked")
    private static <T, I, O, C, F> ProxyDefinition<T, I, O, C, F> narrow(
            ProxyDefinition<?, ?, ?, ?, ?> definition
    ) {
        return (ProxyDefinition<T, I, O, C, F>) definition;
    }

    private static void ensureRuntimeRegistered(
            ProxyDefinition<?, ?, ?, ?, ?> definition
    ) {
        synchronized (LOCK) {
            ProxyDefinition<?, ?, ?, ?, ?> registered =
                    ProxyRuntime.GLOBAL.definition(definition.key()).orElse(null);
            if (registered == null) {
                ProxyRuntime.GLOBAL.register(definition);
                return;
            }
            if (registered != definition) {
                throw new IllegalStateException(
                        "proxy key is owned outside the block-entity catalog: "
                                + definition.key().id());
            }
        }
    }

    private static void unregisterRuntimeDefinition(
            ProxyDefinition<?, ?, ?, ?, ?> definition
    ) {
        if (ProxyRuntime.GLOBAL.definition(definition.key()).orElse(null) == definition) {
            ProxyRuntime.GLOBAL.unregister(definition.key());
        }
    }
}
