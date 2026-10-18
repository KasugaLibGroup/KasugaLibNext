package lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.bone;

public class PmxIKBone {

    public final Number boneIndex;

    public final int CCD_Count;

    public final float boneRotationLimit;

    public final int chainCount;

    public final PmxIKChain[] chains;

    public PmxIKBone(Number boneIndex, int CCD_Count, float boneRotationLimit, PmxIKChain[] chain) {
        this.boneIndex = boneIndex;
        this.CCD_Count = CCD_Count;
        this.boneRotationLimit = boneRotationLimit;
        this.chainCount = chain.length;
        this.chains = chain;
    }
}
