package lib.kasuga.rendering.models.uml.typo.miku_miku_dance.chunk.material;

import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.StreamLoader;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.chunk.LoopChunk;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.material.PmxMaterial;
import lib.kasuga.structure.Pair;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MaterialSetChunk extends LoopChunk {

    private final ArrayList<PmxMaterial> cache;

    public MaterialSetChunk() {
        super(new ArrayList<>(), Map.of());
        cache = new ArrayList<>();
    }

    public void setMaterialChunk(MaterialChunk chunk) {
        loaders.clear();
        loaders.add(chunk);
    }

    @Override
    public int getLoopTime(int position, ByteBuffer buffer, SerialContext context) {
        return buffer.getInt();
    }

    @Override
    public void onGetData(int position, ByteBuffer buffer, StreamLoader loader, Object data, SerialContext context) {
        if (loader instanceof MaterialChunk) {
            cache.add((PmxMaterial) data);
        }
    }

    @Override
    public Object process(List<Pair<StreamLoader, Object>> loadedData, ByteBuffer buffer, SerialContext context) {
        ArrayList<PmxMaterial> materials = new ArrayList<>(cache);
        cache.clear();
        return materials;
    }
}
