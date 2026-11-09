package test.kasuga.create.bogey;

import io.micronaut.context.annotation.Context;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import lib.kasuga.create.CreateModBeans;
import lib.kasuga.create.client.registration.ClientBogeyRendererRegistry;
import lib.kasuga.create.client.rendering.ProxyBogeyVisual;
import lib.kasuga.inject.class_loader.BeanOnlyIn;

/**
 * Client wiring for the example bogeys.
 *
 * <p>Both bogeys are drawn by the proxy layer, on the ground <em>and</em> on a train:
 *
 * <ul>
 *     <li>placed → the block entity hosts the proxy itself;</li>
 *     <li>mounted on a train → there is no block entity, so Create's {@code BogeyVisual} hook is
 *     used instead: {@link ProxyBogeyVisual} republishes the wheel angle and pose it is handed, and
 *     the same proxy draws. {@link ProxyBogeyVisual.NoopRenderer} keeps Create from drawing its own
 *     model through the non-Flywheel fallback renderer.</li>
 * </ul>
 *
 * <p>The size is resolved lazily inside the visualizer lambda because it only exists once the size
 * registration has committed, which happens after this bean is constructed.
 */
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
                ProxyBogeyVisual.NoopRenderer::new,
                (context, partialTick, inContraption) -> new ProxyBogeyVisual(
                        context, AllExampleBogey.PK209P.getEntry(), AllExampleBogey.contraptionBogeyProxy())
        );
        renderers.register(
                AllExampleBogey.CW2_RENDERER,
                ProxyBogeyVisual.NoopRenderer::new,
                (context, partialTick, inContraption) -> new ProxyBogeyVisual(
                        context, AllExampleBogey.CW2.getEntry(), AllExampleBogey.contraptionBogeyProxy())
        );
    }
}
