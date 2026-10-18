package lib.kasuga.rendering.models.uml.typo.miku_miku_dance.chunk.mesh;

import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.StreamLoader;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.chunk.LoopChunk;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.mesh.PmxMesh;
import lib.kasuga.structure.Pair;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MeshSetChunk extends LoopChunk {

    private final List<PmxMesh> cache;

    public MeshSetChunk() {
        super(new ArrayList<>(), Map.of());
        this.cache = new java.util.ArrayList<>();
    }

    public void setMeshChunk(MeshChunk meshChunk) {
        loaders.clear();
        loaders.add(meshChunk);
    }

    @Override
    public void beforeProcess(int position, ByteBuffer buffer, SerialContext context) {
        cache.clear();
    }

    @Override
    public int getLoopTime(int position, ByteBuffer buffer, SerialContext context) {
        return buffer.getInt() / 3;
    }

    @Override
    public void onGetData(int position, ByteBuffer buffer, StreamLoader loader, Object data, SerialContext context) {
        if (data instanceof PmxMesh mesh) {
            cache.add(mesh);
        }
    }

    @Override
    public Object process(List<Pair<StreamLoader, Object>> loadedData, ByteBuffer buffer, SerialContext context) {
        ArrayList<PmxMesh> meshSet = new ArrayList<>(cache.size());
        meshSet.addAll(cache);
        cache.clear();
        return meshSet;
    }
}
