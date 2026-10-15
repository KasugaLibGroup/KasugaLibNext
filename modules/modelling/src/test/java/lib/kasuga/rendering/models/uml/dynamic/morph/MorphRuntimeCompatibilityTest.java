package lib.kasuga.rendering.models.uml.dynamic.morph;

import lib.kasuga.rendering.models.uml.dynamic.morph.holder.GroupMorph;
import lib.kasuga.rendering.models.uml.dynamic.morph.holder.MorphHolder;
import lib.kasuga.rendering.models.uml.dynamic.morph.types.VertexPosMorph;
import lib.kasuga.rendering.models.uml.dynamic.morph.types.VertexUvMorph;
import lib.kasuga.rendering.models.uml.dynamic.morph.types.MaterialColorMorph;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.math.binding.BoneBindingFunc;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.BoneBinding;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.material.MaterialSet;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import lib.kasuga.rendering.models.uml.structure.material.Sprite;
import lib.kasuga.rendering.models.uml.structure.skeleton.Anchor;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.structure.skeleton.Skeleton;
import lib.kasuga.rendering.models.uml.util.MeshMode;
import lib.kasuga.structure.Pair;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MorphRuntimeCompatibilityTest {
    @Test
    void oneMorphIdentifierActivatesAllOfItsOffsets() {
        Fixture fixture = fixture();
        Morph<Object> morph = fixture.model.getMorph();
        morph.addMorph("combined", new VertexPosMorph<>(
                fixture.vertex, "combined", new Vector3f(1, 0, 0)));
        morph.addMorph("combined", new VertexPosMorph<>(
                fixture.vertex, "combined", new Vector3f(0, 2, 0)));

        MorphInstance<Object> instance = new MorphInstance<>(morph);
        assertTrue(instance.activateMorph("combined", 1f));
        instance.update();

        Vector3f result = instance.getVertexPos(fixture.vertex, new Vector3f());
        assertEquals(new Vector3f(1, 2, 0), result);
    }

    @Test
    void groupWeightsAllOffsetsOfReferencedMorph() {
        Fixture fixture = fixture();
        Morph<Object> morph = fixture.model.getMorph();
        VertexPosMorph<Object> x = new VertexPosMorph<>(
                fixture.vertex, 0, new Vector3f(2, 0, 0));
        VertexPosMorph<Object> y = new VertexPosMorph<>(
                fixture.vertex, 0, new Vector3f(0, 4, 0));
        morph.addMorph(0, x);
        morph.addMorph(0, y);
        GroupMorph<Object> group = new GroupMorph<>(1);
        group.addHolder(new MorphHolder<>(1, x), 0.5f);
        group.addHolder(new MorphHolder<>(1, y), 0.5f);
        morph.addGroup(1, group);

        MorphInstance<Object> instance = new MorphInstance<>(morph);
        assertTrue(instance.activateMorph(1, 1f));
        instance.update();
        assertEquals(new Vector3f(1, 2, 0),
                instance.getVertexPos(fixture.vertex, new Vector3f()));
    }

    @Test
    void uvMorphProducesADeltaInsteadOfAddingTheBaseUvTwice() {
        Fixture fixture = fixture();
        Vector2f base = new Vector2f(0.25f, 0.5f);
        fixture.vertex.addUV(fixture.mesh, fixture.material, base);
        VertexUvMorph<String> morph = new VertexUvMorph<>(fixture.vertex, "uv", fixture.mesh,
                fixture.material, new Vector2f(0.5f, 0.75f));
        assertEquals(new Vector2f(0.25f, 0.25f), morph.morph(fixture.vertex, 1f, 1f));
    }

    @Test
    void materialAddAndMultiplyMorphsCanBeActiveTogether() {
        Fixture fixture = fixture();
        Morph<Object> morph = fixture.model.getMorph();
        morph.addMorph("multiply", new MaterialColorMorph<>(fixture.material, "multiply",
                new Vector4f(2f), BlendMode.MULTIPLY));
        morph.addMorph("add", new MaterialColorMorph<>(fixture.material, "add",
                new Vector4f(0.25f), BlendMode.ADD));
        MorphInstance<Object> instance = new MorphInstance<>(morph);
        instance.activateMorph("multiply", 1f);
        instance.activateMorph("add", 1f);
        instance.update();
        Sprite sprite = new Sprite(null, new Vector2f(), new Vector2f(), new Vector2f(), new Vector2f(),
                new Vector4f(0.5f), null, null, null);

        Vector4f value = new Vector4f();
        instance.getMaterialColor(fixture.material, sprite, value);
        assertEquals(new Vector4f(1.25f), value);
    }

    private static Fixture fixture() {
        Bone root = new Bone("root", new Transform(), null);
        root.setChildren(new Bone[0]);
        Skeleton skeleton = new Skeleton(new Bone[]{root}, root, new Anchor[0], null, new Transform());
        Vertex vertex = new Vertex(new Vector3f(), null);
        @SuppressWarnings("unchecked")
        Pair<Bone, Float>[] noWeights = new Pair[0];
        vertex.setBinding(new BoneBinding(noWeights, BoneBindingFunc.IDENTITY, null));
        Material material = new Material(new Texture[0], null);
        Mesh mesh = new Mesh(new Vertex[]{vertex, vertex, vertex}, new Vector3f(), new Transform(),
                new Material[]{material}, null);
        Model model = new Model(new Vertex[]{vertex}, new Mesh[]{mesh}, new Bone[]{root}, skeleton,
                new MaterialSet(List.of(), List.of(material)), MeshMode.TRIANGLES, null, null);
        return new Fixture(model, vertex, mesh, material);
    }

    private record Fixture(Model model, Vertex vertex, Mesh mesh, Material material) {}
}
