package lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.chunk;

import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.StreamLoader;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.basic.Padding;
import lib.kasuga.structure.Pair;
import lombok.Getter;
import lombok.Setter;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

public class LoopChunk extends Chunk {

    public LoopChunk(List<StreamLoader> loaders, Map<Padding, Integer> autoPaddings) {
        super(loaders, autoPaddings);
    }

    @Override
    public Object load(ByteBuffer buffer, SerialContext context) {
        final int startPosition = buffer.position();
        int position = startPosition;
        beforeProcess(startPosition, buffer, context);
        int loopTime = getLoopTime(position, buffer, context);
        position = buffer.position();
        int i = 0;
        while (true) {
            if (loopTime >= 0 && i >= loopTime) break;
            if (shouldBreak(loopTime, i, position, buffer, context)) break;
            if (shouldContinue(loopTime, i, position, buffer, context)) continue;
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
            i++;
        }
        Object result = process(loadedData, buffer, context);
        loadedData.clear();
        return result;
    }

    public int getLoopTime(int position, ByteBuffer buffer, SerialContext context) {
        return 0;
    }

    public boolean shouldBreak(int allLoopTimes, int loopTimes, int position, ByteBuffer buffer, SerialContext context) {
        return false;
    }

    public boolean shouldContinue(int allLoopTimes, int loopTimes, int position, ByteBuffer buffer, SerialContext context) {
        return false;
    }
}
