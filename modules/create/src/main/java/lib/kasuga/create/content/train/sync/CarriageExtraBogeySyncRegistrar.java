package lib.kasuga.create.content.train.sync;

import io.micronaut.context.annotation.Context;
import jakarta.annotation.PostConstruct;
import lib.kasuga.create.CreateModBeans;

/**
 * Forces {@link CarriageExtraBogeySync} to load during mod construction, so its static RPC
 * registration runs before the payload registration window closes. {@code @CreateModBeans} gates
 * the bean on Create being loaded.
 */
@Context
@CreateModBeans
public final class CarriageExtraBogeySyncRegistrar {

    @PostConstruct
    public void init() {
        // Touching the static field prevents the JVM from eliding the class-load.
        CarriageExtraBogeySync.SYNC.getClass();
    }
}
