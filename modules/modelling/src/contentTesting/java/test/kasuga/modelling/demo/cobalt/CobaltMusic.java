package test.kasuga.modelling.demo.cobalt;

import io.micronaut.context.annotation.Context;
import jakarta.annotation.PostConstruct;
import lib.kasuga.registration.minecraft.sound.SoundReg;
import test.kasuga.modelling.ModellingTestApplication;

/** The demo consumes Kasuga's registration API; the runtime/player is a library facility. */
@Context
public final class CobaltMusic {
    @PostConstruct public void register() {
        SoundReg.of("cobalt_blue_music").setParent(ModellingTestApplication.registry);
    }
}
