package lib.kasuga.rendering.models.uml.dynamic.animation.output;

import lib.kasuga.rendering.models.uml.dynamic.animation.scope.Continuity;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.OutputState;
import lib.kasuga.rendering.models.uml.dynamic.fsm.ApplyMode;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Pose;
import lib.kasuga.rendering.models.uml.math.Transform;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PoseOutputInterpolatorTest {

    private static final float EPS = 1.0e-5f;
    private final PoseOutputInterpolator interpolator = new PoseOutputInterpolator();

    @Test
    void interpolateBlendsBonesAndMorphsBetweenLogicalOutputs() {
        Pose previous = new Pose.Builder()
                .bone("root", new Transform().translate(0f, 0f, 0f), ApplyMode.REPLACE)
                .morph("blink", 0f, 1f)
                .build();
        Pose current = new Pose.Builder()
                .bone("root", new Transform().translate(10f, 0f, 0f), ApplyMode.REPLACE)
                .morph("blink", 1f, 1f)
                .build();

        Pose result = interpolator.interpolate(active(previous, current, Continuity.INTERPOLATE), 0.25);

        assertEquals(2.5f, result.bones().get("root").transform().getPosition().x, EPS);
        assertEquals(0.25f, result.morphs().get("blink").value(), EPS);
    }

    @Test
    void holdCurrentAndSnapUseCurrentOutputWithoutInterpolation() {
        Pose previous = Pose.bone("root", new Transform().translate(0f, 0f, 0f));
        Pose current = Pose.bone("root", new Transform().translate(10f, 0f, 0f));

        Pose held = interpolator.interpolate(active(previous, current, Continuity.HOLD_CURRENT), 0.25);
        Pose snapped = interpolator.interpolate(active(previous, current, Continuity.SNAP), 0.25);

        assertEquals(10f, held.bones().get("root").transform().getPosition().x, EPS);
        assertEquals(10f, snapped.bones().get("root").transform().getPosition().x, EPS);
    }

    @Test
    void firstActiveOutputDoesNotInventAPreviousPose() {
        Pose current = Pose.morph("blink", 0.75f);

        Pose result = interpolator.interpolate(active(null, current, Continuity.INTERPOLATE), 0.0);

        assertEquals(current, result);
    }

    @Test
    void channelsAbsentFromCurrentOutputDoNotLingerAtTheEndOfInterpolation() {
        Pose previous = new Pose.Builder()
                .bone("old-bone", new Transform().translate(4f, 0f, 0f), ApplyMode.REPLACE)
                .morph("old-morph", 1f, 1f)
                .build();
        Pose current = Pose.empty();

        Pose result = interpolator.interpolate(active(previous, current, Continuity.INTERPOLATE), 1.0);

        assertTrue(result.isEmpty());
        assertNull(result.bones().get("old-bone"));
        assertNull(result.morphs().get("old-morph"));
    }

    @Test
    void clearedPublicationProducesAnEmptyPose() {
        Pose result = interpolator.interpolate(new OutputState.Cleared<>("detached"), 0.5);

        assertTrue(result.isEmpty());
    }

    private static OutputState<PoseOutput> active(Pose previous, Pose current, Continuity continuity) {
        return new OutputState.Active<>(
                previous == null ? null : new PoseOutput(previous),
                new PoseOutput(current),
                continuity);
    }
}
