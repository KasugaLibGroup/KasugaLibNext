package lib.kasuga.rendering.models.uml.typo.miku_miku_dance.vpd;

import lib.kasuga.rendering.models.uml.dynamic.fsm.ApplyMode;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Pose;
import lib.kasuga.rendering.models.uml.math.Transform;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** VpdSampler tests: a static VPD pose sampled at any time yields the same bone/morph pose. */
class VpdSamplerTest {

    @Test
    void samplesStaticPoseConstantAcrossTime() {
        VpdPose pose = new VpdPose("model", Map.of("root", new Transform().translate(1f, 2f, 3f)),
                Map.of("smile", 0.5f));
        Pose a = VpdSampler.INSTANCE.sample(pose, 0f);
        Pose b = VpdSampler.INSTANCE.sample(pose, 7f);
        assertEquals(1f, a.bones().get("root").transform().getPosition().x, 1e-5f);
        assertEquals(0.5f, a.morphs().get("smile").value(), 1e-5f);
        assertEquals(ApplyMode.REPLACE, a.bones().get("root").mode());
        assertEquals(a.bones().get("root").transform().getPosition(), b.bones().get("root").transform().getPosition());
    }

    @Test
    void durationIsZeroForConstantPose() {
        VpdPose pose = new VpdPose("model", Map.of(), Map.of());
        assertEquals(0f, VpdSampler.INSTANCE.duration(pose));
    }
}