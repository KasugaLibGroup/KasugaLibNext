package lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.header;

import lib.kasuga.rendering.models.uml.loaders.serial.ContextData;
import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lombok.Getter;

import java.nio.charset.Charset;

@Getter
public class PmxGlobalInfo implements ContextData<PmxGlobalInfo> {

    public final Charset encoding;
    public final byte additionalVec4Count;
    public final byte vertexIndexSize;
    public final byte materialIndexSize;
    public final byte textureIndexSize;
    public final byte boneIndexSize;
    public final byte morphIndexSize;
    public final byte rigidBodyIndexSize;


    public PmxGlobalInfo(
            Charset encoding,
            byte additionalVec4Count,
            byte vertexIndexSize,
            byte materialIndexSize,
            byte textureIndexSize,
            byte boneIndexSize,
            byte morphIndexSize,
            byte rigidBodyIndexSize
    ) {
        this.encoding = encoding;
        this.additionalVec4Count = additionalVec4Count;
        this.vertexIndexSize = vertexIndexSize;
        this.materialIndexSize = materialIndexSize;
        this.textureIndexSize = textureIndexSize;
        this.boneIndexSize = boneIndexSize;
        this.morphIndexSize = morphIndexSize;
        this.rigidBodyIndexSize = rigidBodyIndexSize;
    }

    float version() {
        return 2.0f;
    }

    @Override
    public void build(SerialContext<PmxGlobalInfo> context) {

    }
}
