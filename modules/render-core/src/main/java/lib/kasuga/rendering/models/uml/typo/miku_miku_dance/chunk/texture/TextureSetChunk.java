package lib.kasuga.rendering.models.uml.typo.miku_miku_dance.chunk.texture;

import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.StreamLoader;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.basic.TextLoader;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.chunk.LoopChunk;
import lib.kasuga.structure.Pair;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class TextureSetChunk extends LoopChunk {

    private final List<String> cache;

    public TextureSetChunk() {
        super(new ArrayList<>(), Map.of());
        this.cache = new ArrayList<>();
    }

    @Override
    public int getLoopTime(int position, ByteBuffer buffer, SerialContext context) {
        return buffer.getInt();
    }

    public void setTextLoader(TextLoader loader) {
        loaders.clear();
        loaders.add(loader);
    }

    @Override
    public void beforeProcess(int position, ByteBuffer buffer, SerialContext context) {
        cache.clear();
    }

    @Override
    public void onGetData(int position, ByteBuffer buffer, StreamLoader loader, Object data, SerialContext context) {
        String d = (String) data;
        d = d.replaceAll("\\\\", "/");
        if (d.startsWith("/")) {
            d = d.substring(1);
        }
        if (d.startsWith("./")) {
            d = d.substring(2);
        }
        cache.add(d);
    }

    @Override
    public Object process(List<Pair<StreamLoader, Object>> loadedData, ByteBuffer buffer, SerialContext context) {
        ArrayList<String> textures = new ArrayList<>(cache);
        cache.clear();
        return textures;
    }
}
