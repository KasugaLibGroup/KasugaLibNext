package test.kasuga.create.bogey;

import com.simibubi.create.content.trains.bogey.StandardBogeyRenderer;
import com.simibubi.create.content.trains.bogey.StandardBogeyVisual;
import io.micronaut.context.annotation.Context;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import lib.kasuga.create.CreateModBeans;
import lib.kasuga.create.client.registration.ClientBogeyRendererRegistry;
import lib.kasuga.inject.class_loader.BeanOnlyIn;

@Context
@CreateModBeans
@BeanOnlyIn.Client
public final class ExampleBogeyClientApplication {
    @Inject
    ClientBogeyRendererRegistry renderers;

    @PostConstruct
    void init() {
        renderers.register(
                AllExampleBogey.PK209P_RENDERER,
                StandardBogeyRenderer.Small::new,
                StandardBogeyVisual.Small::new
        );
        renderers.register(
                AllExampleBogey.CW2_RENDERER,
                StandardBogeyRenderer.Large::new,
                StandardBogeyVisual.Large::new
        );
    }
}
