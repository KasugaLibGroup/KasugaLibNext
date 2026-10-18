package lib.kasuga.rendering.models.uml.typo.miku_miku_dance.chunk.bone;

import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.StreamLoader;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.basic.BasicLoaders;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.basic.TextLoader;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.chunk.Chunk;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.PMXLoader;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.bone.*;
import org.joml.Vector3f;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

public class BoneChunk extends Chunk {

    private final PMXLoader loader;

    public BoneChunk(PMXLoader loader) {
        super(List.of(), Map.of());
        this.loader = loader;
    }

    @Override
    public Object load(ByteBuffer buffer, SerialContext context) {
        TextLoader textLoader = loader.getTextLoader();
        String localName = textLoader.process(buffer, textLoader.length(buffer), context);
        String engName = textLoader.process(buffer, textLoader.length(buffer), context);
        Vector3f position = BasicLoaders.VEC3F.load(buffer, context);
        StreamLoader boneIndexLoader = loader.boneIndexLoader();
        Number parentIndex = (Number) boneIndexLoader.load(buffer, context);
        int transformPriority = BasicLoaders.INT.load(buffer, context);
        boolean[] flagSet1 = BasicLoaders.FLAGS.load(buffer, context);
        boolean[] flagSet2 = BasicLoaders.FLAGS.load(buffer, context);
        PmxBoneFlags flags = new PmxBoneFlags(flagSet1, flagSet2);
        Object tailObject = null;
        if (flags.tailPositionIsBone) {
            tailObject = boneIndexLoader.load(buffer, context);
        } else {
            tailObject = BasicLoaders.VEC3F.load(buffer, context);
        }
        ParentBoneInherit inherit = null;
        if (flags.inheritParentRotation || flags.inheritParentTranslation) {
            inherit = new ParentBoneInherit(
                    (Number) boneIndexLoader.load(buffer, context),
                    BasicLoaders.FLOAT.load(buffer, context)
            );
        }
        Vector3f fixedAxis = null;
        if (flags.isAxisFixed) {
            fixedAxis = BasicLoaders.VEC3F.load(buffer, context);
        }
        LocalAxis localAxis = null;
        if (flags.useLocalAxis) {
            localAxis = new LocalAxis(
                    BasicLoaders.VEC3F.load(buffer, context),
                    BasicLoaders.VEC3F.load(buffer, context)
            );
        }
        Number externalParentBone = -1;
        if (flags.hasForeignParentBone) {
            externalParentBone = BasicLoaders.INT.load(buffer, context);
        }
        PmxIKBone ik = null;
        if (flags.isIKBone) {
            Number ikTargetBone = (Number) boneIndexLoader.load(buffer, context);
            int ikLoopCount = BasicLoaders.INT.load(buffer, context);
            float ikLimitAngle = BasicLoaders.FLOAT.load(buffer, context);
            int ikLinkCount = BasicLoaders.INT.load(buffer, context);
            PmxIKChain[] chains = new PmxIKChain[ikLinkCount];
            for (int i = 0; i < ikLinkCount; i++) {
                Number linkBoneIndex = (Number) boneIndexLoader.load(buffer, context);
                boolean hasLimit = BasicLoaders.BYTE.load(buffer, context) != 0;
                IKLimitation limit = null;
                if (hasLimit) {
                    limit = new IKLimitation(
                            BasicLoaders.VEC3F.load(buffer, context),
                            BasicLoaders.VEC3F.load(buffer, context)
                    );
                }
                chains[i] = new PmxIKChain(linkBoneIndex, hasLimit, limit);
            }
            ik = new PmxIKBone(ikTargetBone, ikLoopCount, ikLimitAngle, chains);
        }
        return new PmxBone(
                localName,
                engName,
                position,
                parentIndex,
                transformPriority,
                flags,
                tailObject,
                inherit,
                fixedAxis,
                localAxis,
                externalParentBone,
                ik
        );
    }
}
