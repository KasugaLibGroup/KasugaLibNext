package lib.kasuga.rendering.models.uml.dynamic.proxy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Explicit registry and attach-token authority.
 *
 * <p>There is no wildcard registration. One element identity can have at most one live session,
 * and one token can be consumed at most once until that session closes.
 */
public final class ProxyRuntime {

    public static final ProxyRuntime GLOBAL = new ProxyRuntime();

    private final Object lock = new Object();
    private final Map<ProxyKey<?>, ProxyDefinition<?, ?, ?, ?, ?>> definitions =
            new LinkedHashMap<>();
    private final Map<ProxyAttachToken, ProxySession<?, ?, ?, ?, ?>> sessions =
            new LinkedHashMap<>();
    private final IdentityHashMap<Object, ProxyAttachToken> owners = new IdentityHashMap<>();
    private final Set<ProxyAttachToken> pendingTokens = new HashSet<>();

    public <T, I, O, C, F> void register(ProxyDefinition<T, I, O, C, F> definition) {
        Objects.requireNonNull(definition, "definition");
        synchronized (lock) {
            if (definitions.containsKey(definition.key())) {
                throw new IllegalStateException(
                        "proxy definition already registered: " + definition.key().id());
            }
            definitions.put(definition.key(), definition);
        }
    }

    public <T, I, O, C, F> Optional<ProxySession<T, I, O, C, F>> attach(
            ProxyDefinition<T, I, O, C, F> definition,
            T element,
            ProxyAttachToken token
    ) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(element, "element");
        Objects.requireNonNull(token, "token");
        synchronized (lock) {
            ProxyDefinition<?, ?, ?, ?, ?> registered = definitions.get(definition.key());
            if (registered != definition) {
                throw new IllegalStateException(
                        "proxy definition is not registered: " + definition.key().id());
            }
            reserveLocked(element, token);
        }
        return Optional.of(createAndPublish(definition, element, token));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public Optional<ProxySession<?, ?, ?, ?, ?>> attach(
            ProxyKey<?> key,
            Object element,
            ProxyAttachToken token
    ) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(element, "element");
        Objects.requireNonNull(token, "token");
        ProxyDefinition<?, ?, ?, ?, ?> definition;
        synchronized (lock) {
            definition = definitions.get(key);
            if (definition == null) {
                return Optional.empty();
            }
            reserveLocked(element, token);
        }
        return Optional.of(createAndPublish(
                (ProxyDefinition) definition, element, token));
    }

    public Optional<ProxyDefinition<?, ?, ?, ?, ?>> definition(ProxyKey<?> key) {
        return lockedLookup(definitions, key);
    }

    public Optional<ProxySession<?, ?, ?, ?, ?>> session(ProxyAttachToken token) {
        return lockedLookup(sessions, token);
    }

    private <K, V> Optional<V> lockedLookup(Map<K, V> map, K key) {
        synchronized (lock) {
            return Optional.ofNullable(map.get(key));
        }
    }

    public Map<ProxyAttachToken, ProxySession<?, ?, ?, ?, ?>> sessions() {
        synchronized (lock) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(sessions));
        }
    }

    public void unregister(ProxyKey<?> key) {
        synchronized (lock) {
            definitions.remove(key);
        }
    }

    public void clear() {
        List<ProxySession<?, ?, ?, ?, ?>> snapshot;
        synchronized (lock) {
            snapshot = new ArrayList<>(sessions.values());
        }
        for (ProxySession<?, ?, ?, ?, ?> session : snapshot) {
            try {
                session.close();
            } catch (RuntimeException ignored) {
                // Clear is a test/diagnostic seam; individual close failures remain observable
                // through the owner path.
            }
        }
        synchronized (lock) {
            sessions.clear();
            owners.clear();
            pendingTokens.clear();
            definitions.clear();
        }
    }

    private void reserveLocked(Object element, ProxyAttachToken token) {
        if (sessions.containsKey(token) || !pendingTokens.add(token)) {
            throw new IllegalStateException("proxy attach token already used: " + token);
        }
        ProxyAttachToken ownerToken = owners.get(element);
        if (ownerToken != null) {
            pendingTokens.remove(token);
            throw new IllegalStateException(
                    "element already has proxy session token " + ownerToken
                            + "; attempted " + token);
        }
        owners.put(element, token);
    }

    private <T, I, O, C, F> ProxySession<T, I, O, C, F> createAndPublish(
            ProxyDefinition<T, I, O, C, F> definition,
            T element,
            ProxyAttachToken token
    ) {
        ProxySession<T, I, O, C, F> session = null;
        try {
            ProxyComposition<T, I, O, C, F> composition =
                    Objects.requireNonNull(definition.template().create(element),
                            "proxy template returned null composition");
            session = new ProxySession<>(
                    token, definition.key(), element, composition, this::detached);
            synchronized (lock) {
                if (definitions.get(definition.key()) != definition) {
                    throw new IllegalStateException(
                            "proxy definition was removed while attaching: "
                                    + definition.key().id());
                }
                if (!pendingTokens.contains(token) || owners.get(element) != token) {
                    throw new IllegalStateException(
                            "proxy attach reservation was lost: " + token);
                }
                pendingTokens.remove(token);
                sessions.put(token, session);
                return session;
            }
        } catch (RuntimeException | Error failure) {
            if (session != null) {
                try {
                    session.close();
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            } else {
                releaseReservation(element, token);
            }
            throw failure;
        }
    }

    private void releaseReservation(Object element, ProxyAttachToken token) {
        synchronized (lock) {
            pendingTokens.remove(token);
            if (owners.get(element) == token) {
                owners.remove(element);
            }
        }
    }

    private void detached(ProxySession<?, ?, ?, ?, ?> session) {
        synchronized (lock) {
            pendingTokens.remove(session.token());
            if (sessions.get(session.token()) == session) {
                sessions.remove(session.token());
            }
            ProxyAttachToken ownerToken = owners.get(session.elementForRuntime());
            if (session.token().equals(ownerToken)) {
                owners.remove(session.elementForRuntime());
            }
        }
    }
}
