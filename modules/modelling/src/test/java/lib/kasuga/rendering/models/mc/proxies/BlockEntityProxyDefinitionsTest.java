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
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyDefinition;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyRuntime;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxySession;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyVisualPort;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockEntityProxyDefinitionsTest {

    private static final LogicalStepPolicy POLICY = LogicalStepPolicy.fixed(0.05, 5);
    private static final ResourceLocation TYPE_ID =
            ResourceLocation.fromNamespaceAndPath("test", "registered_proxy_entity");

    @AfterEach
    void clearDefinitions() {
        BlockEntityProxyDefinitions.clear();
    }

    @Test
    void registrationRoutesAQualifiedTypeToItsRegisteredDefinition() {
        Object type = new Object();
        ProxyDefinition<String, String, String, String, String> definition =
                BlockEntityProxyDefinitions.<String, String, String, String, String>registerForType(
                        type, TYPE_ID, ignored -> composition());
        String element = "element";
        ProxyAttachToken token = ProxyAttachToken.random();

        ProxySession<String, String, String, String, String> session =
                BlockEntityProxyDefinitions.<String, String, String, String, String>attachForType(
                                type, element, token)
                        .orElseThrow();
        try {
            assertSame(definition, BlockEntityProxyDefinitions.definitionForType(type).orElseThrow());
            assertEquals("test:block_entity/registered_proxy_entity", definition.key().id());
            assertSame(session, ProxyRuntime.GLOBAL.session(token).orElseThrow());
        } finally {
            session.close();
        }
    }

    @Test
    void duplicateTypeAndDuplicateKeyRegistrationAreRejected() {
        Object firstType = new Object();
        BlockEntityProxyDefinitions.<String, String, String, String, String>registerForType(
                firstType, TYPE_ID, ignored -> composition());

        assertThrows(IllegalStateException.class,
                () -> BlockEntityProxyDefinitions.<String, String, String, String, String>registerForType(
                firstType,
                ResourceLocation.fromNamespaceAndPath("test", "replacement"),
                ignored -> composition()));

        Object secondType = new Object();
        assertThrows(IllegalStateException.class,
                () -> BlockEntityProxyDefinitions.<String, String, String, String, String>registerForType(
                secondType, TYPE_ID, ignored -> composition()));
    }

    @Test
    void unattachedOrUnregisteredTypesHaveNoImplicitDefinition() {
        Object type = new Object();
        String element = "element";

        assertFalse(BlockEntityProxyDefinitions.definitionForType(type).isPresent());
        assertFalse(BlockEntityProxyDefinitions.attachForType(
                type, element, ProxyAttachToken.random()).isPresent());

        BlockEntityProxyDefinitions.<String, String, String, String, String>registerForType(
                type, TYPE_ID, ignored -> composition());
        BlockEntityProxyDefinitions.unregisterType(type);

        assertFalse(BlockEntityProxyDefinitions.definitionForType(type).isPresent());
        assertFalse(BlockEntityProxyDefinitions.attachForType(
                type, element, ProxyAttachToken.random()).isPresent());
        assertTrue(BlockEntityProxyDefinitions.registeredTypeKeys().isEmpty());
    }

    private static ProxyComposition<String, String, String, String, String> composition() {
        return ProxyComposition.of(
                POLICY,
                ignored -> "input",
                controller(),
                (ignored, output) -> {
                },
                ProxyCommandPort.none(),
                new ProxyVisualPort<>() {
                    @Override
                    public boolean isReady(String element) {
                        return true;
                    }

                    @Override
                    public void bind(AnimationRenderPort logicalRenderPort) {
                    }

                    @Override
                    public void publishFrame(String frame) {
                    }
                },
                List.of());
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

}
