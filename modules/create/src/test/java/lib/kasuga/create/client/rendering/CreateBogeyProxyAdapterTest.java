package lib.kasuga.create.client.rendering;

import com.simibubi.create.content.trains.bogey.BogeySizes;
import lib.kasuga.create.proxy.BogeyModels;
import lib.kasuga.create.proxy.ContraptionBogey;
import lib.kasuga.rendering.models.mc.api.McModelHandle;
import lib.kasuga.rendering.models.mc.api.McModelHandleTestSupport;
import lib.kasuga.rendering.models.mc.proxies.ElementProxy;
import lib.kasuga.rendering.models.mc.proxies.ModelPart;
import lib.kasuga.rendering.models.mc.proxies.ProxyConfig;
import lib.kasuga.rendering.models.mc.proxies.ProxyIdentity;
import lib.kasuga.rendering.models.uml.dynamic.fsm.BlendMode;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmRegistries;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachine;
import lib.kasuga.rendering.models.uml.dynamic.fsm.VarProvider;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.LayerDefinition;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.PoseDefinition;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.StateDefinition;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.StateMachineDefinition;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyAttachToken;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyLifecycleState;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyRuntime;
import lib.kasuga.rendering.models.uml.dynamic.proxy.ProxySession;
import lib.kasuga.rendering.models.uml.math.Transform;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreateBogeyProxyAdapterTest {

    private static final ResourceLocation STYLE =
            ResourceLocation.fromNamespaceAndPath("kasuga_lib", "kernel_test_style");
    private static final ResourceLocation MODEL =
            ResourceLocation.fromNamespaceAndPath("kasuga_lib", "models/block/bogey/frame.obj");
    private static final Id MACHINE =
            Id.fromNamespaceAndPath("kasuga_lib", "kernel_test_machine");
    private static final BogeySizes.BogeySize SIZE = new BogeySizes.BogeySize(
            ResourceLocation.fromNamespaceAndPath("kasuga_lib", "kernel_test_size"), 0.5f);

    @AfterEach
    void clear() {
        ProxyRuntime.GLOBAL.clear();
        BogeyModels.clear();
        FsmRegistries.GLOBAL.definitions().remove(MACHINE);
    }

    @Test
    void framePublicationDoesNotAdvanceRevisionAndHideResumeKeepTheSameSession() {
        BogeyModels.register(STYLE, SIZE, List.of(ModelPart.of(MODEL)), 0.0f);
        ContraptionBogey bogey = new ContraptionBogey(STYLE, SIZE.id(), 12f, new Transform());
        ElementProxy<ContraptionBogey> template = new ElementProxy<>() {
            @Override
            public ProxyConfig configure(ContraptionBogey element) {
                return ProxyConfig.parts(List.of(
                        ModelPart.of(MODEL).moving(
                                ModelPart.Spin.frame(element::wheelAngle))));
            }
        };
        ProxyAttachToken token = ProxyAttachToken.random();
        var session = attach(bogey, template, token, 1L);

        assertEquals(ProxyLifecycleState.NEW, session.state());
        assertTrue(session.publishFrame(bogey.hostFrame()));
        assertEquals(0L, session.revision());

        session.activate();
        session.tick(7L, 0.05);
        long revision = session.revision();
        assertTrue(revision > 0L);
        session.publishFrame(bogey.hostFrame());
        assertEquals(revision, session.revision(),
                "frame input must never advance the session revision");

        session.suspend();
        assertSame(session, ProxyRuntime.GLOBAL.session(token).orElseThrow());
        assertFalse(session.publishFrame(bogey.hostFrame()));
        assertTrue(session.resume());
        assertTrue(session.publishFrame(bogey.hostFrame()));

        session.close();
        session.close();
        assertEquals(ProxyLifecycleState.CLOSED, session.state());
        assertFalse(ProxyRuntime.GLOBAL.session(token).isPresent());
    }

    @Test
    void closeUnregistersDefinitionAndDetachesTemplateExactlyOnce() {
        BogeyModels.register(STYLE, SIZE, List.of(ModelPart.of(MODEL)), 0.0f);
        ContraptionBogey bogey = new ContraptionBogey(STYLE, SIZE.id(), 12f, new Transform());
        AtomicInteger detachCalls = new AtomicInteger();
        ElementProxy<ContraptionBogey> template = new ElementProxy<>() {
            @Override
            public ProxyConfig configure(ContraptionBogey element) {
                return ProxyConfig.parts(List.of(ModelPart.of(MODEL)));
            }

            @Override
            public void onDetach(ContraptionBogey element) {
                detachCalls.incrementAndGet();
            }
        };
        var session = attach(bogey, template, ProxyAttachToken.random(), 2L);

        session.close();
        session.close();

        assertEquals(1, detachCalls.get());
        assertFalse(ProxyRuntime.GLOBAL.definition(session.key()).isPresent());
    }

    @Test
    void machineBackedPartsUseTheSessionControllerAndAdvanceOncePerTick() {
        FsmRegistries.GLOBAL.definitions().register(MACHINE, definition());
        BogeyModels.register(STYLE, SIZE, List.of(ModelPart.of(MODEL)), 0.0f);
        ContraptionBogey bogey = new ContraptionBogey(STYLE, SIZE.id(), 12f, new Transform());
        AtomicInteger pulls = new AtomicInteger();
        AtomicInteger pushes = new AtomicInteger();
        AtomicInteger providers = new AtomicInteger();
        ElementProxy<ContraptionBogey> template = new ElementProxy<>() {
            @Override
            public ProxyConfig configure(ContraptionBogey element) {
                return new ProxyConfig(
                        MACHINE,
                        null,
                        null,
                        element::normalizedRoot,
                        List.of(ModelPart.of(MODEL)));
            }

            @Override
            public void pull(ContraptionBogey element, StateMachine<?> machine) {
                pulls.incrementAndGet();
            }

            @Override
            public void push(ContraptionBogey element, StateMachine<?> machine) {
                pushes.incrementAndGet();
            }

            @Override
            public VarProvider varProvider(ContraptionBogey element) {
                return (machine, deltaSeconds, output) -> providers.incrementAndGet();
            }
        };
        var session = attach(bogey, template, ProxyAttachToken.random(), 3L);

        session.activate();
        session.tick(9L, 0.05);

        assertEquals(1, pulls.get());
        assertEquals(1, pushes.get());
        assertEquals(1, providers.get(),
                "the machine must run as the session's sole controller");
        session.close();
    }

    @Test
    void configureFailureDetachesTheTemplateAndLeavesNoSession() {
        BogeyModels.register(STYLE, SIZE, List.of(ModelPart.of(MODEL)), 0.0f);
        ContraptionBogey bogey = new ContraptionBogey(STYLE, SIZE.id(), 12f, new Transform());
        AtomicInteger detachCalls = new AtomicInteger();
        ElementProxy<ContraptionBogey> template = new ElementProxy<>() {
            @Override
            public ProxyConfig configure(ContraptionBogey element) {
                return null;
            }

            @Override
            public void onDetach(ContraptionBogey element) {
                detachCalls.incrementAndGet();
            }
        };
        ProxyAttachToken token = ProxyAttachToken.random();

        assertThrows(NullPointerException.class,
                () -> attach(bogey, template, token, 4L));

        assertEquals(1, detachCalls.get());
        assertFalse(ProxyRuntime.GLOBAL.session(token).isPresent());
    }

    @Test
    void laterCompositionFailureDetachesExactlyOnce() {
        BogeyModels.register(STYLE, SIZE, List.of(ModelPart.of(MODEL)), 0.0f);
        ContraptionBogey bogey = new ContraptionBogey(STYLE, SIZE.id(), 12f, new Transform());
        AtomicInteger detachCalls = new AtomicInteger();
        ElementProxy<ContraptionBogey> template = new ElementProxy<>() {
            @Override
            public ProxyConfig configure(ContraptionBogey element) {
                return new ProxyConfig(
                        MACHINE,
                        null,
                        null,
                        element::normalizedRoot,
                        List.of(ModelPart.of(MODEL)));
            }

            @Override
            public VarProvider varProvider(ContraptionBogey element) {
                throw new IllegalStateException("composition failed after configure");
            }

            @Override
            public void onDetach(ContraptionBogey element) {
                detachCalls.incrementAndGet();
            }
        };
        ProxyAttachToken token = ProxyAttachToken.random();

        assertThrows(IllegalStateException.class,
                () -> attach(bogey, template, token, 5L));

        assertEquals(1, detachCalls.get());
        assertFalse(ProxyRuntime.GLOBAL.session(token).isPresent());
    }

    @Test
    void suspendHidesAndResumeShowsTheMountedCreateHandle() {
        BogeyModels.register(STYLE, SIZE, List.of(ModelPart.of(MODEL)), 0.0f);
        ContraptionBogey bogey = new ContraptionBogey(STYLE, SIZE.id(), 12f, new Transform());
        ElementProxy<ContraptionBogey> template = new ElementProxy<>() {
            @Override
            public ProxyConfig configure(ContraptionBogey element) {
                return ProxyConfig.parts(List.of(ModelPart.of(MODEL)));
            }
        };
        AtomicReference<McModelHandle> mounted = new AtomicReference<>();
        ProxySession<ContraptionBogey, ContraptionBogey.HostFrame, Void, Void,
                ContraptionBogey.HostFrame> session = CreateBogeyProxyAdapter.attach(
                bogey,
                template,
                ProxyIdentity.ofExternal("kasuga_lib", "kernel_test", 6L),
                ProxyAttachToken.random(),
                null,
                (part, instanceLoc) -> {
                    McModelHandle handle =
                            McModelHandleTestSupport.custom(part.model(), instanceLoc);
                    mounted.set(handle);
                    return handle;
                });

        session.activate();
        session.tick(1L, 0.05);

        assertTrue(mounted.get().isMounted());
        assertTrue(mounted.get().shown());

        session.suspend();
        assertFalse(mounted.get().shown());

        session.resume();
        assertTrue(mounted.get().shown());

        session.close();
        assertTrue(mounted.get().isDestroyed());
    }

    private static ProxySession<ContraptionBogey, ContraptionBogey.HostFrame, Void, Void,
            ContraptionBogey.HostFrame> attach(
            ContraptionBogey bogey,
            ElementProxy<ContraptionBogey> template,
            ProxyAttachToken token,
            long discriminator
    ) {
        return CreateBogeyProxyAdapter.attach(
                bogey,
                template,
                ProxyIdentity.ofExternal("kasuga_lib", "kernel_test", discriminator),
                token,
                null,
                (part, instanceLoc) ->
                        McModelHandleTestSupport.custom(part.model(), instanceLoc));
    }

    private static StateMachineDefinition definition() {
        StateDefinition idle = new StateDefinition(
                "idle",
                Optional.empty(),
                PoseDefinition.EMPTY,
                List.of(),
                List.of(),
                List.of(),
                Optional.empty());
        LayerDefinition layer = new LayerDefinition(
                "main",
                BlendMode.BASE,
                1f,
                Optional.empty(),
                List.of(idle),
                List.of(),
                "idle");
        return new StateMachineDefinition(MACHINE, List.of(), List.of(layer));
    }

}
