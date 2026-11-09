package lib.kasuga.rendering.models.mc.proxies;

import lib.kasuga.rendering.models.uml.dynamic.animation.scope.AnimationRenderPort;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.ControlEvaluationRequest;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.ControlFrame;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.LogicalStepPolicy;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.RuntimeController;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyAttachToken;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyCommandPort;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyComposition;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyControllerPort;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyLifecycleState;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyRuntime;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxySession;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyVisualPort;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ProxyTickersKernelTest {

    private static final ResourceLocation TYPE_ID =
            ResourceLocation.fromNamespaceAndPath("test", "registered_proxy_host");

    @AfterEach
    void clear() {
        BlockEntityProxyDefinitions.clear();
        ProxyRuntime.GLOBAL.clear();
    }

    @Test
    void hostTickerAttachesOnceAndReusesTheSameSessionEveryTick() {
        Object type = new Object();
        TestHost host = new TestHost();
        RecordingController controller = new RecordingController();
        BlockEntityProxyDefinitions.<TestHost, String, String, String, String>registerForType(
                type,
                TYPE_ID,
                ignored -> ProxyComposition.<TestHost, String, String, String, String>of(
                        LogicalStepPolicy.fixed(0.05, 5),
                        element -> "input",
                        controller,
                        (element, output) -> {
                        },
                        ProxyCommandPort.none(),
                        ProxyVisualPort.none(),
                        List.of()));

        ProxyTickers.tickHost(type, host, host, 1L, 0.05);
        ProxySession<?, ?, ?, ?, ?> first = host.proxySession();
        assertNotNull(first);
        assertEquals(ProxyLifecycleState.ACTIVE, first.state());

        ProxyTickers.tickHost(type, host, host, 2L, 0.05);

        assertSame(first, host.proxySession());
        assertEquals(2, controller.advances.get());
        assertEquals(2L, first.revision());
    }

    private static final class TestHost implements ProxySessionHost {

        private final ProxyAttachToken token = ProxyAttachToken.random();
        @Nullable private ProxySession<?, ?, ?, ?, ?> session;

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

    private static final class RecordingController
            implements ProxyControllerPort<String, String> {

        private final AtomicInteger advances = new AtomicInteger();

        @Override
        public void prepare(String input) {
        }

        @Override
        public RuntimeController<Void> controller() {
            return new RuntimeController<>() {
                @Override
                public ControlFrame<Void> evaluate(ControlEvaluationRequest<Void> request) {
                    advances.incrementAndGet();
                    return new ControlFrame<>(null, List.of(), List.of());
                }
            };
        }

        @Override
        public String output() {
            return "output";
        }
    }
}
