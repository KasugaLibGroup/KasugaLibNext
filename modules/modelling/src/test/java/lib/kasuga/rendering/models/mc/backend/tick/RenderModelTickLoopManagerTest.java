package lib.kasuga.rendering.models.mc.backend.tick;

import lib.kasuga.rendering.models.mc.dynamic.MinecraftModelRuntime;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstanceFixture;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.ModelTickLoop;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.PendingTransform;
import lib.kasuga.rendering.models.uml.dynamic.tick_loop.handler.ModelTickLoopModule;
import lib.kasuga.rendering.models.uml.structure.Model;
import net.neoforged.bus.api.SubscribeEvent;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RenderModelTickLoopManagerTest {
    @Test
    void registrationAlwaysReturnsTheCanonicalLoopWithoutCallingLegacyFactory() {
        RenderModelTickLoopManager<Object> manager = new RenderModelTickLoopManager<>() {
            @Override protected ModelTickLoop create(ModelInstance instance) {
                fail("legacy factories must not create a second pipeline");
                return new ModelTickLoop(instance);
            }
        };
        try (ModelInstance instance = ModelInstanceFixture.minimal()) {
            try {
                assertSame(instance.getTickLoop(), manager.registerLoop(instance));
                assertSame(instance.getTickLoop(), manager.registerLoop(instance));
                assertTrue(MinecraftModelRuntime.isRegistered(instance));
            } finally {
                manager.unregisterLoop(instance);
            }
        }
    }

    @Test
    void automaticRegistrationExcludesManualTicksUntilExplicitlyUnregistered() {
        RenderModelTickLoopManager<Object> manager = new RenderModelTickLoopManager<>() {};
        try (ModelInstance instance = ModelInstanceFixture.minimal()) {
            AtomicInteger calls = new AtomicInteger();
            instance.getTickLoop().addPostPhysics("manager-probe", new ModelTickLoopModule() {
                @Override public void tick(Model model, PendingTransform[] transforms, ModelTickLoop loop, float dt) {
                    calls.incrementAndGet();
                }
                @Override public void destroy(Model model) {}
            });
            manager.registerLoop(instance);
            try {
                manager.tick(0.1f);
                assertEquals(0, calls.get());
                MinecraftModelRuntime.evaluateFrame(0.5f, 0.1f);
                manager.tick(0.1f);
                assertEquals(1, calls.get());
                MinecraftModelRuntime.unregister(instance);
                manager.tick(0.1f);
                assertEquals(2, calls.get());
                instance.close();
                assertDoesNotThrow(() -> manager.tick(0.1f));
                assertEquals(2, calls.get());
            } finally {
                manager.unregisterLoop(instance);
            }
        }
    }

    @Test
    void unregisterDoesNotDestroyTheInstanceOwnedLoop() {
        RenderModelTickLoopManager<Object> manager = new RenderModelTickLoopManager<>() {};
        try (ModelInstance instance = ModelInstanceFixture.minimal()) {
            AtomicInteger destroys = new AtomicInteger();
            instance.getTickLoop().addPostPhysics("lifetime-probe", new ModelTickLoopModule() {
                @Override public void tick(Model model, PendingTransform[] transforms, ModelTickLoop loop, float dt) {}
                @Override public void destroy(Model model) { destroys.incrementAndGet(); }
            });
            manager.registerLoop(instance);
            manager.unregisterLoop(instance);
            manager.unregisterLoop(instance);
            assertFalse(MinecraftModelRuntime.isRegistered(instance));
            assertEquals(0, destroys.get());
            instance.close();
            assertEquals(1, destroys.get());
        }
    }

    @Test
    void managerHasNoRenderFrameSubscription() {
        assertTrue(Arrays.stream(RenderModelTickLoopManager.class.getDeclaredMethods())
                .noneMatch(method -> method.isAnnotationPresent(SubscribeEvent.class)));
    }
}
