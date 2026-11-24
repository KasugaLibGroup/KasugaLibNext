package lib.kasuga.test.registration.data_driven;

import lib.kasuga.registration.data_driven.DataDrivenRegistrationProviders;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataDrivenRegistrationProvidersTest {
    @Test
    void installsAndRemovesExactProvider() {
        var provider = (lib.kasuga.registration.data_driven.DataDrivenRegistrationProvider)
                ignored -> null;
        var attachment = DataDrivenRegistrationProviders.install(
                "data_driven_provider_install", provider);
        try {
            assertSame(provider, DataDrivenRegistrationProviders.find(
                    "data_driven_provider_install").orElseThrow());
        } finally {
            attachment.close();
        }
        assertTrue(DataDrivenRegistrationProviders.find(
                "data_driven_provider_install").isEmpty());
    }

    @Test
    void rejectsSecondProviderForSameHost() {
        var attachment = DataDrivenRegistrationProviders.install(
                "data_driven_provider_duplicate", ignored -> null);
        try {
            assertThrows(IllegalStateException.class, () ->
                    DataDrivenRegistrationProviders.install(
                            "data_driven_provider_duplicate", ignored -> null));
        } finally {
            attachment.close();
        }
    }
}
