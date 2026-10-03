package lib.kasuga.rendering.models.uml.typo.miku_miku_dance.chunk.header;

import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.StreamLoader;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.basic.BasicLoaders;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.chunk.Chunk;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.header.PmxGlobalInfo;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.PmxFormatException;
import lib.kasuga.structure.Pair;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class HeaderInfoChunk extends Chunk {
    private int dataSize = 8;

    public HeaderInfoChunk() {
        super(List.of(
                BasicLoaders.BYTE,
                BasicLoaders.BYTE,
                BasicLoaders.BYTE,
                BasicLoaders.BYTE,
                BasicLoaders.BYTE,
                BasicLoaders.BYTE,
                BasicLoaders.BYTE,
                BasicLoaders.BYTE
        ), Map.of());
    }

    public void setDataSize(int dataSize) {
        if (dataSize < 8) throw new IllegalArgumentException("PMX global data must contain at least 8 bytes");
        this.dataSize = dataSize;
    }

    @Override
    public Object load(ByteBuffer buffer, SerialContext context) {
        if (buffer.remaining() < dataSize) {
            throw new IllegalStateException("Truncated PMX global data: expected " + dataSize
                    + " bytes, got " + buffer.remaining());
        }
        byte[] values = new byte[dataSize];
        buffer.get(values);
        if (values[0] != 0 && values[0] != 1) {
            throw new PmxFormatException("Unsupported PMX text encoding: " + Byte.toUnsignedInt(values[0]));
        }
        if (values[1] < 0 || values[1] > 4) {
            throw new PmxFormatException("Invalid PMX additional vec4 count: " + Byte.toUnsignedInt(values[1]));
        }
        for (int i = 2; i < 8; i++) {
            if (values[i] != 1 && values[i] != 2 && values[i] != 4) {
                throw new PmxFormatException("Invalid PMX index size at global byte " + i + ": "
                        + Byte.toUnsignedInt(values[i]));
            }
        }
        Charset encoding = values[0] == 0 ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_8;
        return new PmxGlobalInfo(encoding, values[1], values[2], values[4], values[3],
                values[5], values[6], values[7]);
    }

    @Override
    public Object process(List<Pair<StreamLoader, Object>> loadedData, ByteBuffer buffer, SerialContext context) {
        Charset encoding;
        if ((byte) loadedData.getFirst().getSecond() == 0) {
            encoding = StandardCharsets.UTF_16LE;
        } else {
            encoding = StandardCharsets.UTF_8;
        }
        PmxGlobalInfo globalInfo = new PmxGlobalInfo(
                encoding,
                (byte) loadedData.get(1).getSecond(),
                (byte) loadedData.get(2).getSecond(),
                (byte) loadedData.get(4).getSecond(),
                (byte) loadedData.get(3).getSecond(),
                (byte) loadedData.get(5).getSecond(),
                (byte) loadedData.get(6).getSecond(),
                (byte) loadedData.get(7).getSecond()
        );
        return globalInfo;
    }
}
