package lib.kasuga.rendering.models.uml.loaders.assembly;

import lib.kasuga.rendering.models.uml.dynamic.morph.types.*;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Copies morph definitions onto an assembly's elements. Null removes a morph whose target was hidden. */
@FunctionalInterface
public interface MorphRemapper {
    MorphType<?, ?, ?> remap(MorphType<?, ?, ?> source, ModelAssembly.Remap mapping);

    static MorphRemapper standard() { return Builtin.INSTANCE; }

    final class Builtin {
        private Builtin() {}
        static final MorphRemapper INSTANCE = Builtin::remap;

        private static MorphType<?, ?, ?> remap(MorphType<?, ?, ?> source, ModelAssembly.Remap map) {
            Object id = map.morphId(source.getIdentifier());
            if (source instanceof FlipMorph<?> flip) {
                MorphType<?, ?, ?> reference = map.morph(flip.getReferenceMorph());
                return reference == null ? null : new FlipMorph<>(id, reference);
            }
            Object original = source.getOriginal();
            Object target;
            if (original instanceof Vertex vertex) target = map.vertex(vertex);
            else if (original instanceof Mesh mesh) target = map.mesh(mesh);
            else if (original instanceof Bone bone) target = map.bone(bone);
            else if (original instanceof Material material) target = map.material(material);
            else throw new IllegalArgumentException("unsupported morph target: " + original);
            if (target == null) return null;
            if (source instanceof VertexPosMorph<?> morph) {
                return new VertexPosMorph<>((Vertex) target, id, new Vector3f(morph.getTargetPosition()));
            }
            if (source instanceof VertexNormalMorph<?> morph) {
                Mesh mesh = map.mesh(morph.getMesh());
                return mesh == null ? null : new VertexNormalMorph<>((Vertex) target, id, mesh, new Vector3f(morph.getTargetNormal()));
            }
            if (source instanceof VertexUvMorph<?> morph) {
                Mesh mesh = map.mesh(morph.getMesh());
                return mesh == null ? null : new VertexUvMorph<>((Vertex) target, id, mesh,
                        map.material(morph.getMaterial()), new Vector2f(morph.getTargetUv()));
            }
            if (source instanceof VertexTangentMorph<?> morph) {
                return new VertexTangentMorph<>((Vertex) target, id, new Vector4f(morph.getTargetTangent()));
            }
            if (source instanceof BoneTransformMorph<?> morph) {
                return new BoneTransformMorph<>((Bone) target, id, morph.getTargetTransform().copy());
            }
            if (source instanceof MaterialColorMorph<?> morph) {
                return new MaterialColorMorph<>((Material) target, id, new Vector4f(morph.getTargetColor()), morph.getBlendMode());
            }
            if (source instanceof MaterialSpecularMorph<?> morph) {
                return new MaterialSpecularMorph<>((Material) target, id, new Vector4f(morph.getTargetSpecular()));
            }
            if (source instanceof MaterialAmbientMorph<?> morph) {
                return new MaterialAmbientMorph<>((Material) target, id, new Vector4f(morph.getTargetAmbient()));
            }
            if (source instanceof MaterialEdgeColorMorph<?> morph) {
                return new MaterialEdgeColorMorph<>((Material) target, id, new Vector4f(morph.getTargetEdgeColor()));
            }
            if (source instanceof SpriteFrameMorph<?> morph) {
                return new SpriteFrameMorph<>((Material) target, id, morph.getSpriteSetIndex(), morph.getTargetFrame());
            }
            if (source instanceof MaterialFrameMorph<?> morph) {
                return new MaterialFrameMorph<>((Material) target, id, morph.getTargetSpriteSetIndex());
            }
            throw new IllegalArgumentException("unsupported morph type: " + source.getClass().getName()
                    + "; supply a MorphRemapper");
        }
    }
}
