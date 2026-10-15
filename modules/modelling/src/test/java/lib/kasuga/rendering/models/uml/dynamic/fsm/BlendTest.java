package lib.kasuga.rendering.models.uml.dynamic.fsm;

import lib.kasuga.rendering.models.uml.math.Transform;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Blend math across layers (BASE/ADDITIVE/OVERRIDE). ADDITIVE accumulates in the {@link Blender}, not in MorphInstance's single slot. */
class BlendTest {

    @Test
    void baseThenAdditiveThenOverride() {
        Blender blender = new Blender();
        blender.applyLayer(BlendMode.BASE, Pose.morph("blink", 0.5f), 1f, BoneMask.all());
        assertEquals(0.5f, blender.morphs().get("blink").value(), 1e-4f);

        blender.applyLayer(BlendMode.ADDITIVE, Pose.morph("blink", 0.2f), 1f, BoneMask.all());
        assertEquals(0.7f, blender.morphs().get("blink").value(), 1e-4f);

        blender.applyLayer(BlendMode.OVERRIDE, Pose.morph("blink", 0.3f), 1f, BoneMask.all());
        assertEquals(0.3f, blender.morphs().get("blink").value(), 1e-4f);
    }

    @Test
    void additiveClampsToOne() {
        Blender blender = new Blender();
        blender.applyLayer(BlendMode.BASE, Pose.morph("x", 0.9f), 1f, BoneMask.all());
        blender.applyLayer(BlendMode.ADDITIVE, Pose.morph("x", 0.5f), 1f, BoneMask.all());
        assertEquals(1f, blender.morphs().get("x").value(), 1e-4f);
    }

    @Test
    void weightScalesBase() {
        Blender blender = new Blender();
        blender.applyLayer(BlendMode.BASE, Pose.morph("x", 1f), 0.5f, BoneMask.all());
        assertEquals(0.5f, blender.morphs().get("x").value(), 1e-4f);
    }

    //region BoneAccum ApplyMode recording

    @Test
    void boneAccumRecordsBaseMode() {
        Blender blender = new Blender();
        blender.applyLayer(BlendMode.BASE, Pose.bone("b", new Transform().translate(1, 0, 0), ApplyMode.ADD), 1f, BoneMask.all());
        Blender.BoneAccum accum = blender.bones().get("b");
        assertEquals(ApplyMode.ADD, accum.mode);
        assertEquals(1f, accum.base.getPosition().x, 1e-4f);
    }

    @Test
    void boneAccumOverrideWriterWinsMode() {
        Blender blender = new Blender();
        blender.applyLayer(BlendMode.BASE, Pose.bone("b", new Transform().translate(1, 0, 0), ApplyMode.ADD), 1f, BoneMask.all());
        blender.applyLayer(BlendMode.OVERRIDE, Pose.bone("b", new Transform().translate(0, 2, 0), ApplyMode.REPLACE), 1f, BoneMask.all());
        Blender.BoneAccum accum = blender.bones().get("b");
        assertTrue(accum.hasOverride);
        assertEquals(ApplyMode.REPLACE, accum.mode);
    }

    @Test
    void boneAccumLateBaseDoesNotClobberOverrideMode() {
        Blender blender = new Blender();
        blender.applyLayer(BlendMode.OVERRIDE, Pose.bone("b", new Transform().translate(0, 2, 0), ApplyMode.MULTIPLY), 1f, BoneMask.all());
        blender.applyLayer(BlendMode.BASE, Pose.bone("b", new Transform().translate(1, 0, 0), ApplyMode.ADD), 1f, BoneMask.all());
        Blender.BoneAccum accum = blender.bones().get("b");
        assertTrue(accum.hasOverride);
        assertEquals(ApplyMode.MULTIPLY, accum.mode);
    }

    @Test
    void boneAccumDefaultsToReplace() {
        Blender blender = new Blender();
        blender.applyLayer(BlendMode.BASE, Pose.bone("b", new Transform().translate(1, 0, 0)), 1f, BoneMask.all());
        assertEquals(ApplyMode.REPLACE, blender.bones().get("b").mode);
    }

    //endregion

    //region resolveBoneWrites — pure sink-mode dispatch (REPLACE / MULTIPLY / ADD)

    @Test
    void resolveOverrideWinsWithItsOwnMode() {
        Blender blender = new Blender();
        blender.applyLayer(BlendMode.BASE, Pose.bone("b", new Transform().translate(1, 0, 0), ApplyMode.ADD), 1f, BoneMask.all());
        blender.applyLayer(BlendMode.OVERRIDE, Pose.bone("b", new Transform().translate(0, 2, 0), ApplyMode.MULTIPLY), 1f, BoneMask.all());
        List<Blender.BoneWrite> writes = Blender.resolveBoneWrites(blender.bones().get("b"));
        assertEquals(1, writes.size());
        assertEquals(ApplyMode.MULTIPLY, writes.get(0).mode());
        assertEquals(2f, writes.get(0).transform().getPosition().y, 1e-4f);
    }

    @Test
    void resolveBaseWithItsModeThenAdditiveMultiply() {
        Blender blender = new Blender();
        blender.applyLayer(BlendMode.BASE, Pose.bone("b", new Transform().translate(1, 0, 0), ApplyMode.ADD), 1f, BoneMask.all());
        blender.applyLayer(BlendMode.ADDITIVE, Pose.bone("b", new Transform().translate(0, 1, 0), ApplyMode.ADD), 1f, BoneMask.all());
        List<Blender.BoneWrite> writes = Blender.resolveBoneWrites(blender.bones().get("b"));
        assertEquals(2, writes.size());
        assertEquals(ApplyMode.ADD, writes.get(0).mode());
        assertEquals(ApplyMode.MULTIPLY, writes.get(1).mode());
        assertEquals(1f, writes.get(1).transform().getPosition().y, 1e-4f);
    }

    @Test
    void resolveAdditiveOnlyStaysMultiply() {
        Blender blender = new Blender();
        blender.applyLayer(BlendMode.ADDITIVE, Pose.bone("b", new Transform().translate(0, 1, 0), ApplyMode.ADD), 1f, BoneMask.all());
        List<Blender.BoneWrite> writes = Blender.resolveBoneWrites(blender.bones().get("b"));
        assertEquals(1, writes.size());
        assertEquals(ApplyMode.MULTIPLY, writes.get(0).mode());
    }

    @Test
    void resolveEmptyAccumYieldsNoWrites() {
        Blender blender = new Blender();
        blender.applyLayer(BlendMode.OVERRIDE, Pose.morph("m", 0.5f), 1f, BoneMask.all());
        assertTrue(blender.bones().isEmpty());
        Blender.BoneAccum empty = new Blender.BoneAccum();
        assertFalse(empty.hasOverride);
        assertEquals(0, Blender.resolveBoneWrites(empty).size());
    }

    //endregion
}
