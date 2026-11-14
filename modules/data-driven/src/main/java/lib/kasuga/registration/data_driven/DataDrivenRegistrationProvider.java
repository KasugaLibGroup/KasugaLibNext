package lib.kasuga.registration.data_driven;

import lib.kasuga.registration.data_driven.context.JsonRegistryGroup;

/**
 * Host-owned replacement for the legacy mod-JAR data-driven scan.
 */
@FunctionalInterface
public interface DataDrivenRegistrationProvider {
    JsonRegistryGroup build(String hostModId);
}
