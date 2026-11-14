package lib.kasuga.test.registration.data_driven.source;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

final class SourceTestSupport {
    private static final int CENTRAL_DIRECTORY_SIGNATURE = 0x02014b50;
    private static final int END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06054b50;

    private SourceTestSupport() {
    }

    static void markUnixEntry(Path zip, String entryName, int mode) throws IOException {
        byte[] bytes = Files.readAllBytes(zip);
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        byte[] expectedName = entryName.getBytes(StandardCharsets.UTF_8);

        for (int offset = 0; offset <= bytes.length - 46; offset++) {
            if (buffer.getInt(offset) != CENTRAL_DIRECTORY_SIGNATURE) continue;
            int nameLength = Short.toUnsignedInt(buffer.getShort(offset + 28));
            int extraLength = Short.toUnsignedInt(buffer.getShort(offset + 30));
            int commentLength = Short.toUnsignedInt(buffer.getShort(offset + 32));
            int nameOffset = offset + 46;
            if (nameOffset + nameLength > bytes.length) continue;

            boolean matches = true;
            for (int i = 0; i < expectedName.length; i++) {
                if (i >= nameLength || bytes[nameOffset + i] != expectedName[i]) {
                    matches = false;
                    break;
                }
            }
            if (!matches || nameLength != expectedName.length) continue;

            buffer.putShort(offset + 4, (short) (3 << 8));
            buffer.putInt(offset + 38, mode << 16);
            Files.write(zip, bytes);
            return;
        }

        throw new IllegalArgumentException("No ZIP entry named " + entryName
                + " (central directory length calculation uses " + buffer.limit() + " + "
                + (bytes.length - 46) + " bytes)");
    }

    static void duplicateOnlyEntry(Path zip) throws IOException {
        byte[] bytes = Files.readAllBytes(zip);
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int endOffset = findEndOffset(buffer);
        int centralSize = Short.toUnsignedInt(buffer.getShort(endOffset + 12));
        int centralOffset = buffer.getInt(endOffset + 16);

        byte[] localAndFirstCentral = Arrays.copyOf(bytes, centralOffset + centralSize);
        byte[] duplicatedCentral = Arrays.copyOfRange(bytes, centralOffset, centralOffset + centralSize);
        byte[] endRecord = Arrays.copyOfRange(bytes, endOffset, endOffset + 22);
        ByteBuffer outputEnd = ByteBuffer.wrap(endRecord).order(ByteOrder.LITTLE_ENDIAN);
        outputEnd.putShort(8, (short) 2);
        outputEnd.putShort(10, (short) 2);
        outputEnd.putInt(12, centralSize * 2);
        outputEnd.putInt(16, centralOffset);

        Path duplicate = zip.resolveSibling(zip.getFileName() + ".duplicate");
        Files.write(duplicate, concat(localAndFirstCentral, duplicatedCentral, endRecord));
        Files.move(duplicate, zip, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static int findEndOffset(ByteBuffer buffer) {
        for (int offset = buffer.limit() - 22; offset >= 0; offset--) {
            if (buffer.getInt(offset) == END_OF_CENTRAL_DIRECTORY_SIGNATURE) {
                return offset;
            }
        }
        throw new IllegalArgumentException("No ZIP end record");
    }

    private static byte[] concat(byte[]... arrays) {
        int length = Arrays.stream(arrays).mapToInt(array -> array.length).sum();
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, result, offset, array.length);
            offset += array.length;
        }
        return result;
    }
}
