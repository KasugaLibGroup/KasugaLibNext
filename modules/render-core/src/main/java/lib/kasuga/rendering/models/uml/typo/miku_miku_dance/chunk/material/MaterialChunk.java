package lib.kasuga.rendering.models.uml.typo.miku_miku_dance.chunk.material;

import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.StreamLoader;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.basic.BasicLoaders;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.basic.TextLoader;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.chunk.Chunk;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.PMXLoader;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.material.PmxMaterial;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.material.PmxMaterialFlags;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class MaterialChunk extends Chunk {

    private final PMXLoader loader;

    public MaterialChunk(PMXLoader loader) {
        super(List.of(), Map.of());
        this.loader = loader;
    }

    @Override
    public Object load(ByteBuffer buffer, SerialContext context) {
        Objects.requireNonNull(loader);
        TextLoader textLoader = loader.getTextLoader();
        String localName = textLoader.load(buffer, context);
        String englishName = textLoader.load(buffer, context);
        Vector4f diffuseColor = BasicLoaders.VEC4F.load(buffer, context);
        Vector3f specularColor = BasicLoaders.VEC3F.load(buffer, context);
        float specularStrength = BasicLoaders.FLOAT.load(buffer, context);
        Vector3f ambientColor = BasicLoaders.VEC3F.load(buffer, context);
        boolean[] flags = BasicLoaders.FLAGS.load(buffer, context);
        PmxMaterialFlags f = new PmxMaterialFlags(flags);
        Vector4f edgeColor = BasicLoaders.VEC4F.load(buffer, context);
        float edgeSize = BasicLoaders.FLOAT.load(buffer, context);
        StreamLoader textureLoader = loader.textureIndexLoader();
        Number textureIndex = (Number) textureLoader.load(buffer, context);
        Number envTextureIndex = (Number) textureLoader.load(buffer, context);
        byte blendingMode = BasicLoaders.BYTE.load(buffer, context);
        byte toonFlag = BasicLoaders.BYTE.load(buffer, context);
        Number toonTextureIndex;
        if (toonFlag == 0) {
            toonTextureIndex = (Number) textureLoader.load(buffer, context);
        } else {
            toonTextureIndex = (byte) BasicLoaders.BYTE.load(buffer, context);
        }
        String memo = textLoader.load(buffer, context);
        int faceCount = BasicLoaders.INT.load(buffer, context);
        return new PmxMaterial(
                localName, englishName, diffuseColor, specularColor, specularStrength, ambientColor,
                f, edgeColor, edgeSize, textureIndex.intValue(), envTextureIndex.intValue(),
                blendingMode, toonFlag, toonTextureIndex.intValue(), memo, faceCount
        );
    }
}
