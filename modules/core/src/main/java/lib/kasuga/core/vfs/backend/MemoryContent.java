package lib.kasuga.core.vfs.backend;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;

/** Immutable owned chunks; readers retain a stable version while later writes replace the node. */
final class MemoryContent {
    private final List<byte[]> chunks;
    final int size;
    final String hash;

    private MemoryContent(List<byte[]> chunks, int size, String hash) {
        this.chunks = List.copyOf(chunks); this.size = size; this.hash = hash;
    }

    static MemoryContent copyOf(byte[] bytes) { return owned(bytes.clone()); }
    static MemoryContent owned(byte[] bytes) { return new MemoryContent(List.of(bytes), bytes.length, VfsSupport.sha256Hex(bytes)); }
    static MemoryContent read(InputStream input) throws IOException {
        Builder builder = new Builder(); input.transferTo(builder); return builder.finish();
    }

    byte[] copy() { return copyRange(0, size); }
    private byte[] copyRange(int start, int count) {
        byte[] result = new byte[count]; int destination = 0, skipped = 0;
        for (byte[] chunk : chunks) {
            int available = Math.min(chunk.length, size - skipped);
            int offset = Math.max(0, start - skipped);
            int copied = Math.min(available - offset, count - destination);
            if (copied > 0) { System.arraycopy(chunk, offset, result, destination, copied); destination += copied; }
            skipped += available;
            if (destination == count) break;
        }
        return result;
    }

    InputStream open() {
        return new InputStream() {
            private int position;
            @Override public int read() {
                if (position == size) return -1;
                int index = position / Builder.CHUNK, offset = position % Builder.CHUNK;
                // Byte-array writes use one arbitrarily sized chunk.
                int value = chunks.size() == 1 ? chunks.getFirst()[position] : chunks.get(index)[offset];
                position++; return value & 255;
            }
            @Override public int read(byte[] target, int offset, int length) {
                Objects.checkFromIndexSize(offset, length, target.length);
                if (length == 0) return 0;
                if (position == size) return -1;
                int count = Math.min(length, size - position), copied = 0;
                while (copied < count) {
                    int index = chunks.size() == 1 ? 0 : position / Builder.CHUNK;
                    int source = chunks.size() == 1 ? position : position % Builder.CHUNK;
                    byte[] chunk = chunks.get(index);
                    int amount = Math.min(count - copied, chunk.length - source);
                    System.arraycopy(chunk, source, target, offset + copied, amount);
                    position += amount; copied += amount;
                }
                return count;
            }
            @Override public byte[] readAllBytes() { byte[] result = copyRange(position, size - position); position = size; return result; }
            @Override public long skip(long count) { int skipped = (int) Math.min(Math.max(count, 0), size - position); position += skipped; return skipped; }
            @Override public int available() { return size - position; }
        };
    }

    static class Builder extends OutputStream {
        static final int CHUNK = 64 * 1024;
        private final List<byte[]> chunks = new ArrayList<>();
        private final MessageDigest digest = VfsSupport.digest();
        private int size;
        private boolean finished;

        private byte[] writableChunk(int incoming) {
            int offset = size % CHUNK;
            if (chunks.isEmpty()) chunks.add(new byte[Math.min(CHUNK, Math.max(256, incoming))]);
            else if (offset == 0) chunks.add(new byte[CHUNK]);
            byte[] chunk = chunks.getLast();
            if (offset == chunk.length) {
                chunk = Arrays.copyOf(chunk, Math.min(CHUNK, Math.max(chunk.length * 2, offset + Math.min(incoming, CHUNK - offset))));
                chunks.set(chunks.size() - 1, chunk);
            }
            return chunk;
        }

        @Override public void write(int value) throws IOException {
            if (finished) throw new IOException("Stream is closed");
            if (size == Integer.MAX_VALUE) throw new IOException("In-memory file exceeds Java array address space");
            writableChunk(1)[size % CHUNK] = (byte) value;
            digest.update((byte) value); size++;
        }
        @Override public void write(byte[] source, int offset, int length) throws IOException {
            if (finished) throw new IOException("Stream is closed");
            Objects.checkFromIndexSize(offset, length, source.length);
            if (length > Integer.MAX_VALUE - size) throw new IOException("In-memory file exceeds Java array address space");
            digest.update(source, offset, length);
            while (length > 0) {
                int inChunk = size % CHUNK;
                byte[] chunk = writableChunk(length);
                int copied = Math.min(length, chunk.length - inChunk);
                System.arraycopy(source, offset, chunk, inChunk, copied);
                offset += copied; length -= copied; size += copied;
            }
        }

        MemoryContent finish() {
            if (finished) throw new IllegalStateException("Content already transferred");
            finished = true;
            MemoryContent content = new MemoryContent(chunks, size, HexFormat.of().formatHex(digest.digest()));
            chunks.clear();
            return content;
        }
    }
}
