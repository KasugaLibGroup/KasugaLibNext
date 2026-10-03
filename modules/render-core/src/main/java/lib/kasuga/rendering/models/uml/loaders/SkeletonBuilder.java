package lib.kasuga.rendering.models.uml.loaders;

import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.basic.BoneBinding;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.basic.data.BoneBindingData;
import lib.kasuga.rendering.models.uml.structure.skeleton.Anchor;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.structure.skeleton.Skeleton;
import lib.kasuga.rendering.models.uml.structure.skeleton.data.AnchorData;
import lib.kasuga.rendering.models.uml.structure.skeleton.data.BoneData;
import lib.kasuga.rendering.models.uml.structure.skeleton.data.SkeletonData;
import lombok.NonNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.BiFunction;

public class SkeletonBuilder {

    public record BoneRecord(@NonNull String name, @NonNull Transform transform, @Nullable String parentName, @Nullable BoneData boneData) {}

    public record AnchorRecord(@NonNull String name, @NonNull Transform transform, @NonNull Collection<String> parentBoneNames, @Nullable AnchorData anchorData) {}

    public record VertexRecord(@NonNull Vertex vertex, @NonNull Collection<String> parentBoneNames) {}

    private final HashMap<String, BoneRecord> boneRecords;

    private final HashMap<String, AnchorRecord> anchorRecords;

    private final HashSet<VertexRecord> vertexRecords;

    public SkeletonBuilder() {
        boneRecords = new HashMap<>();
        anchorRecords = new HashMap<>();
        vertexRecords = new HashSet<>();
    }

    public void addBone(@NonNull String name, @NonNull Transform transform, @Nullable String parentName, @Nullable BoneData boneData) {
        boneRecords.put(name, new BoneRecord(name, transform, parentName, boneData));
    }

    public void addAnchor(@NonNull String name, @NonNull Transform transform, @NonNull Collection<String> parentBoneNames, @Nullable AnchorData anchorData) {
        anchorRecords.put(name, new AnchorRecord(name, transform, parentBoneNames, anchorData));
    }

    public void addVertex(@NonNull Vertex vertex, @NonNull Collection<String> parentBoneNames) {
        vertexRecords.add(new VertexRecord(vertex, parentBoneNames));
    }

    public void clear() {
        boneRecords.clear();
        anchorRecords.clear();
        vertexRecords.clear();
    }

    public Skeleton build(SkeletonData skeletonData, @Nullable Transform rootTransform,
                                      @NonNull BiFunction<AnchorRecord, List<Bone>, BoneBinding> anchorBindingFunction,
                                      @NonNull BiFunction<VertexRecord, List<Bone>, BoneBinding> vertexBindingFunction) {
        BoneRecord rootRecord = null;
        for (BoneRecord record : boneRecords.values()) {
            if (record.parentName == null) {
                rootRecord = record;
                break;
            }
        }
        if (rootRecord == null) {
            throw new IllegalStateException("No root bone found");
        }
        Bone rootBone = new Bone(rootRecord.name, rootRecord.transform, rootRecord.boneData);
        Queue<Bone> queue = new LinkedList<>();
        queue.add(rootBone);
        HashMap<String, Bone> boneMap = new HashMap<>();
        HashMap<String, List<Bone>> childrenMap = new HashMap<>();
        boneMap.put(rootBone.getName(), rootBone);
        while (!queue.isEmpty()) {
            Bone bone = queue.poll();
            for (BoneRecord record : boneRecords.values()) {
                if (bone.getName().equals(record.parentName)) {
                    Bone childBone = new Bone(record.name, record.transform, record.boneData);
                    childrenMap.computeIfAbsent(bone.getName(), k -> new ArrayList<>()).add(childBone);
                    boneMap.put(childBone.getName(), childBone);
                    childBone.setParent(bone);
                    queue.add(childBone);
                }
            }
        }
        for (Bone bone : boneMap.values()) {
            bone.setChildren(childrenMap.getOrDefault(bone.getName(), new ArrayList<>()).toArray(new Bone[0]));
        }
        HashMap<String, Anchor> anchorMap = new HashMap<>();
        for (AnchorRecord record : anchorRecords.values()) {
            List<Bone> parentBones = new ArrayList<>();
            for (String pBN : record.parentBoneNames) {
                Bone parentBone = boneMap.get(pBN);
                if (parentBone != null) {
                    parentBones.add(parentBone);
                }
            }
            Anchor anchor = new Anchor(record.name, anchorBindingFunction.apply(record, parentBones), record.transform, record.anchorData);
            anchorMap.put(anchor.getName(), anchor);
        }
        for (VertexRecord record : vertexRecords) {
            List<Bone> parentBones = new ArrayList<>();
            for (String pBN : record.parentBoneNames) {
                Bone parentBone = boneMap.get(pBN);
                if (parentBone != null) {
                    parentBones.add(parentBone);
                }
            }
            record.vertex.setBinding((BoneBinding) vertexBindingFunction.apply(record, parentBones));
        }
        @SuppressWarnings("unchecked")
        Skeleton skeleton = new Skeleton(
                boneMap.values().toArray(new Bone[0]),
                rootBone,
                anchorMap.values().toArray(new Anchor[0]),
                skeletonData,
                rootTransform == null ? new Transform() : rootTransform
        );
        return skeleton;
    }
}
