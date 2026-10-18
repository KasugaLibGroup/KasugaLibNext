package lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.bone;

import lib.kasuga.rendering.models.uml.math.binding.BoneBindingFunc;
import lib.kasuga.rendering.models.uml.math.binding.SDEFData;
import lib.kasuga.rendering.models.uml.structure.basic.BoneBinding;
import lib.kasuga.rendering.models.uml.structure.basic.data.BoneBindingData;
import lib.kasuga.rendering.models.uml.structure.basic.data.vertex.SDEFBoneBindingData;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.PMXLoader;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public class PmxBoneBinding implements BoneBindingData, SDEFBoneBindingData {

    public static enum BindingType {
        BDEF, SDEF, QDEF, NONE
    }

    public static final SDEFData EMPTY = new SDEFData(
            new Vector3f(), new Vector3f(), new Vector3f()
    );

    public final Map<Number, Float> boneWeights;

    public final BindingType type;

    public final SDEFData data;

    public PmxBoneBinding(BindingType type, Map<Number, Float> boneWeights) {
        this(type, boneWeights, null);
    }

    public PmxBoneBinding(BindingType type, Map<Number, Float> boneWeights, @Nullable SDEFData sdefData) {
        this.type = type;
        this.boneWeights = boneWeights;
        this.data = sdefData;
    }

    public BoneBinding binding(PMXLoader loader) {
        return null;
    }

    public static PmxBoneBinding BDEF(List<Number> boneIndices, List<Float> boneWeights) {
        return BDEF_OR_QDEF(boneIndices, boneWeights, false);
    }

    public static PmxBoneBinding QDEF(List<Number> boneIndices, List<Float> boneWeights) {
        return BDEF_OR_QDEF(boneIndices, boneWeights, true);
    }

    public static PmxBoneBinding SDEF(Number boneIndex1, Number boneIndex2, float weight, Vector3f c, Vector3f r0, Vector3f r1) {
        Map<Number, Float> weights = new LinkedHashMap<>();
        weights.put(boneIndex1, weight);
        weights.put(boneIndex2, 1f - weight);
        return new PmxBoneBinding(BindingType.SDEF, weights, new SDEFData(c, r0, r1));
    }

    public static PmxBoneBinding BDEF_OR_QDEF(List<Number> boneIndices, List<Float> boneWeights, boolean isQDEF) {
        if (boneIndices.size() != boneWeights.size()) {
            if (boneIndices.size() == boneWeights.size() + 1) {
                float sumWeights = 0f;
                for (Float weight : boneWeights) {
                    sumWeights += weight;
                }
                if (sumWeights > 1f) {
                    throw new IllegalArgumentException("Bone weights sum cannot be greater than 1");
                }
                boneWeights.add(1f - sumWeights);
            } else {
                throw new IllegalArgumentException("Bone indices and weights must have the same size");
            }
        }
        float sumWeights = 0f;
        for (Float weight : boneWeights) {
            sumWeights += weight;
        }
        if (sumWeights != 1f) {
            for (int i = 0; i < boneWeights.size(); i++) {
                boneWeights.set(i, boneWeights.get(i) / sumWeights);
            }
        }
        Map<Number, Float> map = new java.util.HashMap<>();
        for (int i = 0; i < boneIndices.size(); i++) {
            map.put(boneIndices.get(i), boneWeights.get(i));
        }
        return new PmxBoneBinding(isQDEF ? BindingType.QDEF : BindingType.BDEF, map);
    }

    public BoneBindingFunc toBindingFunc() {
        return switch (type) {
            case BDEF -> BoneBindingFunc.BDEF;
            case SDEF -> BoneBindingFunc.SDEF;
            case QDEF -> BoneBindingFunc.QDEF;
            case NONE -> BoneBindingFunc.IDENTITY;
        };
    }

    @Override
    public boolean equals(Object obj) {
        return this == obj || (
                obj instanceof PmxBoneBinding binding &&
                type == binding.type &&
                boneWeights.equals(binding.boneWeights) &&
                Objects.equals(data, binding.data)
        );
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, data);
    }

    @Override
    public SDEFData getSDEFData() {
        return data != null ? data : EMPTY;
    }
}
