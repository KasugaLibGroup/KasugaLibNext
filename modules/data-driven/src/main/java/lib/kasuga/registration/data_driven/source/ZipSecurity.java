package lib.kasuga.registration.data_driven.source;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipException;

/**
 * Reads the small amount of Unix metadata that {@link java.util.zip.ZipFile} does not expose.
 */
final class ZipSecurity {
    private static final int END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06054b50;
    private static final int ZIP64_END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06064b50;
    private static final int ZIP64_LOCATOR_SIGNATURE = 0x07064b50;
    private static final int CENTRAL_DIRECTORY_SIGNATURE = 0x02014b50;
    private static final int END_OF_CENTRAL_DIRECTORY_SIZE = 22;
    private static final int ZIP64_END_OF_CENTRAL_DIRECTORY_SIZE = 56;
    private static final int ZIP64_LOCATOR_SIZE = 20;
    private static final int CENTRAL_DIRECTORY_HEADER_SIZE = 46;
    private static final long UINT32_MAX = 0xffff_ffffL;
    private static final int UINT16_MAX = 0xffff;
    private static final Charset IBM437 = Charset.forName("IBM437");

    private ZipSecurity() {
    }

    static List<CentralEntry> readCentralEntries(Path archive, int maxEntries) throws IOException {
        try (FileChannel channel = FileChannel.open(archive, StandardOpenOption.READ)) {
            long fileSize = channel.size();
            if (fileSize < END_OF_CENTRAL_DIRECTORY_SIZE) {
                throw new ZipException("ZIP is shorter than an end-of-central-directory record");
            }

            int tailLength = (int) Math.min(fileSize, END_OF_CENTRAL_DIRECTORY_SIZE + 0xffffL);
            long tailOffset = fileSize - tailLength;
            byte[] tail = readFully(channel, tailOffset, tailLength);
            int endRecordOffset = findEndRecord(tail);
            if (endRecordOffset < 0) {
                throw new ZipException("ZIP end-of-central-directory record is missing");
            }

            long endRecordPosition = tailOffset + endRecordOffset;
            long entryCount = unsignedShort(tail, endRecordOffset + 10);
            long centralSize = unsignedInt(tail, endRecordOffset + 12);
            long centralOffset = unsignedInt(tail, endRecordOffset + 16);

            if (entryCount == UINT16_MAX && maxEntries < UINT16_MAX) {
                throw new TooManyEntriesException(entryCount);
            }
            if (entryCount == UINT16_MAX
                    || centralSize == UINT32_MAX
                    || centralOffset == UINT32_MAX) {
                Zip64EndRecord zip64 = readZip64EndRecord(channel, endRecordPosition);
                entryCount = zip64.entryCount();
                centralSize = zip64.centralSize();
                centralOffset = zip64.centralOffset();
            }
            if (entryCount > maxEntries) {
                throw new TooManyEntriesException(entryCount);
            }
            if (entryCount > Integer.MAX_VALUE) {
                throw new ZipException("ZIP contains too many entries to inspect");
            }
            if (centralOffset < 0 || centralSize < 0
                    || centralOffset > endRecordPosition
                    || centralSize > endRecordPosition - centralOffset) {
                throw new ZipException("ZIP central directory is outside the archive");
            }

            List<CentralEntry> entries = new ArrayList<>((int) entryCount);
            long position = centralOffset;
            for (int index = 0; index < entryCount; index++) {
                byte[] header = readFully(channel, position, CENTRAL_DIRECTORY_HEADER_SIZE);
                if (leInt(header, 0) != CENTRAL_DIRECTORY_SIGNATURE) {
                    throw new ZipException("invalid ZIP central-directory record at " + position);
                }
                int versionMadeBy = unsignedShort(header, 4);
                int flags = unsignedShort(header, 8);
                int nameLength = unsignedShort(header, 28);
                int extraLength = unsignedShort(header, 30);
                int commentLength = unsignedShort(header, 32);
                long externalAttributes = unsignedInt(header, 38);
                byte[] nameBytes = readFully(channel, position + CENTRAL_DIRECTORY_HEADER_SIZE, nameLength);
                Charset charset = (flags & 0x0800) != 0 ? StandardCharsets.UTF_8 : IBM437;
                String name = new String(nameBytes, charset);

                int unixMode = (int) ((externalAttributes >>> 16) & 0xffff);
                int fileType = unixMode & 0170000;
                boolean symbolicLink = fileType == 0120000;
                boolean unixDirectory = fileType == 0040000;
                boolean specialFile = fileType != 0 && fileType != 0100000
                        && !symbolicLink && !unixDirectory;
                entries.add(new CentralEntry(
                        name,
                        symbolicLink,
                        unixDirectory,
                        specialFile,
                        versionMadeBy >>> 8
                ));

                position += CENTRAL_DIRECTORY_HEADER_SIZE
                        + (long) nameLength
                        + extraLength
                        + commentLength;
                if (position > centralOffset + centralSize) {
                    throw new ZipException("ZIP central-directory record exceeds its declared size");
                }
            }
            return List.copyOf(entries);
        }
    }

