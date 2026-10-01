package lib.kasuga.rendering.models.uml.dynamic.morph;

import lib.kasuga.rendering.models.uml.dynamic.morph.holder.GroupMorph;
import lib.kasuga.rendering.models.uml.dynamic.morph.holder.MorphHolder;
import lib.kasuga.rendering.models.uml.dynamic.morph.types.VertexNormalMorph;
import lib.kasuga.rendering.models.uml.dynamic.morph.types.VertexPosMorph;
import lib.kasuga.rendering.models.uml.dynamic.morph.types.VertexUvMorph;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.math.binding.BoneBindingFunc;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.BoneBinding;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.material.MaterialSet;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import lib.kasuga.rendering.models.uml.structure.skeleton.Anchor;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.structure.skeleton.Skeleton;
import lib.kasuga.rendering.models.uml.util.MeshMode;
import lib.kasuga.structure.Pair;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression / behavior tests for the optimized morph write path:
 * <ul>
 *   <li>Step 1 — unchanged values are skipped (no spurious dirty-marking / recompute);</li>
 *   <li>Step 2 — factor/value arrays keyed by {@code MorphType} ordinals;</li>
 *   <li>Step 3 — {@code VertexResult} + vertex→morph sets array-indexed by {@code Vertex.index}.</li>
 * </ul>
 */
class MorphInstanceOptimizedPathTest {

    private static final float EPS = 1e-4f;

    // ── Fixture: n independent vertices in one mesh ────────────────

    private static Fixture fixture(int vertexCount) {
        Bone root = new Bone("root", new Transform(), null);
        root.setChildren(new Bone[0]);
        Skeleton skeleton = new Skeleton(new Bone[]{root}, root, new Anchor[0], null, new Transform());
        Vertex[] vertices = new Vertex[vertexCount];
        for (int i = 0; i < vertexCount; i++) {
            vertices[i] = new Vertex(new Vector3f(i, 0, 0), null);
            @SuppressWarnings("unchecked")
            Pair<Bone, Float>[] noWeights = new Pair[0];
            vertices[i].setBinding(new BoneBinding(noWeights, BoneBindingFunc.IDENTITY, null));
        }
        Material material = new Material(new Texture[0], null);
        Mesh mesh = new Mesh(vertices, new Vector3f(), new Transform(), new Material[]{material}, null);
        Model model = new Model(vertices, new Mesh[]{mesh}, new Bone[]{root}, skeleton,
                new MaterialSet(List.of(), List.of(material)), MeshMode.TRIANGLES, null, null);
        return new Fixture(model, vertices, mesh, material);
    }

    private static void assertVertex(MorphInstance<Object> instance, Vertex vertex, Vector3f expected) {
        assertEquals(expected, instance.getVertexPos(vertex, new Vector3f()));
    }

    @Test
    void pendingActivationRequestsUpdateBeforeFirstResultExists() {
        Fixture f = fixture(1);
        f.model().getMorph().addMorph("m", new VertexPosMorph<>(f.v0(), "m", new Vector3f(1, 0, 0)));
        MorphInstance<Object> instance = new MorphInstance<>(f.model().getMorph());
        instance.activateMorph("m", 1f);
        assertTrue(instance.shouldUpdate());
    }

    @Test
    void changingOneVertexPreservesOtherActiveMorphs() {
        Fixture f = fixture(2);
        f.model().getMorph().addMorph("a", new VertexPosMorph<>(f.v(0), "a", new Vector3f(2, 0, 0)));
        f.model().getMorph().addMorph("b", new VertexPosMorph<>(f.v(1), "b", new Vector3f(1, 3, 0)));
        MorphInstance<Object> instance = new MorphInstance<>(f.model().getMorph());
        instance.activateMorph("a", 1f);
        instance.activateMorph("b", 1f);
        instance.update();
        instance.clearLastChanged();
        instance.activateMorph("a", .5f);
        instance.update();
        assertVertex(instance, f.v(0), new Vector3f(1, 0, 0));
        assertVertex(instance, f.v(1), new Vector3f(1, 3, 0));
        instance.deactivateMorph("a");
        instance.update();
        assertVertex(instance, f.v(1), new Vector3f(1, 3, 0));
    }

    @Test
    void thirdPartyPositionMorphOverrideKeepsItsBehavior() {
        Fixture f = fixture(1);
        f.model().getMorph().addMorph("custom", new VertexPosMorph<Object>(f.v0(), "custom", new Vector3f()) {
            @Override public Vector3f morph(Vertex input, float percentage, float factor) {
                return new Vector3f(7 * percentage, 0, 0);
            }
        });
        MorphInstance<Object> instance = new MorphInstance<>(f.model().getMorph());
        instance.activateMorph("custom", 1f); instance.update();
        assertVertex(instance, f.v0(), new Vector3f(7, 0, 0));
        instance.activateMorph("custom", .5f); instance.update();
        assertVertex(instance, f.v0(), new Vector3f(3.5f, 0, 0));
    }

