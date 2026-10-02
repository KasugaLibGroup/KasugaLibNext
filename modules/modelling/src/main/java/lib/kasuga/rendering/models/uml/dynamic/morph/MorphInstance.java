package lib.kasuga.rendering.models.uml.dynamic.morph;

import lib.kasuga.rendering.models.uml.dynamic.morph.blender.BlenderType;
import lib.kasuga.rendering.models.uml.backend.ElementChanges;
import lib.kasuga.rendering.models.uml.dynamic.morph.holder.GroupMorph;
import lib.kasuga.rendering.models.uml.dynamic.morph.holder.IMorphHolder;
import lib.kasuga.rendering.models.uml.dynamic.morph.results.*;
import lib.kasuga.rendering.models.uml.dynamic.morph.types.*;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.material.Sprite;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.*;

/**
 * Dynamic morph state — tracks activation factors, dirty elements via {@link BitSet},
 * and produces {@link lib.kasuga.rendering.models.uml.dynamic.morph.results.IMorphResult} objects for the render pipeline.
 */
@Getter
public class MorphInstance<IdType> {

    protected final Morph<IdType> morph;

    /** Per-morph-type ordinals → activation value / factor, sized to {@link Morph#morphTypeCount()}. */
    protected final float[] activeValues;
    protected final float[] activeFactors;

    /** Fallback for MorphTypes never registered via {@code Morph.addMorph} — keeps arbitrary-group compatibility. */
    protected final Map<MorphType<?, ?, IdType>, Float[]> unindexedFactors;

    // ── Model element arrays (direct references, no copy) ──────────
    protected final Vertex[] vertices;
    protected final Mesh[] meshes;
    protected final Bone[] bones;
    protected final Material[] materials;

    // ── Forward index (element → array index) ─────────────────────
    protected final Map<Mesh, Integer> meshIndex;
    protected final Map<Material, Integer> materialIndex;

    // ── Dirty tracking (BitSet) ────────────────────────────────────
    protected final BitSet dirtyVertices;
    protected final BitSet dirtyMeshes;
    protected final BitSet dirtyBones;
    protected final BitSet dirtyMaterials;

    // ── Last-updated (BitSet) ─────────────────────────────────────
    protected final BitSet lastUpdatedVertices;
    protected final BitSet lastUpdatedBones;
    protected final BitSet lastUpdatedMeshes;
    protected final BitSet lastUpdatedMaterials;
    protected final ElementChanges vertexChanges, materialChanges;

    // ── Morph results (index arrays for the hot per-vertex render path) ──
    protected final VertexResult[] vertexResults;
    protected final Set<MorphType<Vertex, ?, IdType>>[] vertexMorphsByIndex;
    protected final Map<Bone, BoneResult> boneResults;
    protected final Map<Mesh, MeshResult> meshResults;
    protected final Map<Material, MaterialResult> materialResults;
    protected final Map<Bone, Transform> transformCache;

    @Setter
    protected byte resultMappingType;

    public MorphInstance(Morph<IdType> morph) {
        this.morph = morph;
        this.activeValues = new float[morph.morphTypeCount()];
        this.activeFactors = new float[morph.morphTypeCount()];
        this.unindexedFactors = new HashMap<>();
        this.transformCache = new HashMap<>();

        Model model = morph.getModel();
        this.vertices = model.getVertices();
        this.meshes = model.getMeshes();
        this.bones = model.getBones();
        this.materials = model.getMaterialSet().getMaterials();

        int vc = vertices.length,
                mc = meshes.length,
                bc = bones.length,
                mac = materials.length;

        this.meshIndex = new HashMap<>(mc);
        for (int i = 0; i < mc; i++) meshIndex.put(meshes[i], i);
        this.materialIndex = new HashMap<>(mac);
        for (int i = 0; i < mac; i++) materialIndex.put(materials[i], i);

        this.dirtyVertices = new BitSet(vc);
        this.dirtyMeshes = new BitSet(mc);
        this.dirtyBones = new BitSet(bc);
        this.dirtyMaterials = new BitSet(mac);

        this.lastUpdatedVertices = new BitSet(vc);
        this.lastUpdatedBones = new BitSet(bc);
        this.lastUpdatedMeshes = new BitSet(mc);
        this.lastUpdatedMaterials = new BitSet(mac);
        this.vertexChanges = new ElementChanges(vc);
        this.materialChanges = new ElementChanges(mac);

        this.vertexResults = new VertexResult[vc];
        this.boneResults = new HashMap<>();
        this.meshResults = new HashMap<>();
        this.materialResults = new HashMap<>();

        @SuppressWarnings("unchecked")
        Set<MorphType<Vertex, ?, IdType>>[] vmb = new Set[vc];
        for (int i = 0; i < vc; i++) {
            vertices[i].setIndex(i);
            vmb[i] = morph.getVertexMorphs().get(vertices[i]);
        }
        this.vertexMorphsByIndex = vmb;

        this.resultMappingType = (byte) 0b1011;
    }

