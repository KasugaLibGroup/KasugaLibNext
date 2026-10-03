package lib.kasuga.rendering.models.uml.typo.miku_miku_dance.chunk.mesh;

import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.StreamLoader;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.chunk.Chunk;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.PMXLoader;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.mesh.PmxMesh;
import lib.kasuga.structure.Pair;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

public class MeshChunk extends Chunk {

    public MeshChunk(PMXLoader loader) {
        super(List.of(
                loader.vertexIndexLoader(),
                loader.vertexIndexLoader(),
                loader.vertexIndexLoader()
        ), Map.of());
    }

    @Override
    public Object process(List<Pair<StreamLoader, Object>> loadedData, ByteBuffer buffer, SerialContext context) {
        return new PmxMesh(
                (Number) loadedData.get(0).getSecond(),
                (Number) loadedData.get(1).getSecond(),
                (Number) loadedData.get(2).getSecond()
        );
    }
}
