package lib.kasuga.rendering.models.uml.typo.miku_miku_dance.chunk.vertex;

import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.StreamLoader;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.chunk.LoopChunk;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.vertex.PmxVertex;
import lib.kasuga.structure.Pair;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class VertexSetChunk extends LoopChunk {

    private final List<PmxVertex> cache;

    public VertexSetChunk() {
        super(new ArrayList<>(), Map.of());
        this.cache = new ArrayList<>();
    }

    @Override
    public void beforeProcess(int position, ByteBuffer buffer, SerialContext context) {
        cache.clear();
    }

    public void setVertexChunk(VertexChunk vertexChunk) {
        loaders.clear();
        loaders.add(vertexChunk);
    }

    @Override
    public void onGetData(int position, ByteBuffer buffer, StreamLoader loader, Object data, SerialContext context) {
        cache.add((PmxVertex) data);
    }

    @Override
    public Object process(List<Pair<StreamLoader, Object>> loadedData, ByteBuffer buffer, SerialContext context) {
        ArrayList<PmxVertex> holder = new ArrayList<>(cache.size());
        holder.addAll(cache);
        cache.clear();
        return holder;
    }

    @Override
    public int getLoopTime(int position, ByteBuffer buffer, SerialContext context) {
        return buffer.getInt();
    }
}
