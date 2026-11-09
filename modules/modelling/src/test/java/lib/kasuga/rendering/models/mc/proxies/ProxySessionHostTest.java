package lib.kasuga.rendering.models.mc.proxies;

import lib.kasuga.rendering.models.uml.dynamic.animation.scope.AnimationRenderPort;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.AnimationTarget;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.AnimationTargetRef;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.ControlEvaluationRequest;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.ControlFrame;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.LogicalStepPolicy;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.RenderSampleContext;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.RuntimeController;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.TargetBinding;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.TargetPublication;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.TargetPublicationFactory;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyAttachToken;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyCommandPort;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyComposition;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyControllerPort;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyDefinition;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyKey;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyRuntime;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxySession;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyVisualPort;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxySessionHostTest {

    private static final LogicalStepPolicy POLICY = LogicalStepPolicy.fixed(0.05, 5);

    @Test
    void removeAndUnloadMayBothCloseTheOwnedSession() {
        ProxyRuntime runtime = new ProxyRuntime();
        AtomicInteger visualCloses = new AtomicInteger();
        AtomicInteger targetCloses = new AtomicInteger();
        ProxyKey<String> key = ProxyKey.blockEntity("test", "host-close");
        ProxyDefinition<String, String, String, String, String> definition =
                new ProxyDefinition<>(key, element -> ProxyComposition.of(
                        POLICY,
                        ignored -> "input",
                        controller(),
                        (ignored, output) -> {
                        },
                        ProxyCommandPort.none(),
                        new ProxyVisualPort<>() {
                            @Override
                            public boolean isReady(String ignored) {
                                return true;
                            }

                            @Override
                            public void bind(AnimationRenderPort logicalRenderPort) {
                            }

                            @Override
                            public void publishFrame(String frame) {
                            }

                            @Override
                            public void close() {
                                visualCloses.incrementAndGet();
                            }
                        },
                        List.of(new TargetBinding<>(
                                closeCountingTarget(targetCloses),
                                ignored -> "value",
                                new TargetPublicationFactory<>()))));
        runtime.register(definition);

        ProxyAttachToken token = ProxyAttachToken.random();
        ProxySession<String, String, String, String, String> session =
                runtime.attach(definition, "fan", token).orElseThrow();
        TestHost host = new TestHost(token);
        host.proxySession(session);

        host.closeProxySession();
        host.closeProxySession();

        assertTrue(session.isClosed());
        assertNull(host.proxySession());
        assertFalse(runtime.session(token).isPresent());
        assertEquals(1, visualCloses.get());
        assertEquals(1, targetCloses.get());
    }

    private static AnimationTarget<String> closeCountingTarget(AtomicInteger closes) {
        return new AnimationTarget<>() {
            private final AnimationTargetRef ref = AnimationTargetRef.property("host-close");

            @Override
            public AnimationTargetRef ref() {
                return ref;
            }

            @Override
            public void publish(TargetPublication<String> publication) {
            }

            @Override
            public void sample(TargetPublication<String> publication,
                               RenderSampleContext context) {
            }

            @Override
            public void close() {
                closes.incrementAndGet();
            }
        };
    }

    private static ProxyControllerPort<String, String> controller() {
        return new ProxyControllerPort<>() {
            @Override
            public void prepare(String input) {
            }

            @Override
            public RuntimeController<Void> controller() {
                return new RuntimeController<>() {
                    @Override
                    public ControlFrame<Void> evaluate(ControlEvaluationRequest<Void> request) {
                        return new ControlFrame<>(null, List.of(), List.of());
                    }
                };
            }

            @Override
            public String output() {
                return "output";
            }
        };
    }

    private static final class TestHost implements ProxySessionHost {

        private final ProxyAttachToken token;
        @Nullable
        private ProxySession<?, ?, ?, ?, ?> session;

        private TestHost(ProxyAttachToken token) {
            this.token = token;
        }

        @Override
        public ProxyAttachToken proxyAttachToken() {
            return token;
        }

        @Override
        public @Nullable ProxySession<?, ?, ?, ?, ?> proxySession() {
            return session;
        }

        @Override
        public void proxySession(@Nullable ProxySession<?, ?, ?, ?, ?> session) {
            this.session = session;
        }
    }
}
