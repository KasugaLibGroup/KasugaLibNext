package lib.kasuga.registration.data_driven;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide installation point for host registration providers.
 */
public final class DataDrivenRegistrationProviders {
    private static final Map<String, Attachment> PROVIDERS = new ConcurrentHashMap<>();

    public static Attachment install(String hostModId, DataDrivenRegistrationProvider provider) {
        String id = requireHostId(hostModId);
        Objects.requireNonNull(provider, "provider");
        Attachment attachment = new Attachment(id, provider);
        if (PROVIDERS.putIfAbsent(id, attachment) != null) {
            throw new IllegalStateException(
                    "A data-driven registration provider is already installed for mod '" + id + "'");
        }
        return attachment;
    }

    public static Optional<DataDrivenRegistrationProvider> find(String hostModId) {
        String id = requireHostId(hostModId);
        Attachment attachment = PROVIDERS.get(id);
        return attachment == null ? Optional.empty() : Optional.of(attachment.provider);
    }

    private static String requireHostId(String hostModId) {
        if (hostModId == null || hostModId.isBlank()) {
            throw new IllegalArgumentException("hostModId must not be blank");
        }
        return hostModId;
    }

    private DataDrivenRegistrationProviders() {
    }

    public static final class Attachment implements AutoCloseable {
        private final String hostModId;
        private final DataDrivenRegistrationProvider provider;

        private Attachment(String hostModId, DataDrivenRegistrationProvider provider) {
            this.hostModId = hostModId;
            this.provider = provider;
        }

        @Override
        public void close() {
            PROVIDERS.remove(hostModId, this);
        }
    }
}