    // ── Activation ────────────────────────────────────────────────

    public boolean activateMorph(IdType id, float value) { return activateMorph(id, value, 1F); }

    @SuppressWarnings("unchecked")
    public boolean activateMorph(IdType id, float value, float factor) {
        GroupMorph<IdType> group = morph.getGroup(id);
        if (group != null) return activateGroup(group, Math.clamp(value, 0f, 1f), factor);

        List<MorphType<?, ?, IdType>> definitions = morph.getMorphs(id);
        if (definitions.isEmpty()) return false;
        float v = Math.clamp(value, 0f, 1f);
        boolean applied = false;
        for (MorphType<?, ?, IdType> mt : definitions) {
            if (mt instanceof FlipMorph<?> flip) {
                applied |= applySingle((MorphType<?, ?, IdType>) flip.getReferenceMorph(), 1f - v, factor);
            } else {
                applied |= applySingle(mt, v, factor);
            }
        }
        return applied;
    }

    @SuppressWarnings("unchecked")
    private boolean activateGroup(GroupMorph<IdType> group, float value, float factor) {
        boolean any = false;
        for (IMorphHolder<?, IdType> h : group.getSubHolders()) {
            Float f = group.getFactors().get(h);
            if (f == null) f = 1F;
            if (h.isGroup()) any |= activateGroup((GroupMorph<IdType>) h, value, f * factor);
            else {
                MorphType<?, ?, IdType> proto = (MorphType<?, ?, IdType>) h.getMorphPrototype();
                if (proto != null) any |= applySingle(proto, value, f * factor);
            }
        }
        return any;
    }

    public void deactivateMorph(IdType id) { activateMorph(id, 0F, 1F); }
    public void deactivateMorph(IdType id, float factor) { activateMorph(id, 0F, factor); }

    private static final float MORPH_EPSILON = 1e-4f;

    /**
     * Writes one MorphType's activation value/factor, skipping the dirty-marking (and thus the whole
     * per-vertex recompute) when nothing changed since the last frame — the dominant cost for looping
     * clips whose morph values plateau. Registered types go through flat arrays; unregistered ones
     * (arbitrary group prototypes) fall back to a small map with identical semantics.
     */
    private boolean applySingle(MorphType<?, ?, IdType> mt, float value, float factor) {
        int idx = mt.getMorphTypeIndex();
        if (idx >= 0 && idx < activeValues.length) {
            if (Math.abs(activeValues[idx] - value) < MORPH_EPSILON
                    && Math.abs(activeFactors[idx] - factor) < MORPH_EPSILON) {
                return true;
            }
            activeValues[idx] = value;
            activeFactors[idx] = factor;
        } else {
            Float[] cur = unindexedFactors.get(mt);
            if (cur != null && Math.abs(cur[0] - value) < MORPH_EPSILON
                    && Math.abs(cur[1] - factor) < MORPH_EPSILON) {
                return true;
            }
            unindexedFactors.put(mt, new Float[]{value, factor});
        }
        markDirty(mt.getOriginal());
        return true;
    }