    // ── Step 1: unchanged-value skip ───────────────────────────────

    @Test
    void repeatedSameValueDoesNotMarkDirty() {
        Fixture f = fixture(1);
        Morph<Object> morph = f.model().getMorph();
        VertexPosMorph<Object> m = new VertexPosMorph<>(f.v0(), "m", new Vector3f(1, 0, 0));
        morph.addMorph("m", m);
        MorphInstance<Object> instance = new MorphInstance<>(morph);

        assertTrue(instance.activateMorph("m", 1f));
        instance.update();
        assertFalse(instance.isDirty());

        // same value again → must NOT mark the vertex dirty (the ~36% cost is skipped on plateau frames)
        assertTrue(instance.activateMorph("m", 1f));
        assertFalse(instance.isDirty());
        assertFalse(instance.isVertexDirtyAt(0));
        // result is preserved from the previous update
        assertVertex(instance, f.v0(), new Vector3f(1, 0, 0));
    }

    @Test
    void changedValueMarksDirtyAndRecomputes() {
        Fixture f = fixture(1);
        Morph<Object> morph = f.model().getMorph();
        morph.addMorph("m", new VertexPosMorph<>(f.v0(), "m", new Vector3f(1, 0, 0)));
        MorphInstance<Object> instance = new MorphInstance<>(morph);

        instance.activateMorph("m", 1f);
        instance.update();
        assertVertex(instance, f.v0(), new Vector3f(1, 0, 0));

        instance.activateMorph("m", 0.5f);
        assertTrue(instance.isDirty());
        instance.update();
        assertVertex(instance, f.v0(), new Vector3f(0.5f, 0, 0));
    }

    @Test
    void epsilonBoundaryControlsSkipping() {
        Fixture f = fixture(1);
        Morph<Object> morph = f.model().getMorph();
        morph.addMorph("m", new VertexPosMorph<>(f.v0(), "m", new Vector3f(1, 0, 0)));
        MorphInstance<Object> instance = new MorphInstance<>(morph);

        instance.activateMorph("m", 1f);
        instance.update();

        // sub-epsilon change → skipped
        instance.activateMorph("m", 0.99995f);
        assertFalse(instance.isDirty());

        // change above epsilon → dirty (values stay inside the [0,1] clamp)
        instance.activateMorph("m", 0.999f);
        assertTrue(instance.isDirty());
        instance.update();
        // delta = 0.999 × (target − origin) = (0.999, 0, 0)
        assertVertex(instance, f.v0(), new Vector3f(0.999f, 0, 0));
    }

    @Test
    void deactivateReturnsToBindPosition() {
        Fixture f = fixture(1);
        Morph<Object> morph = f.model().getMorph();
        morph.addMorph("m", new VertexPosMorph<>(f.v0(), "m", new Vector3f(1, 0, 0)));
        MorphInstance<Object> instance = new MorphInstance<>(morph);

        instance.activateMorph("m", 1f);
        instance.update();
        assertVertex(instance, f.v0(), new Vector3f(1, 0, 0));

        instance.deactivateMorph("m");
        assertTrue(instance.isDirty());
        instance.update();
        assertVertex(instance, f.v0(), new Vector3f(0, 0, 0));
    }

    // ── Step 2: ordinal-indexed arrays ─────────────────────────────

    @Test
    void registeredMorphsReceiveConsecutiveOrdinals() {
        Fixture f = fixture(1);
        Morph<Object> morph = f.model().getMorph();
        VertexPosMorph<Object> a = new VertexPosMorph<>(f.v0(), "a", new Vector3f(1, 0, 0));
        VertexPosMorph<Object> b = new VertexPosMorph<>(f.v0(), "b", new Vector3f(0, 1, 0));
        VertexPosMorph<Object> c = new VertexPosMorph<>(f.v0(), "c", new Vector3f(0, 0, 1));
        morph.addMorph("a", a);
        morph.addMorph("b", b);
        morph.addMorph("c", c);

        assertEquals(0, a.getMorphTypeIndex());
        assertEquals(1, b.getMorphTypeIndex());
        assertEquals(2, c.getMorphTypeIndex());
        assertEquals(3, morph.morphTypeCount());

        // never registered → stays -1 (falls back to the small map path)
        VertexPosMorph<Object> orphan = new VertexPosMorph<>(f.v0(), "orphan", new Vector3f(2, 2, 2));
        assertEquals(-1, orphan.getMorphTypeIndex());
    }

