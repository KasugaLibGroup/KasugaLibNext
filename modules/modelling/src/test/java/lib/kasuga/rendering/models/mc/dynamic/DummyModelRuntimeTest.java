package lib.kasuga.rendering.models.mc.dynamic;

import lib.kasuga.rendering.models.mc.api.McDummyModels;
import lib.kasuga.rendering.models.uml.dynamic.DummyModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstanceFixture;
import lib.kasuga.rendering.models.uml.dynamic.ModelPoseBinding;
import lib.kasuga.rendering.models.uml.dynamic.PoseDriver;
import lib.kasuga.rendering.models.uml.math.Transform;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DummyModelRuntimeTest {
    @Test
    void automaticGroupsRunParentFirstOncePerFrameAndDoNotNeedRendering() {
        int baseline = MinecraftModelRuntime.registeredCount();
        try (var outer = McDummyModels.create(); var inner = McDummyModels.create();
             var leaf = ModelInstanceFixture.minimal()) {
            outer.defineAnchor("inner", new Transform().translate(2, 0, 0));
            inner.defineAnchor("leaf", new Transform().translate(0, 3, 0));
            outer.attach("inner", inner);
            inner.attach("leaf", leaf);
            List<String> samples = new ArrayList<>();
            AtomicInteger frame = new AtomicInteger();
            ModelPoseBinding.install(outer, new PoseDriver() {
                @Override public void sample(float partialTick) {
                    samples.add("outer");
                    outer.getSkeletonInstance().transformRoot(new Transform().translate(frame.get() * 10, 0, 0));
                }
            });
            ModelPoseBinding.install(inner, new PoseDriver() {
                @Override public void sample(float partialTick) { samples.add("inner"); }
            });
            ModelPoseBinding.install(leaf, new PoseDriver() {
                @Override public void sample(float partialTick) { samples.add("leaf"); }
            });
            MinecraftModelRuntime.register(leaf);
            try {
                for (int i = 1; i <= 8; i++) {
                    samples.clear(); frame.set(i);
                    MinecraftModelRuntime.evaluateFrame(0.5f, 1f / 60f);
                    assertEquals(List.of("outer", "inner", "leaf"), samples);
                    assertEquals(new Vector3f(i * 10 + 2, 3, 0), leaf.getSkeletonInstance().getTransform().getPosition());
                    Object token = new Object();
                    leaf.sample(0.5f, token);
                    leaf.prepareRenderFrame(token);
                    assertEquals(List.of("outer", "inner", "leaf"), samples, "drawing reuses the prepared frame");
                }
            } finally { MinecraftModelRuntime.unregister(leaf); }
            assertEquals(baseline + 2, MinecraftModelRuntime.registeredCount());
        }
        assertEquals(baseline, MinecraftModelRuntime.registeredCount());
    }

    @Test
    void invisibleUnregisteredLeafCanSampleAnAlreadyPreparedAutomaticParent() {
        try (var group = McDummyModels.create(); var child = ModelInstanceFixture.minimal()) {
            group.defineAnchor("a", new Transform().translate(2, 0, 0));
            group.attach("a", child);
            AtomicInteger samples = new AtomicInteger();
            ModelPoseBinding.install(group, new PoseDriver() {
                @Override public void sample(float partialTick) {
                    group.getSkeletonInstance().transformRoot(new Transform().translate(samples.incrementAndGet(), 0, 0));
                }
            });
            MinecraftModelRuntime.evaluateFrame(0.2f, 0.01f);
            child.sample(0.2f);
            child.update();
            assertEquals(1, samples.get());
            assertEquals(new Vector3f(3, 0, 0), child.getSkeletonInstance().getTransform().getPosition());
            MinecraftModelRuntime.evaluateFrame(0.7f, 0.01f);
            child.sample(0.7f);
            child.update();
            assertEquals(2, samples.get());
            assertEquals(new Vector3f(4, 0, 0), child.getSkeletonInstance().getTransform().getPosition());
        }
    }

    @Test
    void closingAutomaticParentUnregistersImmediatelyAndLeavesTheChildUsable() {
        int baseline = MinecraftModelRuntime.registeredCount();
        try (var group = McDummyModels.create(new Transform().translate(4, 0, 0));
             var child = ModelInstanceFixture.minimal()) {
            group.defineAnchor("a", new Transform().translate(2, 0, 0));
            group.attach("a", child);
            MinecraftModelRuntime.evaluateFrame(0.5f, 0.01f);
            group.close(); group.close();
            assertEquals(baseline, MinecraftModelRuntime.registeredCount());
            assertNull(child.getRootTransformDriver());
            assertFalse(child.isClosed());
            child.evaluateFrame(0.5f, 0.01f);
            assertEquals(new Vector3f(6, 0, 0), child.getSkeletonInstance().getTransform().getPosition());
        }
    }
}
