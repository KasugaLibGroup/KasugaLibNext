package lib.kasuga.rendering.models.uml.typo.miku_miku_dance.chunk.bone;

import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.StreamLoader;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.basic.Padding;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.chunk.LoopChunk;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.bone.PmxBone;
import lib.kasuga.structure.Pair;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class BoneSetChunk extends LoopChunk {

    private final ArrayList<PmxBone> cache;

    public BoneSetChunk() {
        super(new ArrayList<>(), Map.of());
        cache = new ArrayList<>();
    }

    @Override
    public int getLoopTime(int position, ByteBuffer buffer, SerialContext context) {
        return buffer.getInt();
    }

    @Override
    public void beforeProcess(int position, ByteBuffer buffer, SerialContext context) {
        cache.clear();
    }

    public void setBoneChunk(BoneChunk boneChunk) {
        loaders.clear();
        loaders.add(boneChunk);
    }

    @Override
    public void onGetData(int position, ByteBuffer buffer, StreamLoader loader, Object data, SerialContext context) {
        if (data instanceof PmxBone bone) cache.add(bone);
    }

    @Override
    public Object process(List<Pair<StreamLoader, Object>> loadedData, ByteBuffer buffer, SerialContext context) {
        ArrayList<PmxBone> bones = new ArrayList<>(cache);
        cache.clear();
        return bones;
    }
}