    @Test
    void multipleMorphsOverMultipleVerticesThroughArrays() {
        Fixture f = fixture(3);
        Morph<Object> morph = f.model().getMorph();
        morph.addMorph("m0", new VertexPosMorph<>(f.v(0), "m0", new Vector3f(1, 0, 0)));
        morph.addMorph("m1", new VertexPosMorph<>(f.v(1), "m1", new Vector3f(0, 2, 0)));
        morph.addMorph("m2", new VertexPosMorph<>(f.v(2), "m2", new Vector3f(0, 0, 3)));
        MorphInstance<Object> instance = new MorphInstance<>(morph);

        instance.activateMorph("m0", 1f);
        instance.activateMorph("m1", 0.5f);
        instance.activateMorph("m2", 1f);
        instance.update();

        assertVertex(instance, f.v(0), new Vector3f(1, 0, 0));
        assertVertex(instance, f.v(1), new Vector3f(0.5f, 1, 0));
        assertVertex(instance, f.v(2), new Vector3f(0, 0, 3));
    }

    @Test
    void vertexIndicesAssignedFromModelArrayPosition() {
        Fixture f = fixture(3);
        MorphInstance<Object> instance = new MorphInstance<>(f.model().getMorph());
        assertEquals(0, f.v(0).getIndex());
        assertEquals(1, f.v(1).getIndex());
        assertEquals(2, f.v(2).getIndex());

        // a foreign vertex that is not part of the model → index stays -1 → no result (bind position)
        Vertex foreign = new Vertex(new Vector3f(9, 9, 9), null);
        assertEquals(-1, foreign.getIndex());
        assertVertex(instance, foreign, new Vector3f(9, 9, 9));
    }

    // ── Step 2 fallback: unregistered group prototype ─────────────

    @Test
    void unregisteredGroupPrototypeKeepsLegacySemantics() {
        Fixture f = fixture(1);
        Morph<Object> morph = f.model().getMorph();
        VertexPosMorph<Object> registered = new VertexPosMorph<>(f.v0(), "registered", new Vector3f(1, 0, 0));
        morph.addMorph("registered", registered);

        // group referencing a prototype that never went through addMorph
        VertexPosMorph<Object> orphan = new VertexPosMorph<>(f.v0(), "orphan", new Vector3f(0, 5, 0));
        GroupMorph<Object> group = new GroupMorph<>("g");
        group.addHolder(new MorphHolder<>("g", orphan), 1f);
        morph.addGroup("g", group);

        MorphInstance<Object> instance = new MorphInstance<>(morph);
        // must not throw (no array OOB), and registered morph still works
        assertTrue(instance.activateMorph("g", 1f));
        instance.activateMorph("registered", 1f);
        instance.update();
        // orphan is not in the vertex→morph registry, so only the registered morph contributes — legacy behavior
        assertVertex(instance, f.v0(), new Vector3f(1, 0, 0));
    }

    // ── Step 3: normal / UV read through the indexed result array ─

    @Test
    void uvAndNormalMorphsReadThroughIndexedResults() {
        Fixture f = fixture(1);
        Vector2f baseUv = new Vector2f(0.25f, 0.5f);
        f.v0().addUV(f.mesh(), f.material(), baseUv);
        f.v0().getNormals().put(f.mesh(), new Vector3f(0, 0, 1));

        Morph<Object> morph = f.model().getMorph();
        morph.addMorph("uv", new VertexUvMorph<>(f.v0(), "uv", f.mesh(), f.material(), new Vector2f(0.75f, 1f)));
        morph.addMorph("n", new VertexNormalMorph<>(f.v0(), "n", f.mesh(), new Vector3f(0, 0, 2)));
        MorphInstance<Object> instance = new MorphInstance<>(morph);

        instance.activateMorph("uv", 1f);
        instance.activateMorph("n", 1f);
        instance.update();

        assertEquals(new Vector2f(0.75f, 1f),
                instance.getVertexUv(f.v0(), f.mesh(), f.material(), new Vector2f()));
        assertEquals(new Vector3f(0, 0, 1),
                instance.getVertexNormal(f.v0(), f.mesh(), new Vector3f()));
    }

    // ── Fixture record ────────────────────────────────────────────

    private record Fixture(Model model, Vertex[] vertices, Mesh mesh, Material material) {
        Vertex v0() { return vertices[0]; }
        Vertex v(int i) { return vertices[i]; }
    }
}
