package lib.kasuga.rendering.models.mc.dynamic.fsm.sync;

import io.micronaut.context.annotation.Context;
import jakarta.annotation.PostConstruct;

/**
 * Forces {@link FsmSyncChannel} to load during mod construction so its {@code PayloadReg} is
 * attached to the registration tree within the registration window.
 */
@Context
public final class FsmSyncChannelRegistrar {

    @PostConstruct
    public void init() {
        // Force FsmSyncChannel class-init so its static PAYLOAD PayloadReg attaches to the registry tree
        // within the mod-loading window. Static registration only takes effect if this class loads now;
        // touching PAYLOAD (via getClass) prevents the JVM from eliding the class-load.
        FsmSyncChannel.PAYLOAD.getClass();
    }
}
