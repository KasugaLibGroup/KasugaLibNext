package lib.kasuga.rendering.models.uml.typo.miku_miku_dance.chunk.vertex;

import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.StreamLoader;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.basic.BasicLoaders;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.chunk.Chunk;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.PMXLoader;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.chunk.bone.BoneBindingChunk;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.bone.PmxBoneBinding;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.vertex.PmxVertex;
import lib.kasuga.structure.Pair;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class VertexChunk extends Chunk {

    private final BoneBindingChunk boneBinding;
    private PmxBoneBinding binding;
    private final int vec4Count;

    public VertexChunk(PMXLoader loader) {
        super(new ArrayList<>(), Map.of());
        vec4Count = loader.getHeader().getInfo().additionalVec4Count;
        List<StreamLoader> loaders = getLoaders();
        loaders.add(BasicLoaders.VEC3F);
        loaders.add(BasicLoaders.VEC3F);
        loaders.add(BasicLoaders.VEC2F);
        for (int i = 0; i < vec4Count; i++) {
            loaders.add(BasicLoaders.VEC4F);
        }
        loaders.add(BasicLoaders.BYTE);
        boneBinding = new BoneBindingChunk(loader);
        loaders.add(boneBinding);
        loaders.add(BasicLoaders.FLOAT);
        binding = null;
    }

    @Override
    public void onGetData(int position, ByteBuffer buffer, StreamLoader loader, Object data, SerialContext context) {
        if (loader == BasicLoaders.BYTE) {
            boneBinding.setType((byte) data);
        } else if (loader == boneBinding) {
            binding = (PmxBoneBinding) data;
        }
    }

    @Override
    public Object process(List<Pair<StreamLoader, Object>> loadedData, ByteBuffer buffer, SerialContext context) {
        Vector4f[] additionalVec4 = new Vector4f[vec4Count];
        for (int i = 0; i < vec4Count; i++) {
            additionalVec4[i] = (Vector4f) loadedData.get(3 + i).getSecond();
        }
        int offset = 3 + vec4Count;
        return new PmxVertex(
                (Vector3f) loadedData.get(0).getSecond(),
                (Vector3f) loadedData.get(1).getSecond(),
                (Vector2f) loadedData.get(2).getSecond(),
                additionalVec4,
                (byte) loadedData.get(offset).getSecond(),
                binding,
                (float) loadedData.get(offset + 2).getSecond()
        );
    }
}
