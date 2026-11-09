package lib.kasuga.rendering.models.mc.proxies;

import lib.kasuga.rendering.models.mc.api.McModelHandle;
import lib.kasuga.rendering.models.uml.math.Transform;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyInstanceLifecycleTest {

    private static final ResourceLocation FIRST =
            ResourceLocation.fromNamespaceAndPath("test", "first");
    private static final ResourceLocation SECOND =
            ResourceLocation.fromNamespaceAndPath("test", "second");

    @Test
    void secondPartFailureDestroysTheFirstPartAndDetachesTemplateOnce() {
        AtomicInteger detachCalls = new AtomicInteger();
        ElementProxy<String> template = new ElementProxy<>() {
            @Override
            public ProxyConfig configure(String element) {
                return ProxyConfig.parts(List.of(ModelPart.of(FIRST), ModelPart.of(SECOND)));
            }

            @Override
            public void onDetach(String element) {
                detachCalls.incrementAndGet();
            }
        };
        ProxyInstance<String> proxy = new ProxyInstance<>(
                this, "element", template,
                ProxyIdentity.ofExternal("test", "proxy", 1L), Transform::new);
        AtomicReference<McModelHandle> firstHandle = new AtomicReference<>();
        AtomicInteger creates = new AtomicInteger();

        assertThrows(IllegalStateException.class, () -> proxy.materializePartsForTest(
                List.of(ModelPart.of(FIRST), ModelPart.of(SECOND)),
                (spec, instanceLoc) -> {
                    if (creates.getAndIncrement() == 0) {
                        McModelHandle handle = McModelHandle.of(
                                spec.model(), spec.modelName(), instanceLoc, null);
                        firstHandle.set(handle);
                        return handle;
                    }
                    throw new IllegalStateException("second part failed");
                }));

        assertTrue(firstHandle.get().isDestroyed(),
                "the already-created first part handle must be destroyed during rollback");
        assertEquals(1, detachCalls.get());

        proxy.release(null);

        assertEquals(1, detachCalls.get(), "template detach must remain single-shot across failed release");
    }
}