    /** Activation value for a MorphType (0 = inactive), array-backed on the hot path. */
    private float factorValue(MorphType<?, ?, IdType> mt) {
        int idx = mt.getMorphTypeIndex();
        if (idx >= 0 && idx < activeValues.length) return activeValues[idx];
        Float[] fallback = unindexedFactors.get(mt);
        return fallback == null ? 0f : fallback[0];
    }

    /** Activation factor for a MorphType (multiplier on the value). */
    private float factorFactor(MorphType<?, ?, IdType> mt) {
        int idx = mt.getMorphTypeIndex();
        if (idx >= 0 && idx < activeFactors.length) return activeFactors[idx];
        Float[] fallback = unindexedFactors.get(mt);
        return fallback == null ? 1f : fallback[1];
    }

    private void markDirty(Object original) {
        if (original instanceof Vertex v) {
            int idx = v.getIndex();
            if (idx >= 0) dirtyVertices.set(idx);
        } else if (original instanceof Mesh m) {
            Integer idx = meshIndex.get(m);
            if (idx != null) dirtyMeshes.set(idx);
        } else if (original instanceof Bone b) {
            dirtyBones.set(b.getIndex());
        } else if (original instanceof Material mat) {
            Integer idx = materialIndex.get(mat);
            if (idx != null) dirtyMaterials.set(idx);
        }
    }

    // ── Result accessors ──────────────────────────────────────────

    @Nullable public VertexResult getVertexResult(Vertex v) { return resultAt(v); }
    @Nullable public BoneResult getBoneResult(Bone b) { return boneResults.get(b); }
    @Nullable public MeshResult getMeshResult(Mesh m) { return meshResults.get(m); }
    @Nullable public MaterialResult getMaterialResult(Material m) { return materialResults.get(m); }
    @Nullable public Transform getCachedTransform(Bone b) { return transformCache.get(b); }

    public void clearTransformCache() {
        transformCache.clear();
    }

    public boolean isVertexDirtyAt(int index) { return dirtyVertices.get(index); }
    public boolean isBoneDirtyAt(int index)    { return dirtyBones.get(index); }
    public boolean isMeshDirtyAt(int index)    { return dirtyMeshes.get(index); }
    public boolean isMaterialDirtyAt(int index){ return dirtyMaterials.get(index); }

    // ── Dirty queries ─────────────────────────────────────────────

    public boolean isDirty() {
        return !dirtyVertices.isEmpty() || !dirtyBones.isEmpty()
                || !dirtyMeshes.isEmpty() || !dirtyMaterials.isEmpty();
    }

    public boolean shouldUpdate() {
        return isDirty() || !lastUpdatedBones.isEmpty();
    }

    public void clearLastChanged() {
        lastUpdatedBones.clear();
        lastUpdatedVertices.clear();
        lastUpdatedMeshes.clear();
        lastUpdatedMaterials.clear();
    }

    public boolean isVerticesDirty()  { return !dirtyVertices.isEmpty(); }
    public boolean isBonesDirty()     { return !dirtyBones.isEmpty(); }
    public boolean isMeshesDirty()    { return !dirtyMeshes.isEmpty(); }
    public boolean isMaterialsDirty() { return !dirtyMaterials.isEmpty(); }

    // ── Update ────────────────────────────────────────────────────

