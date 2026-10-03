package lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.bone;

public class PmxBoneFlags {

    public final boolean
            tailPositionIsBone, isRotatable, isTranslatable,
            isVisible, isEnabled, isIKBone,
            inheritParentRotation, inheritParentTranslation, isAxisFixed,
            useLocalAxis, calcMorphBeforePhysics, hasForeignParentBone;

    public PmxBoneFlags(boolean[] flagSet1, boolean[] flagSet2) {
        tailPositionIsBone = flagSet1[0];
        isRotatable = flagSet1[1];
        isTranslatable = flagSet1[2];
        isVisible = flagSet1[3];
        isEnabled = flagSet1[4];
        isIKBone = flagSet1[5];
        inheritParentRotation = flagSet2[0];
        inheritParentTranslation = flagSet2[1];
        isAxisFixed = flagSet2[2];
        useLocalAxis = flagSet2[3];
        calcMorphBeforePhysics = flagSet2[4];
        hasForeignParentBone = flagSet2[5];
    }

    public PmxBoneFlags() {
        tailPositionIsBone = false;
        isRotatable = true;
        isTranslatable = true;
        isVisible = true;
        isEnabled = true;
        isIKBone = false;
        inheritParentRotation = false;
        inheritParentTranslation = false;
        isAxisFixed = false;
        useLocalAxis = false;
        calcMorphBeforePhysics = false;
        hasForeignParentBone = false;
    }
}
