package lib.kasuga.rendering.models.mc.proxies;

import lib.kasuga.rendering.models.mc.api.McModelHandleTestSupport;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstanceFixture;
import lib.kasuga.rendering.models.uml.math.Transform;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProxyInstanceModelGroupTest {

    private static final ResourceLocation MODEL = ResourceLocation.fromNamespaceAndPath("test", "part");

    @Test
    void oneRootReadFeedsMaterializationTickAndEveryPartSample() {
        AtomicInteger rootReads = new AtomicInteger();
        AtomicInteger motionTicks = new AtomicInteger();
        ProxyInstance<String> proxy = new ProxyInstance<>(
                this, "element", new ElementProxy<>() {
                    @Override
                    public ProxyConfig configure(String element) {
                        return ProxyConfig.parts(parts(motionTicks));
                    }
                },
                ProxyIdentity.ofExternal("test", "model-group", 1L),
                () -> {
                    float x = rootReads.incrementAndGet();
                    return new Transform().translate(x, 0f, 0f);
                });
        List<ModelInstance> instances = new ArrayList<>();

        proxy.materializePartsForTest(parts(motionTicks), (spec, instanceLoc) -> {
            ModelInstance instance = ModelInstanceFixture.minimal();
            instances.add(instance);
            return McModelHandleTestSupport.custom(MODEL, instanceLoc, instance);
        });

        assertEquals(4, instances.size());
        assertEquals(1, rootReads.get());

        proxy.tickPartsForTest(1L);
        assertEquals(2, rootReads.get());
        int motionTicksAfterTick = motionTicks.get();

        for (ModelInstance instance : instances) {
            instance.sample(0.5f);
            instance.sample(0.5f);
            instance.sample(0.5f);
            assertEquals(3f, instance.getSkeletonInstance().getTransform().getPosition().x, 1e-5f);
        }
        assertEquals(2, rootReads.get());
        assertEquals(motionTicksAfterTick, motionTicks.get());

        proxy.publishVisualFrame(new Transform().translate(30f, 0f, 0f));
        assertEquals(2, rootReads.get());
        assertEquals(motionTicksAfterTick, motionTicks.get());

        for (ModelInstance instance : instances) {
            instance.sample(0.5f);
            assertEquals(31f, instance.getSkeletonInstance().getTransform().getPosition().x, 1e-5f);
        }
        assertEquals(2, rootReads.get());
        assertEquals(motionTicksAfterTick, motionTicks.get());
    }

    private static List<ModelPart> parts(AtomicInteger motionTicks) {
        return List.of(
                motion(MODEL, motionTicks),
                motion(MODEL, motionTicks),
                motion(MODEL, motionTicks),
                motion(MODEL, motionTicks));
    }

    private static ModelPart motion(ResourceLocation model, AtomicInteger motionTicks) {
        return ModelPart.of(model).moving(ModelPart.tickTransform(machine -> {
            motionTicks.incrementAndGet();
            return new Transform().translate(1f, 0f, 0f);
        }));
    }

    @Test
    void initialPoseUsesTheTickMotionCurrentValueThroughTheSampler() {
        AtomicInteger reads = new AtomicInteger();
        ModelPart part = ModelPart.at(MODEL, new Transform().translate(1f, 0f, 0f))
                .moving(ModelPart.tickTransform(machine -> {
                    reads.incrementAndGet();
                    return new Transform().translate(2f, 0f, 0f);
                }));

        ModelInstance instance = materializeSingle(part).instance();

        assertEquals(13f, instance.getSkeletonInstance().getTransform().getPosition().x, 1e-5f);
        assertEquals(1, reads.get());
    }

    @Test
    void initialPoseUsesTheFrameMotionCurrentValueWithoutATickPublication() {
        AtomicReference<Transform> frame = new AtomicReference<>(
                new Transform().translate(4f, 0f, 0f));
        ModelPart part = ModelPart.at(MODEL, new Transform().translate(1f, 0f, 0f))
                .moving(ModelPart.frameTransform(frame::get));

        ModelInstance instance = materializeSingle(part).instance();

        assertEquals(15f, instance.getSkeletonInstance().getTransform().getPosition().x, 1e-5f);
    }

    @Test
    void visualFramePublicationSnapshotsMotionForAllRenderSamples() {
        AtomicInteger frameReads = new AtomicInteger();
        AtomicReference<Transform> frame = new AtomicReference<>(
                new Transform().translate(2f, 0f, 0f));
        ModelPart part = ModelPart.of(MODEL)
                .moving(ModelPart.frameTransform(() -> {
                    frameReads.incrementAndGet();
                    return frame.get();
                }));
        Assembled assembled = materializeSingle(part);
        assertEquals(1, frameReads.get());

        frame.set(new Transform().translate(5f, 0f, 0f));
        assembled.proxy().publishVisualFrame(new Transform().translate(30f, 0f, 0f));
        assertEquals(2, frameReads.get());

        frame.set(new Transform().translate(99f, 0f, 0f));
        assembled.instance().sample(0.5f);
        assembled.instance().sample(0.5f);
        assertEquals(35f, assembled.instance().getSkeletonInstance().getTransform().getPosition().x, 1e-5f);
        assertEquals(2, frameReads.get(), "render sampling must not read the source again");
    }

    private static Assembled materializeSingle(ModelPart part) {
        ProxyInstance<String> proxy = new ProxyInstance<>(
                ProxyInstanceModelGroupTest.class, "initial", new ElementProxy<>() {
                    @Override
                    public ProxyConfig configure(String element) {
                        return ProxyConfig.parts(List.of(part));
                    }
                },
                ProxyIdentity.ofExternal("test", "initial", 1L),
                () -> new Transform().translate(10f, 0f, 0f));
        List<ModelInstance> instances = new ArrayList<>();
        proxy.materializePartsForTest(List.of(part), (spec, instanceLoc) -> {
            ModelInstance instance = ModelInstanceFixture.minimal();
            instances.add(instance);
            return McModelHandleTestSupport.custom(MODEL, instanceLoc, instance);
        });
        return new Assembled(proxy, instances.getFirst());
    }

    private record Assembled(ProxyInstance<String> proxy, ModelInstance instance) {
    }
}
