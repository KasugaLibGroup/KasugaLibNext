package test.kasuga.modelling.demo.nebula;

import lib.kasuga.rendering.effect.post.PostProcessTargetDescriptor;
import lib.kasuga.rendering.effect.post.graph.PostProcessGraphTarget;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class NebulaPipelineTest {
    @Test void actualDemoGraphDeclaresItsGbufferAndInfersTheExecutionOrder() {
        var graph = NebulaPipeline.create((context, frame) -> true, ignored -> {}, ignored -> {});
        assertEquals(List.of("starship_geometry_pass", "nebula_march", "nebula_present"),
                graph.executionOrder().stream().map(pass -> pass.id().getPath()).toList());
        assertEquals(Set.of(NebulaPipeline.GBUFFER), graph.executionOrder().get(0).writes());
        assertEquals(Set.of(NebulaPipeline.GBUFFER), graph.executionOrder().get(1).reads());
        assertEquals(Set.of(NebulaPipeline.VOLUME), graph.executionOrder().get(1).writes());
        assertEquals(Set.of(PostProcessGraphTarget.main()), graph.executionOrder().get(2).writes());
        var target = graph.targets().get(NebulaPipeline.GBUFFER);
        assertEquals(List.of(PostProcessTargetDescriptor.ColorFormat.RGBA16F,
                PostProcessTargetDescriptor.ColorFormat.RGBA16F,
                PostProcessTargetDescriptor.ColorFormat.RGBA16F), target.colorAttachments());
        assertTrue(target.useDepth());
        assertEquals(3840, target.resolveWidth(3840));
        assertEquals(2160, target.resolveHeight(2160));
        assertEquals(2, graph.allocatedTargetIds().size());
        assertTrue(graph.allocatedTargetIds().contains(graph.allocatedTargetId(NebulaPipeline.GBUFFER)));
        assertThrows(IllegalArgumentException.class, () -> graph.allocatedTargetId(PostProcessGraphTarget.main()));
    }

    @Test void fsmUsesTheSharedClockForManualTriggerAndSkippedPhases() {
        var sim = new NebulaSimulation(42);
        assertEquals(NebulaWarpSequence.Phase.CRUISE, sim.warpPhase());
        assertTrue(sim.triggerWarp());
        assertEquals(NebulaWarpSequence.Phase.CHARGING, sim.warpPhase());
        assertFalse(sim.triggerWarp(), "A repeated trigger must not rearm the same charge");
        // One deliberately slow capture frame crosses charging, jump and the warp entry boundary.
        sim.captureFrame(.2f);
        assertEquals(NebulaWarpSequence.Phase.WARP, sim.warpPhase());
        assertEquals(5, sim.time(1), .00001);
        sim.reset();
        assertEquals(NebulaWarpSequence.Phase.CRUISE, sim.warpPhase());
        for (int i = 0; i < 280; i++) sim.tick();
        assertEquals(NebulaWarpSequence.Phase.READY, sim.warpPhase());
        assertTrue(sim.triggerWarp());
        assertEquals(NebulaWarpSequence.Phase.CHARGING, sim.warpPhase());
    }

    @Test void cameraPlayerFollowsWithoutAdvancingTheSimulationClock() {
        var sim = new NebulaSimulation(42);
        for (int i = 0; i < 720; i++) sim.captureFrame(60);
        float time = sim.time(1);
        var craft = NebulaFlight.sample(time);
        var warp = NebulaFlight.sampleWarp(sim.warpTime(1));
        var expected = NebulaFlight.camera(craft, warp, time, 1280, 720, 0, 0);
        for (int i = 0; i < 10; i++) assertEquals(expected, sim.camera(craft, warp, 1280, 720, 0, 0, 1));
        assertEquals(time, sim.time(1));
    }
}
