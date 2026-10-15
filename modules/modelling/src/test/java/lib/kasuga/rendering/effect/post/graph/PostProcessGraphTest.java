package lib.kasuga.rendering.effect.post.graph;

import lib.kasuga.rendering.effect.post.PostProcessTargetDescriptor;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PostProcessGraphTest {
    private static final PostProcessGraphPass NO_OP = ignored -> {};

    @Test
    void infersDependenciesFromManagedTargetReads() {
        PostProcessGraphTarget scene = target("scene");
        PostProcessGraphTarget mask = target("mask");
        PostProcessGraphTarget distorted = target("distorted");

        PostProcessGraph graph = baseGraph("black_hole")
                .target(descriptor(scene))
                .target(descriptor(mask))
                .target(descriptor(distorted))
                // Add these backwards to prove insertion order is not the execution plan.
                .pass(pass("composite").reads(distorted).writesMain().priority(-100).build())
                .pass(pass("distort").reads(scene, mask).writes(distorted).build())
                .pass(pass("mask").writes(mask).priority(100).build())
                .pass(pass("capture").readsMain().writes(scene).priority(100).build())
                .build();

        assertEquals(
                List.of(id("capture"), id("mask"), id("distort"), id("composite")),
                graph.executionOrder().stream().map(PostProcessGraphPassDescriptor::id).toList()
        );
    }

    @Test
    void rejectsFeedbackMultipleWritersAndMissingProducer() {
        PostProcessGraphTarget scene = target("scene_validation");

        assertThrows(IllegalArgumentException.class, () -> pass("feedback")
                .reads(scene)
                .writes(scene)
                .build());

        assertThrows(IllegalArgumentException.class, () -> baseGraph("multiple_writers")
                .target(descriptor(scene))
                .pass(pass("first").writes(scene).build())
                .pass(pass("second").writes(scene).build())
                .build());

        assertThrows(IllegalArgumentException.class, () -> baseGraph("missing_producer")
                .target(descriptor(scene))
                .pass(pass("reader").reads(scene).writesMain().build())
                .build());
    }

    @Test
    void rejectsExplicitDependencyCycles() {
        assertThrows(IllegalArgumentException.class, () -> baseGraph("cycle")
                .pass(pass("cycle_a").writesMain().after(id("cycle_b")).build())
                .pass(pass("cycle_b").writesMain().after(id("cycle_a")).build())
                .build());
    }

    @Test
    void scopesPhysicalTargetsToTheirGraph() {
        PostProcessGraphTarget sharedLogicalName = target("scene_shared");
        PostProcessGraph first = baseGraph("first")
                .target(descriptor(sharedLogicalName))
                .pass(pass("first_writer").writes(sharedLogicalName).build())
                .build();
        PostProcessGraph second = baseGraph("second")
                .target(descriptor(sharedLogicalName))
                .pass(pass("second_writer").writes(sharedLogicalName).build())
                .build();

        ResourceLocation firstPhysical = first.allocatedTargetIds().iterator().next();
        ResourceLocation secondPhysical = second.allocatedTargetIds().iterator().next();
        org.junit.jupiter.api.Assertions.assertNotEquals(firstPhysical, secondPhysical);
    }

    private static PostProcessGraph.Builder baseGraph(String path) {
        return PostProcessGraph.builder(id(path));
    }

    private static PostProcessGraphPassDescriptor.Builder pass(String path) {
        return PostProcessGraphPassDescriptor.builder(id(path), NO_OP);
    }

    private static PostProcessGraphTarget target(String path) {
        return PostProcessGraphTarget.managed(id(path));
    }

    private static PostProcessTargetDescriptor descriptor(PostProcessGraphTarget target) {
        return PostProcessTargetDescriptor.builder(target.id()).screenScale(1).build();
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("kasuga_graph_test", path);
    }
}
