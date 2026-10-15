package lib.kasuga.rendering.models.uml.structure.skeleton;

import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.skeleton.data.BoneData;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import org.jetbrains.annotations.Nullable;

@Getter
public class Bone {

    private final String name;

    @NonNull
    @Setter
    private Transform transform;

    @Nullable
    private final BoneData boneData;

    @Nullable
    @Setter
    private Bone parent;

    @Nullable
    @Setter
    private Bone[] children;

    @Getter
    @Setter
    private int index;

    public Bone(String name, @NonNull Transform transform, @Nullable BoneData boneData) {
        this.name = name;
        this.boneData = boneData;
        this.transform = transform;
    }
}
