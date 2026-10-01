package lib.kasuga.rendering.models.uml.math;
import lib.kasuga.structure.Pair;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class DualQuaternionBlendTest {
    @Test void equivalentOppositeQuaternionSignsDoNotCancelRotationAndTranslation() {
        Quaternionf rotation=new Quaternionf().rotateXYZ(.7f,-.3f,1.2f);
        var first=new DualQuaternion(new Quaternionf(rotation),new Vector3f(2,3,4));
        var opposite=new DualQuaternion(new Quaternionf(-rotation.x,-rotation.y,-rotation.z,-rotation.w),new Vector3f(2,3,4));
        Quaternionf originalReal=new Quaternionf(opposite.getReal()), originalDual=new Quaternionf(opposite.getDual());
        Vector3f point=new Vector3f(1,2,3), expected=first.transformPoint(new Vector3f(point));
        Vector3f actual=DualQuaternion.blend(List.of(Pair.of(first,.5f),Pair.of(opposite,.5f))).transformPoint(new Vector3f(point));
        assertEquals(expected.x,actual.x,1e-5);assertEquals(expected.y,actual.y,1e-5);assertEquals(expected.z,actual.z,1e-5);
        assertEquals(originalReal,opposite.getReal());assertEquals(originalDual,opposite.getDual());
    }
}
