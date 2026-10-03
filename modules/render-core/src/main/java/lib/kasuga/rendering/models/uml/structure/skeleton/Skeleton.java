package lib.kasuga.rendering.models.uml.structure.skeleton;

import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.skeleton.data.AnchorData;
import lib.kasuga.rendering.models.uml.structure.skeleton.data.BoneData;
import lib.kasuga.rendering.models.uml.structure.skeleton.data.SkeletonData;
import lib.kasuga.rendering.models.uml.structure.basic.data.BoneBindingData;
import lib.kasuga.structure.Pair;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Queue;

@Getter
public class Skeleton {

    private final Bone[] bones;

    private final Bone root;

    private final SkeletonData data;

    private final Anchor[] anchors;

    private final HashMap<String, Bone> boneMap;
    private final HashMap<String, Anchor> anchorMap;
    private final HashMap<Bone, Pair<Transform, Transform>> boneTransforms;

    @NonNull
    @Setter
    private Transform transform;

    public Skeleton(Bone[] bones, Bone root,
                    Anchor[] anchors,
                    SkeletonData data, @NonNull Transform transform) {
        this.bones = bones;
        this.root = root;
        this.data = data;
        this.transform = transform;
        this.anchors = anchors;
        this.boneMap = new HashMap<>();
        this.anchorMap = new HashMap<>();
        this.boneTransforms = new HashMap<>();
        int i = 0;
        for (Bone bone : bones) {
            bone.setIndex(i);
            i++;
            if (bone.getName().isEmpty()) continue;
            boneMap.put(bone.getName(), bone);
        }
        for (Anchor anchor : anchors) {
            anchorMap.put(anchor.getName(), anchor);
        }
        getInverse();
    }

    public Anchor getAnchor(String name) {
        return anchorMap.get(name);
    }

    public void getInverse() {
        if (!boneTransforms.isEmpty()) return;
        Queue<Pair<Bone, Transform>> queue = new LinkedList<>();
        // Fix (Bug #2): 根骨骼的绑定变换应包含其自身局部变换。
        // 原代码: queue.add(Pair.of(root, getTransform()));
        // 问题: 仅使用 skeleton.transform 作为根骨骼的父变换，忽略了 root.getTransform()，
        //       导致根骨骼的位移/旋转在整个骨骼层级中被丢失。
        // 修复: 根骨骼的绑定绝对变换 = skeleton.transform * root.getTransform()。
        Transform rootWorldTransform = getTransform().copy().mul(root.getTransform());
        queue.add(Pair.of(root, rootWorldTransform));
        recursiveUpdate(queue);
    }

    private void recursiveUpdate(Queue<Pair<Bone, Transform>> queue) {
        while (!queue.isEmpty()) {
            Pair<Bone, Transform> pair = queue.poll();
            Bone bone = pair.getFirst();
            Transform parentTransform = pair.getSecond();
            if (bone == root) {
                boneTransforms.put(bone, Pair.of(parentTransform.copy(), parentTransform.copy().invert()));
            }
            if (bone.getChildren() == null) continue;
            for (Bone child : bone.getChildren()) {
                if (child == null) continue;
                Transform transform = parentTransform.copy().mul(child.getTransform());
                boneTransforms.put(child, Pair.of(transform, transform.invert()));
                queue.add(Pair.of(child, transform));
            }
        }
    }

    public Transform getBindingAbsolute(Bone bone) {
        if (!boneTransforms.containsKey(bone)) {
            throw new IllegalArgumentException("Bone not found in skeleton: " + bone.getName());
        }
        return boneTransforms.get(bone).getFirst();
    }

    public Transform getBindingInverse(Bone bone) {
        if (!boneTransforms.containsKey(bone)) {
            throw new IllegalArgumentException("Bone not found in skeleton: " + bone.getName());
        }
        return boneTransforms.get(bone).getSecond();
    }
}
