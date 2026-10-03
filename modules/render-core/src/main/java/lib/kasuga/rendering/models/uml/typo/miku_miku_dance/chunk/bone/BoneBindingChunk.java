package lib.kasuga.rendering.models.uml.typo.miku_miku_dance.chunk.bone;

import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.StreamLoader;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.basic.BasicLoaders;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.chunk.Chunk;
import lib.kasuga.rendering.models.uml.math.binding.SDEFData;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.PMXLoader;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.bone.PmxBoneBinding;
import lib.kasuga.structure.Pair;
import lombok.Getter;
import lombok.Setter;
import org.joml.Vector3f;

import java.nio.ByteBuffer;
import java.util.*;

public class BoneBindingChunk extends Chunk {

    @Getter
    private final PMXLoader loader;

    @Getter
    @Setter
    private byte type;

    public BoneBindingChunk(PMXLoader loader) {
        super(new ArrayList<>(), Map.of());
        this.loader = loader;
    }

    @Override
    public Object load(ByteBuffer buffer, SerialContext context) {
        StreamLoader boneIndexLoader = loader.boneIndexLoader();
        return switch (type) {
            case 0 -> {
                Number bone = (Number) boneIndexLoader.load(buffer, context);
                yield new PmxBoneBinding(PmxBoneBinding.BindingType.BDEF, Map.of(bone, 1f));
            }
            case 1 -> {
                Number bone1 = (Number) boneIndexLoader.load(buffer, context);
                Number bone2 = (Number) boneIndexLoader.load(buffer, context);
                float weight = BasicLoaders.FLOAT.load(buffer, context);
                float weight2 = 1.0f - weight;
                Map<Number, Float> boneWeights;
                if (Objects.equals(bone1, bone2)) {
                    boneWeights = Map.of(bone1, 1.0f);
                } else {
                    boneWeights = Map.of(bone1, weight, bone2, weight2);
                }
                yield new PmxBoneBinding(PmxBoneBinding.BindingType.BDEF, boneWeights);
            }
            case 2, 4 -> loadBDEF4_OR_QDEF(buffer, context, type == 2);
            case 3 -> {
                Number bone1 = (Number) boneIndexLoader.load(buffer, context);
                Number bone2 = (Number) boneIndexLoader.load(buffer, context);
                float weight = BasicLoaders.FLOAT.load(buffer, context);
                Vector3f c = BasicLoaders.VEC3F.load(buffer, context);
                Vector3f r0 = BasicLoaders.VEC3F.load(buffer, context);
                Vector3f r1 = BasicLoaders.VEC3F.load(buffer, context);
                Map<Number, Float> boneWeights;
                if (Objects.equals(bone1, bone2)) {
                    boneWeights = Map.of(bone1, 1.0f);
                } else {
                    boneWeights = new LinkedHashMap<>();
                    boneWeights.put(bone1, weight);
                    boneWeights.put(bone2, 1.0f - weight);
                }
                yield new PmxBoneBinding(
                        Objects.equals(bone1, bone2) ?
                                PmxBoneBinding.BindingType.BDEF :
                                PmxBoneBinding.BindingType.SDEF,
                        boneWeights, new SDEFData(c, r0, r1));
            }
            default -> new PmxBoneBinding(PmxBoneBinding.BindingType.NONE, Map.of());
        };
    }

    public PmxBoneBinding loadBDEF4_OR_QDEF(ByteBuffer buffer, SerialContext context, boolean isBDEF) {
        Number bone1 = (Number) loader.boneIndexLoader().load(buffer, context);
        Number bone2 = (Number) loader.boneIndexLoader().load(buffer, context);
        Number bone3 = (Number) loader.boneIndexLoader().load(buffer, context);
        Number bone4 = (Number) loader.boneIndexLoader().load(buffer, context);
        float weight1 = BasicLoaders.FLOAT.load(buffer, context);
        float weight2 = BasicLoaders.FLOAT.load(buffer, context);
        float weight3 = BasicLoaders.FLOAT.load(buffer, context);
        float weight4 = BasicLoaders.FLOAT.load(buffer, context);
        float sum = weight1 + weight2 + weight3 + weight4;
        if (Float.compare(sum, 1.0f) != 0) {
            weight1 /= sum;
            weight2 /= sum;
            weight3 /= sum;
            weight4 /= sum;
        }
        Map<Number, Float> boneWeights = new HashMap<>();
        boneWeights.put(bone1, weight1);
        if (boneWeights.containsKey(bone2)) {
            boneWeights.put(bone2, boneWeights.get(bone2) + weight2);
        } else {
            boneWeights.put(bone2, weight2);
        }
        if (boneWeights.containsKey(bone3)) {
            boneWeights.put(bone3, boneWeights.get(bone3) + weight3);
        } else {
            boneWeights.put(bone3, weight3);
        }
        if (boneWeights.containsKey(bone4)) {
            boneWeights.put(bone4, boneWeights.get(bone4) + weight4);
        } else {
            boneWeights.put(bone4, weight4);
        }
        return new PmxBoneBinding(isBDEF ?
                PmxBoneBinding.BindingType.BDEF : PmxBoneBinding.BindingType.QDEF,
                boneWeights);
    }

    private void BDEF1() {
        getLoaders().clear();
        getLoaders().add(loader.boneIndexLoader());
    }

    private void BDEF2() {
        getLoaders().clear();
        getLoaders().add(loader.boneIndexLoader());
        getLoaders().add(loader.boneIndexLoader());
        getLoaders().add(BasicLoaders.FLOAT);
    }

    private void BDEF4_OR_QDEF() {
        getLoaders().clear();
        StreamLoader boneIndexLoader = loader.boneIndexLoader();
        getLoaders().add(boneIndexLoader);
        getLoaders().add(boneIndexLoader);
        getLoaders().add(boneIndexLoader);
        getLoaders().add(boneIndexLoader);
        getLoaders().add(BasicLoaders.FLOAT);
        getLoaders().add(BasicLoaders.FLOAT);
        getLoaders().add(BasicLoaders.FLOAT);
        getLoaders().add(BasicLoaders.FLOAT);
    }

    private void SDEF() {
        getLoaders().clear();
        getLoaders().add(loader.boneIndexLoader());
        getLoaders().add(loader.boneIndexLoader());
        getLoaders().add(BasicLoaders.FLOAT);
        getLoaders().add(BasicLoaders.VEC3F);
        getLoaders().add(BasicLoaders.VEC3F);
        getLoaders().add(BasicLoaders.VEC3F);
    }
}
