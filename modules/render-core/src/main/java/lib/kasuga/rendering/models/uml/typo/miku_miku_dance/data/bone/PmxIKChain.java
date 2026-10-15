package lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.bone;

import org.jetbrains.annotations.Nullable;

public class PmxIKChain {

    public final Number boneIndex;
    public final boolean useRotationLimit;
    public final @Nullable IKLimitation limit;

    public PmxIKChain(Number boneIndex, boolean useRotationLimit, @Nullable IKLimitation limit) {
        this.boneIndex = boneIndex;
        this.useRotationLimit = useRotationLimit;
        this.limit = limit;
    }
}
