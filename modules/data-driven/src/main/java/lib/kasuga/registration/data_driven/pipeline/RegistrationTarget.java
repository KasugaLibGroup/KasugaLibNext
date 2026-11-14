package lib.kasuga.registration.data_driven.pipeline;

import lib.kasuga.registration.data_driven.context.JsonRegistryGroup;
import lib.kasuga.registration.data_driven.context.RegBuildContext;

/**
 * Creates the mutable registration context used by one build session.
 */
@FunctionalInterface
public interface RegistrationTarget {
    RegBuildContext createContext(String hostModId, JsonRegistryGroup rootGroup, BuildOptions options);

    static RegistrationTarget standard() {
        return (hostModId, rootGroup, options) ->
                new RegBuildContext(hostModId, rootGroup, options.isStrict());
    }
}
