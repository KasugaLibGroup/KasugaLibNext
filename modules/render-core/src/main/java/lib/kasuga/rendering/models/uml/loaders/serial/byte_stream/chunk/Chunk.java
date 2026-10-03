package lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.chunk;

import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.StreamLoader;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.basic.DataLoader;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.basic.Padding;
import lib.kasuga.structure.Pair;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Chunk implements StreamLoader {

    @Getter
    protected final List<StreamLoader> loaders;

    @Getter
    protected final Map<Padding, Integer> autoPaddings;

    protected final List<Pair<StreamLoader, Object>> loadedData;

    public Chunk(List<StreamLoader> loaders, Map<Padding, Integer> autoPaddings) {
        this.loaders = loaders;
        this.autoPaddings = autoPaddings;
        this.loadedData = new ArrayList<>(loaders.size());
    }

    @Override
    public Object load(ByteBuffer buffer, SerialContext context) {
        final int startPosition = buffer.position();
        int position = startPosition;
        beforeProcess(startPosition, buffer, context);
        for (StreamLoader loader : loaders) {
            if (loader instanceof Padding padding) {
                if (autoPaddings.containsKey(padding)) {
                    int pad = startPosition + autoPaddings.get(padding) - position;
                    padding.setBytes(pad);
                }
            }
            Object data = loader.load(buffer, context);
            onGetData(position, buffer, loader, data, context);
            loadedData.add(Pair.of(loader, data));
            position = buffer.position();
        }
        @Nullable Object result = process(loadedData, buffer, context);
        loadedData.clear();
        return result;
    }

    public void beforeProcess(int position, ByteBuffer buffer, SerialContext context) {}

    public void onGetData(int position, ByteBuffer buffer, StreamLoader loader, Object data, SerialContext context) {}

    public Object process(List<Pair<StreamLoader, Object>> loadedData, ByteBuffer buffer, SerialContext context) {
        return null;
    }
}
