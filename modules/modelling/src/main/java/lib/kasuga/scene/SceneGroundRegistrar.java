package lib.kasuga.scene;

import io.micronaut.context.annotation.Context;
import jakarta.annotation.PostConstruct;

@Context
public final class SceneGroundRegistrar {
    @PostConstruct
    public void register() {
        SceneGround.register();
    }
}
