package lib.kasuga.rendering.models.uml.typo.miku_miku_dance.chunk.header;

import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.StreamLoader;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.basic.BasicLoaders;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.basic.TextLoader;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.chunk.Chunk;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.header.PmxGlobalInfo;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.header.PmxHeader;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.PmxFormatException;
import lib.kasuga.structure.Pair;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class HeaderChunk extends Chunk {

    private PmxGlobalInfo info;
    private final HeaderInfoChunk infoChunk;
    private int fieldIndex;

    public HeaderChunk(HeaderInfoChunk infoChunk) {
        super(new ArrayList<>(), Map.of());
        List<StreamLoader> loaders = getLoaders();
        loaders.addAll(List.of(
                BasicLoaders.BYTE,
                BasicLoaders.BYTE,
                BasicLoaders.BYTE,
                BasicLoaders.BYTE,
                BasicLoaders.FLOAT,
                BasicLoaders.BYTE,
                infoChunk,
                BasicLoaders.text(StandardCharsets.UTF_8),
                BasicLoaders.text(StandardCharsets.UTF_8),
                BasicLoaders.text(StandardCharsets.UTF_8),
                BasicLoaders.text(StandardCharsets.UTF_8)
                ));
        this.infoChunk = infoChunk;
    }

    @Override
    public void beforeProcess(int position, ByteBuffer buffer, SerialContext context) {
        fieldIndex = 0;
        info = null;
    }

    @Override
    public void onGetData(int position, ByteBuffer buffer, StreamLoader loader, Object data, SerialContext context) {
        if (fieldIndex == 5) {
            infoChunk.setDataSize(Byte.toUnsignedInt((byte) data));
        }
        if (loader instanceof HeaderInfoChunk) {
            info = (PmxGlobalInfo) data;
            List<StreamLoader> loaders = getLoaders();
            for (int i = loaders.size() - 4; i < loaders.size(); i++) {
                if (!(loaders.get(i) instanceof TextLoader textLoader)) continue;
                textLoader.setEncoding(info.getEncoding());
            }
        }
        fieldIndex++;
    }

    @Override
    public Object process(List<Pair<StreamLoader, Object>> loadedData, ByteBuffer buffer, SerialContext context) {
        if (info == null) throw new IllegalStateException("Global info not loaded");
        byte[] b = new byte[] {
                (byte) loadedData.get(0).getSecond(),
                (byte) loadedData.get(1).getSecond(),
                (byte) loadedData.get(2).getSecond(),
                (byte) loadedData.get(3).getSecond()
        };
        String sgn = new String(b, StandardCharsets.US_ASCII);
        float version = (float) loadedData.get(4).getSecond();
        if (!"PMX ".equals(sgn)) {
            throw new PmxFormatException("Invalid PMX signature: '" + sgn + "'");
        }
        if (Math.abs(version - 2.0f) > 1.0e-4f && Math.abs(version - 2.1f) > 1.0e-4f) {
            throw new PmxFormatException("Unsupported PMX version: " + version);
        }
        PmxHeader header = new PmxHeader(
                sgn,
                version,
                (byte) loadedData.get(5).getSecond(),
                info,
                ((String) loadedData.get(7).getSecond()),
                (String) loadedData.get(8).getSecond(),
                (String) loadedData.get(9).getSecond(),
                (String) loadedData.get(10).getSecond()
        );
        return header;
    }
}