    private static int findEndRecord(byte[] tail) {
        for (int offset = tail.length - END_OF_CENTRAL_DIRECTORY_SIZE; offset >= 0; offset--) {
            if (leInt(tail, offset) != END_OF_CENTRAL_DIRECTORY_SIGNATURE) {
                continue;
            }
            int commentLength = unsignedShort(tail, offset + 20);
            if (offset + END_OF_CENTRAL_DIRECTORY_SIZE + commentLength == tail.length) {
                return offset;
            }
        }
        return -1;
    }

    private static Zip64EndRecord readZip64EndRecord(FileChannel channel, long endRecordPosition)
            throws IOException {
        long locatorPosition = endRecordPosition - ZIP64_LOCATOR_SIZE;
        if (locatorPosition < 0) {
            throw new ZipException("ZIP64 locator is missing");
        }
        byte[] locator = readFully(channel, locatorPosition, ZIP64_LOCATOR_SIZE);
        if (leInt(locator, 0) != ZIP64_LOCATOR_SIGNATURE) {
            throw new ZipException("ZIP64 locator is missing");
        }
        long zip64Position = leLong(locator, 8);
        if (zip64Position < 0) {
            throw new ZipException("ZIP64 end record offset is invalid");
        }
        byte[] record = readFully(channel, zip64Position, ZIP64_END_OF_CENTRAL_DIRECTORY_SIZE);
        if (leInt(record, 0) != ZIP64_END_OF_CENTRAL_DIRECTORY_SIGNATURE) {
            throw new ZipException("ZIP64 end record is missing");
        }
        return new Zip64EndRecord(
                leLong(record, 32),
                leLong(record, 40),
                leLong(record, 48)
        );
    }

    private static byte[] readFully(FileChannel channel, long position, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        int total = 0;
        while (total < length) {
            int read = channel.read(buffer, position + total);
            if (read < 0) {
                throw new EOFException("truncated ZIP metadata");
            }
            if (read == 0) {
                continue;
            }
            total += read;
        }
        return buffer.array();
    }

    private static int unsignedShort(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private static int leInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff)
                | ((bytes[offset + 1] & 0xff) << 8)
                | ((bytes[offset + 2] & 0xff) << 16)
                | ((bytes[offset + 3] & 0xff) << 24);
    }

    private static long unsignedInt(byte[] bytes, int offset) {
        return Integer.toUnsignedLong(leInt(bytes, offset));
    }

    private static long leLong(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, Long.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getLong();
    }

    record CentralEntry(
            String name,
            boolean symbolicLink,
            boolean unixDirectory,
            boolean specialFile,
            int hostSystem
    ) {
    }

    private record Zip64EndRecord(long entryCount, long centralSize, long centralOffset) {
    }

    static final class TooManyEntriesException extends IOException {
        private TooManyEntriesException(long entryCount) {
            super("ZIP contains " + entryCount + " entries");
        }
    }
}
