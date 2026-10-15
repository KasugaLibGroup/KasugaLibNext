package lib.kasuga.rendering.models.uml.math.binding;

import lib.kasuga.rendering.models.uml.math.BoneContext;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.basic.BoneBinding;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.bone.PmxBoneBinding;
import lib.kasuga.structure.Pair;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BoneBindingFuncTest {

    private static final float EPSILON = 1.0e-5f;

    @Test
    void sdefPreservesBindPoseWithDegenerateRadii() {
        Fixture fixture = fixture(0.35f);
        Vertex result = BoneBindingFunc.SDEF.apply(
                fixture.vertex,
                List.of(context(fixture.bone0, 0.35f, new Transform()),
                        context(fixture.bone1, 0.65f, new Transform()))
        );

        assertVectorEquals(fixture.vertex.getPosition(), result.getPosition());
        assertVectorEquals(new Vector3f(0, 1, 0), result.getNormal(fixture.mesh));
    }

    @Test
    void sdefBlendsTranslationsWithoutMovingAnExtraVertex() {
        Fixture fixture = fixture(0.25f);
        Transform translated0 = new Transform().translate(2, 0, 0);
        Transform translated1 = new Transform().translate(0, 2, 0);

        Vertex result = BoneBindingFunc.SDEF.apply(
                fixture.vertex,
                List.of(context(fixture.bone0, 0.25f, translated0),
                        context(fixture.bone1, 0.75f, translated1))
        );

        assertVectorEquals(new Vector3f(fixture.vertex.getPosition()).add(0.5f, 1.5f, 0), result.getPosition());
    }

    private static Fixture fixture(float weight0) {
        Bone bone0 = new Bone("bone0", new Transform(), null);
        Bone bone1 = new Bone("bone1", new Transform(), null);
        PmxBoneBinding data = PmxBoneBinding.SDEF(
                0, 1, weight0,
                new Vector3f(0.5f, 0.25f, -0.5f),
                new Vector3f(1, 0, 0),
                new Vector3f(2, 0, 0)
        );
        Vertex vertex = new Vertex(new Vector3f(3, 4, 5), null);
        vertex.setBinding(new BoneBinding(
                new Pair[]{Pair.of(bone0, weight0), Pair.of(bone1, 1.0f - weight0)},
                BoneBindingFunc.SDEF,
                data
        ));
        Mesh mesh = new Mesh(new Vertex[]{vertex}, new Vector3f(), new Transform(), new Material[0], null);
        vertex.getNormals().put(mesh, new Vector3f(0, 1, 0));
        return new Fixture(vertex, mesh, bone0, bone1);
    }

    private static BoneContext context(Bone bone, float weight, Transform skinningTransform) {
        return new BoneContext<>(
                bone, weight, null, new Transform(), new Transform(),
                skinningTransform, new Transform()
        );
    }

    private static void assertVectorEquals(Vector3f expected, Vector3f actual) {
        assertEquals(expected.x, actual.x, EPSILON);
        assertEquals(expected.y, actual.y, EPSILON);
        assertEquals(expected.z, actual.z, EPSILON);
    }

    private record Fixture(Vertex vertex, Mesh mesh, Bone bone0, Bone bone1) {}
}
