package lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.bone;

import lib.kasuga.rendering.models.uml.structure.skeleton.data.BoneData;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

public class PmxBone implements BoneData {

    public final String localBoneName;

    public final String engBoneName;

    public final Vector3f position;

    public final Number parentBoneIndex;

    public final int transformPriority;

    public final PmxBoneFlags flags;

    public final Object tailObject;

    public final @Nullable ParentBoneInherit inherit;

    public final @Nullable Vector3f fixedAxis;

    public final @Nullable LocalAxis localAxis;

    public final Number foreignParentBoneIndex;

    public final @Nullable PmxIKBone ik;

    public PmxBone(String localBoneName,
                   String engBoneName,
                   Vector3f position,
                   Number parentBoneIndex,
                   int transformPriority,
                   PmxBoneFlags flags,
                   Object tailObject,
                   @Nullable ParentBoneInherit inherit,
                   @Nullable Vector3f fixedAxis,
                   @Nullable LocalAxis localAxis,
                   Number foreignParentBoneIndex,
                   @Nullable PmxIKBone ik) {
        this.localBoneName = localBoneName;
        this.engBoneName = engBoneName;
        this.position = position;
        this.parentBoneIndex = parentBoneIndex;
        this.transformPriority = transformPriority;
        this.flags = flags;
        this.tailObject = tailObject;
        this.inherit = inherit;
        this.fixedAxis = fixedAxis;
        this.localAxis = localAxis;
        this.foreignParentBoneIndex = foreignParentBoneIndex;
        this.ik = ik;
    }
}