    public void update() {
        if (!isDirty()) return;

        // Plateau results must survive updates to unrelated elements.

        if (isVerticesDirty()) {
            for (int i = dirtyVertices.nextSetBit(0); i >= 0; i = dirtyVertices.nextSetBit(i + 1)) {
                Vertex v = vertices[i];
                Set<MorphType<Vertex, ?, IdType>> set = vertexMorphsByIndex[i];
                if (set == null || set.isEmpty()) continue;
                VertexResult r = vertexResults[i];
                if (r == null) {
                    r = new VertexResult(v);
                    vertexResults[i] = r;
                }
                r.reset();
                morphVertex(v, set, r);
                lastUpdatedVertices.set(i);
            }
        }

        if (isBonesDirty()) {
            for (int i = dirtyBones.nextSetBit(0); i >= 0; i = dirtyBones.nextSetBit(i + 1)) {
                Bone b = bones[i];
                Set<MorphType<Bone, ?, IdType>> set = morph.getBoneMorphs().get(b);
                if (set == null || set.isEmpty()) continue;
                BoneResult r = boneResults.computeIfAbsent(b, BoneResult::new);
                r.reset();
                morphBone(b, set, r);
                lastUpdatedBones.set(i);
            }
        }

        if (isMeshesDirty()) {
            for (int i = dirtyMeshes.nextSetBit(0); i >= 0; i = dirtyMeshes.nextSetBit(i + 1))
                lastUpdatedMeshes.set(i);
        }

        if (isMaterialsDirty()) {
            for (int i = dirtyMaterials.nextSetBit(0); i >= 0; i = dirtyMaterials.nextSetBit(i + 1)) {
                Material m = materials[i];
                Set<MorphType<Material, ?, IdType>> set = morph.getMaterialMorphs().get(m);
                if (set == null || set.isEmpty()) continue;
                MaterialResult r = materialResults.computeIfAbsent(m, MaterialResult::new);
                r.reset();
                morphMaterial(m, set, r);
                lastUpdatedMaterials.set(i);
            }
        }

        vertexChanges.mark(dirtyVertices);
        materialChanges.mark(dirtyMaterials);
        clearAllDirtyMarks();
    }

