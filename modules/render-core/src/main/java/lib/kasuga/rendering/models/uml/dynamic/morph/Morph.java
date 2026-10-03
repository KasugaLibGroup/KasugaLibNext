package lib.kasuga.rendering.models.uml.dynamic.morph;

import lib.kasuga.rendering.models.uml.dynamic.morph.holder.GroupMorph;
import lib.kasuga.rendering.models.uml.dynamic.morph.types.*;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Static morph registry — holds {@link MorphType} definitions and
 * {@link GroupMorph} holders. No dynamic state; pure data structure.
 */
@Getter
public class Morph<IdType> {

    protected final Model model;

    protected final Map<IdType, MorphType<?, ?, IdType>> morphMap;
    protected final Map<IdType, List<MorphType<?, ?, IdType>>> morphsById;
    protected final Map<IdType, GroupMorph<IdType>> groupMorphs;
    protected final Map<Vertex, Set<MorphType<Vertex, ?, IdType>>> vertexMorphs;
    protected final Map<Mesh, Set<MorphType<Mesh, ?, IdType>>> meshMorphs;
    protected final Map<Bone, Set<MorphType<Bone, ?, IdType>>> boneMorphs;
    protected final Map<Material, Set<MorphType<Material, ?, IdType>>> materialMorphs;

    /** Number of {@link MorphType} instances registered so far; their ordinals feed MorphInstance arrays. */
    protected int morphTypeCounter;

    public Morph(Model model) {
        this.model = model;
        this.morphMap = new HashMap<>();
        this.morphsById = new HashMap<>();
        this.groupMorphs = new HashMap<>();
        this.vertexMorphs = new HashMap<>();
        this.meshMorphs = new HashMap<>();
        this.boneMorphs = new HashMap<>();
        this.materialMorphs = new HashMap<>();
    }

    @SuppressWarnings("unchecked")
    public void addMorph(IdType id, MorphType<?, ?, IdType> morph) {
        if (morph.getMorphTypeIndex() < 0) {
            morph.setMorphTypeIndex(morphTypeCounter++);
        }
        morphMap.putIfAbsent(id, morph);
        morphsById.computeIfAbsent(id, ignored -> new ArrayList<>()).add(morph);
        Object original = morph instanceof FlipMorph<?> flip
                ? flip.getReferenceMorph().getOriginal()
                : morph.getOriginal();
        registerByOriginal(original, morph);
    }

    @SuppressWarnings("unchecked")
    private void registerByOriginal(Object original, MorphType<?, ?, IdType> morph) {
        if (original instanceof Vertex v)
            vertexMorphs.computeIfAbsent(v, k -> new HashSet<>())
                    .add((MorphType<Vertex, ?, IdType>) morph);
        else if (original instanceof Mesh m)
            meshMorphs.computeIfAbsent(m, k -> new HashSet<>())
                    .add((MorphType<Mesh, ?, IdType>) morph);
        else if (original instanceof Bone b)
            boneMorphs.computeIfAbsent(b, k -> new HashSet<>())
                    .add((MorphType<Bone, ?, IdType>) morph);
        else if (original instanceof Material mat)
            materialMorphs.computeIfAbsent(mat, k -> new HashSet<>())
                    .add((MorphType<Material, ?, IdType>) morph);
    }

    public void addGroup(IdType id, GroupMorph<IdType> group) { groupMorphs.put(id, group); }

    /** Total registered {@link MorphType} ordinals; sizes the MorphInstance factor/value arrays. */
    public int morphTypeCount() { return morphTypeCounter; }

    @Nullable public MorphType<?, ?, IdType> getMorph(IdType id) { return morphMap.get(id); }
    public List<MorphType<?, ?, IdType>> getMorphs(IdType id) {
        return morphsById.getOrDefault(id, List.of());
    }
    @Nullable public GroupMorph<IdType> getGroup(IdType id)    { return groupMorphs.get(id); }
}
