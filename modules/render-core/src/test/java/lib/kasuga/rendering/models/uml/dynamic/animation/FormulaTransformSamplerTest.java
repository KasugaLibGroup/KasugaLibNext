package lib.kasuga.rendering.models.uml.dynamic.animation;

import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import lib.kasuga.rendering.models.uml.math.QuaternionHelper;
import lib.kasuga.rendering.models.uml.math.Transform;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormulaTransformSamplerTest {

    private static final float EPS = 1e-4f;

    private static AnimationClip clip(AnimationClip.FormulaTransformTrack... tracks) {
        return new AnimationClip(
                Id.parse("kasuga_lib:formula_transforms"),
                1f,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(
                        new AnimationClip.FormulaVariable(
                                "derived.angle",
                                "rad(query.wheel_angle)"
                        ),
                        new AnimationClip.FormulaVariable(
                                "derived.sine",
                                "sin(derived.angle)"
                        )
                ),
                List.of(tracks)
        );
    }

    @Test
    void evaluatesDerivedVariablesAndCompoundPartTransform() {
        FormulaTransformSampler sampler = new FormulaTransformSampler(clip(
                AnimationClip.FormulaTransformTrack.part(
                        "crank",
                        new AnimationClip.FormulaTransform(
                                new AnimationClip.FormulaVector("", "", "derived.sine"),
                                new AnimationClip.FormulaVector("-query.wheel_angle", "180", ""),
                                new AnimationClip.FormulaVector("", "1 + derived.sine", "")
                        )
                )
        ));

        sampler.assignQuery("wheel_angle", 90f);
        Transform transform = sampler.samplePart("crank");

        assertNotNull(transform);
        Vector3f translation = transform.getPosition();
        Vector3f scale = transform.transform().getScale(new Vector3f());
        assertEquals(0f, translation.x, EPS);
        assertEquals(0f, translation.y, EPS);
        assertEquals(1f, translation.z, EPS);
        assertEquals(2f, scale.y, EPS);
        assertEquals(1f, scale.x, EPS);
        assertEquals(1f, scale.z, EPS);
        Quaternionf expected = QuaternionHelper.fromZYXDegrees(-90f, 180f, 0f).normalize();
        assertTrue(Math.abs(expected.dot(transform.getRotation().normalize())) > 1f - EPS);
    }

    @Test
    void evaluatesBonesScopedToPartAndSingleInstance() {
        FormulaTransformSampler sampler = new FormulaTransformSampler(clip(
                AnimationClip.FormulaTransformTrack.bone(
                        "moon",
                        "panel",
                        new AnimationClip.FormulaTransform(
                                AnimationClip.FormulaVector.EMPTY,
                                new AnimationClip.FormulaVector("query.panel_angle", "", ""),
                                AnimationClip.FormulaVector.EMPTY
                        )
                ),
                AnimationClip.FormulaTransformTrack.bone(
                        "root",
                        new AnimationClip.FormulaTransform(
                                AnimationClip.FormulaVector.EMPTY,
                                new AnimationClip.FormulaVector("", "query.root_angle", ""),
                                AnimationClip.FormulaVector.EMPTY
                        )
                )
        ));
        sampler.assignQuery("panel_angle", 15f);
        sampler.assignQuery("root_angle", 30f);

        Transform partBone = sampler.sampleBone("moon", "panel");
        Transform singleBone = sampler.sampleBone("root");

        assertNotNull(partBone);
        assertNotNull(singleBone);
        assertNull(sampler.samplePart("moon"));
        assertEquals(15f, Math.toDegrees(partBone.getRotation().angle()), EPS);
        assertEquals(30f, Math.toDegrees(singleBone.getRotation().angle()), EPS);
    }

    @Test
    void cachesUntilAnInputChanges() {
        FormulaTransformSampler sampler = new FormulaTransformSampler(clip(
                AnimationClip.FormulaTransformTrack.part(
                        "wheel",
                        new AnimationClip.FormulaTransform(
                                AnimationClip.FormulaVector.EMPTY,
                                new AnimationClip.FormulaVector("query.wheel_angle", "", ""),
                                AnimationClip.FormulaVector.EMPTY
                        )
                )
        ));
        sampler.assignQuery("wheel_angle", 20f);

        FormulaTransformPose first = sampler.sample();
        FormulaTransformPose second = sampler.sample();
        sampler.assignQuery("wheel_angle", 40f);
        FormulaTransformPose third = sampler.sample();

        assertSame(first, second);
        assertNotNull(third.part("wheel"));
        assertTrue(first != third);
    }

    @Test
    void nonFiniteFormulaUsesIdentityFallback() {
        FormulaTransformSampler sampler = new FormulaTransformSampler(clip(
                AnimationClip.FormulaTransformTrack.part(
                        "part",
                        new AnimationClip.FormulaTransform(
                                new AnimationClip.FormulaVector("", "", "sqrt(-1)"),
                                AnimationClip.FormulaVector.EMPTY,
                                new AnimationClip.FormulaVector("", "sqrt(-1)", "")
                        )
                )
        ));

        Transform transform = sampler.samplePart("part");

        assertNotNull(transform);
        assertEquals(0f, transform.getPosition().z, EPS);
        assertEquals(1f, transform.transform().getScale(new Vector3f()).y, EPS);
        assertTrue(transform.transform().isFinite());
    }

    @Test
    void rejectsNonFiniteAssignedValues() {
        FormulaTransformSampler sampler = new FormulaTransformSampler(clip(
                AnimationClip.FormulaTransformTrack.part(
                        "part",
                        AnimationClip.FormulaTransform.EMPTY
                )
        ));

        assertThrows(IllegalArgumentException.class,
                () -> sampler.assignQuery("wheel_angle", Float.NaN));
    }

    @Test
    void formulaVariablesCannotReplaceQueryInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> new AnimationClip.FormulaVariable("query.wheel_angle", "0"));
        assertThrows(IllegalArgumentException.class,
                () -> new AnimationClip.FormulaVariable("Invalid", "0"));
    }
}
