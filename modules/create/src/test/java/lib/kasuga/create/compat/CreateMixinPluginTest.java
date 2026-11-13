package lib.kasuga.create.compat;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.*;

class CreateMixinPluginTest {
    @Test void selectsBothSupportedCurveLayoutsWithoutDisablingTheRailOffset() {
        var node = new ClassNode();
        node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "getBakedSegments",
                "()[Lcom/simibubi/create/content/trains/track/BezierConnection$SegmentAngles;", null, null));
        assertTrue(CreateMixinPlugin.legacyCurve(node));
        node.methods.getFirst().desc = "()Lcom/simibubi/create/content/trains/track/BezierConnection$SegmentAngles;";
        assertFalse(CreateMixinPlugin.legacyCurve(node));
    }
    @Test void unknownLayoutsFailExplicitlyInsteadOfSilentlyLosingCustomGauge() {
        assertThrows(IllegalStateException.class, () -> CreateMixinPlugin.legacyCurve(new ClassNode()));
    }
}