    // ── Element morphing → populates Result objects ───────────────

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void morphVertex(Vertex v, Set<MorphType<Vertex, ?, IdType>> set, VertexResult r) {
        for (MorphType<Vertex, ?, IdType> mt : set) {
            float value = factorValue(mt);
            if (value <= 0f) continue;
            float factor = factorFactor(mt);

            if (mt.getClass() == VertexPosMorph.class) {
                VertexPosMorph<?> position = (VertexPosMorph<?>) mt;
                Vector3f origin = position.getOriginal().getPosition();
                Vector3f target = position.getTargetPosition();
                float weight = value * factor;
                r.addPosition((target.x - origin.x) * weight, (target.y - origin.y) * weight,
                        (target.z - origin.z) * weight);
            } else if (mt instanceof VertexPosMorph) {
                // Keep overridden morph() behavior for third-party subclasses.
                r.addPosition((Vector3f) mt.morph(v, value, factor));
            } else if (mt instanceof VertexNormalMorph<?> vnm) {
                r.addNormal(vnm.getMesh(), (Vector3f) mt.morph(v, value, factor));
            } else if (mt instanceof VertexUvMorph<?> uvm) {
                r.addUv(uvm.getMesh(), uvm.getMaterial(), (Vector2f) mt.morph(v, value, factor));
            } else if (mt instanceof VertexTangentMorph) {
                r.addTangent((Vector4f) mt.morph(v, value, factor));
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void morphBone(Bone b, Set<MorphType<Bone, ?, IdType>> set, BoneResult r) {
        for (MorphType<Bone, ?, IdType> mt : set) {
            float value = factorValue(mt);
            if (value <= 0f) continue;
            float factor = factorFactor(mt);
            if (mt instanceof BoneTransformMorph) {
                r.setTransform((Transform) mt.morph(b, value, factor));
            }
        }
        transformCache.put(b, r.getTransform());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void morphMaterial(Material m, Set<MorphType<Material, ?, IdType>> set, MaterialResult r) {
        for (MorphType<Material, ?, IdType> mt : set) {
            float value = factorValue(mt);
            if (value <= 0f) continue;
            float factor = factorFactor(mt);

            if (mt instanceof MaterialColorMorph<?> colorMorph) {
                r.addColor((Vector4f) mt.morph(m, value, factor), colorMorph.getBlendMode());
            } else if (mt instanceof MaterialSpecularMorph) {
                r.addSpecular((Vector4f) mt.morph(m, value, factor), BlendMode.ADD);
            } else if (mt instanceof MaterialAmbientMorph) {
                r.addAmbient((Vector4f) mt.morph(m, value, factor), BlendMode.ADD);
            } else if (mt instanceof MaterialEdgeColorMorph) {
                r.addEdgeColor((Vector4f) mt.morph(m, value, factor), BlendMode.ADD);
            } else if (mt instanceof SpriteFrameMorph<?> sm) {
                r.setSpriteFrame(sm.getSpriteSetIndex(), (Integer) mt.morph(m, value, factor));
            } else if (mt instanceof MaterialFrameMorph) {
                r.setMaterialFrame((Integer) mt.morph(m, value, factor));
            }
        }
    }

    // ── Cleanup ───────────────────────────────────────────────────

    public void clearAllDirtyMarks() {
        dirtyVertices.clear(); dirtyBones.clear();
        dirtyMeshes.clear(); dirtyMaterials.clear();
    }

    public void clearResults() {
        for (VertexResult r : vertexResults) if (r != null) r.reset();
        boneResults.values().forEach(BoneResult::reset);
        meshResults.values().forEach(MeshResult::reset);
        materialResults.values().forEach(MaterialResult::reset);
        transformCache.clear();
        BitSet all = new BitSet();
        all.set(0, vertices.length);
        vertexChanges.mark(all);
        all.clear();
        all.set(0, materials.length);
        materialChanges.mark(all);
        lastUpdatedBones.set(0, bones.length);
    }

    public void resetLastUpdated() {
        lastUpdatedVertices.clear(); lastUpdatedBones.clear();
        lastUpdatedMeshes.clear(); lastUpdatedMaterials.clear();
    }

    // ── Blend-on-read output ports ────────────────────────────────

    /** Result for a model vertex, or null. Array-backed (no hashing) on the per-frame render path. */
    @Nullable
    private VertexResult resultAt(Vertex vertex) {
        int idx = vertex.getIndex();
        return idx >= 0 && idx < vertexResults.length ? vertexResults[idx] : null;
    }

    /** Morphed position = original + delta; returns dest for chaining. */
    public Vector3f getVertexPos(Vertex vertex, Vector3f dest) {
        VertexResult r = resultAt(vertex);
        return (r != null && r.getPosition() != null)
                ? dest.set(vertex.getPosition()).add(r.getPosition())
                : dest.set(vertex.getPosition());
    }

    /** Morphed normal per mesh = normalize(original + delta). */
    public Vector3f getVertexNormal(Vertex vertex, Mesh mesh, Vector3f dest) {
        VertexResult r = resultAt(vertex);
        if (r != null) {
            Vector3f delta = r.getNormals().get(mesh);
            if (delta != null) {
                dest.set(vertex.getNormal(mesh)).add(delta);
                if (dest.lengthSquared() > 0f) dest.normalize();
                return dest;
            }
        }
        return dest.set(vertex.getNormal(mesh));
    }

    /** Morphed UV per mesh+material = original + delta. */
    public Vector2f getVertexUv(Vertex vertex, Mesh mesh, Material material, Vector2f dest) {
        VertexResult r = resultAt(vertex);
        if (r != null) {
            Map<Material, Vector2f> matMap = r.getUvs().get(mesh);
            if (matMap != null) {
                Vector2f delta = matMap.get(material);
                if (delta != null) {
                    Vector2f uv = vertex.getUV(mesh, material);
                    return dest.set(uv != null ? uv : new Vector2f()).add(delta);
                }
            }
        }
        Vector2f uv = vertex.getUV(mesh, material);
        return dest.set(uv != null ? uv : new Vector2f());
    }

    /** Tangent delta, or null. */
    @Nullable
    public void getVertexTangent(Vertex vertex, Vector4f dest) {
        VertexResult r = resultAt(vertex);
        if (r == null || r.getTangent() == null) return;
        dest.set(r.getTangent());
    }

    /** Morphed bone transform, or original. */
    public void getBoneTransform(Bone bone, Transform dest) {
        BoneResult r = boneResults.get(bone);
        dest.mul(bone.getTransform());
        if (r == null || r.getTransform() == null) return;
        dest.mul(r.getTransform());
    }

    /** Material color: MULTIPLY → original × delta, ADD → original + delta. Falls back to white. */
    public void getMaterialColor(Material material, Sprite sprite, Vector4f dest) {
        MaterialResult r = materialResults.get(material);
        if (r != null && (r.getColorMultiply() != null || r.getColorAdd() != null)) {
            dest.set(sprite.color);
            if (r.getColorMultiply() != null) dest.mul(r.getColorMultiply());
            if (r.getColorAdd() != null) dest.add(r.getColorAdd());
            return;
        }
        dest.set(sprite.color);
    }

    /** Material specular: ADD → default + delta, MULTIPLY → default × delta. Falls back to default. */
    public void getMaterialSpecular(Material m, Sprite sprite, Vector4f dest) {
        MaterialResult r = materialResults.get(m);
        if (r != null && r.getSpecular() != null) {
            if (r.getSpecularBlendMode() == BlendMode.MULTIPLY) {
                dest.set(sprite.specular).mul(r.getSpecular());
            } else {
                dest.set(sprite.specular).add(r.getSpecular());
            }
            return;
        }
        dest.set(sprite.specular);
    }

    /** Material ambient: ADD → default + delta, MULTIPLY → default × delta. Falls back to default. */
    public void getMaterialAmbient(Material m, Sprite sprite, Vector4f dest) {
        MaterialResult r = materialResults.get(m);

        if (r != null && r.getAmbient() != null) {
            if (r.getAmbientBlendMode() == BlendMode.MULTIPLY) {
                dest.set(sprite.ambient).mul(r.getAmbient());
            } else {
                dest.set(sprite.ambient).add(r.getAmbient());
            }
            return;
        }
        dest.set(sprite.ambient);
    }

    public void getMaterialUv(Material m, Vector2f uv0, Vector2f uv1, Vector2f uv2, Vector2f uv3) {

    }

//    /** Material edge color: ADD → default + delta, MULTIPLY → default × delta. Falls back to default. */
//    public Vector4f getMaterialEdgeColor(Material m, Sprite sprite, Vector4f dest) {
//        MaterialResult r = materialResults.get(m);
//        if (r != null && r.getEdgeColor() != null) {
//            if (r.getEdgeColorBlendMode() == BlendMode.MULTIPLY)
//                return dest.set().mul(r.getEdgeColor());
//            else
//                return dest.set(defaultEdgeColor).add(r.getEdgeColor());
//        }
//        return dest.set(defaultEdgeColor);
//    }
//
    @NotNull
    public Integer getMaterialSpriteFrame(Material m) {
        MaterialResult r = materialResults.get(m);
        if (r != null && r.getSpriteFrame() != null) return r.getSpriteFrame();
        Set<MorphType<Material, ?, IdType>> definitions = morph.getMaterialMorphs().get(m);
        return definitions != null && definitions.stream().anyMatch(mt -> mt instanceof SpriteFrameMorph) ? 0 : -1;
    }

    @NotNull
    public Integer getMaterialFrameIndex(Material m) {
        MaterialResult r = materialResults.get(m);
        if (r != null && r.getMaterialFrame() != null) return r.getMaterialFrame();
        Set<MorphType<Material, ?, IdType>> definitions = morph.getMaterialMorphs().get(m);
        return definitions != null && definitions.stream().anyMatch(mt -> mt instanceof MaterialFrameMorph) ? 0 : -1;
    }

    public Sprite getSprite(Material material) {
        int idx = getMaterialFrameIndex(material);
        if (idx == -1) return null;
        return material.getSprites().get(idx)
                .getSprite(getMaterialSpriteFrame(material));
    }

    // ── Blender stub ──────────────────────────────────────────────

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Nullable
    public <T> BlenderType<T> getBlender(T original, Set<? extends MorphType<T, ?, IdType>> set,
                                         List<IMorphResult<T>> results) { return null; }
}
