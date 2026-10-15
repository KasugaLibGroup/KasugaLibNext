package test.kasuga.create;

import io.micronaut.context.annotation.Context;
import jakarta.annotation.PostConstruct;
import lib.kasuga.KasugaLibApplication;
import lib.kasuga.create.CreateModBeans;
import test.kasuga.create.track.ExampleTracks;
import test.kasuga.create.bogey.AllExampleBogey;

@Context
@CreateModBeans
public final class CreateContentTestApplication {
    @PostConstruct
    void init() {
        AllExampleBogey.register(KasugaLibApplication.REGISTRY);
        ExampleTracks.register(KasugaLibApplication.REGISTRY);
    }
}
