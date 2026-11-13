package lib.kasuga.core.resource.view;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Bounded discovery, never a decoder, trust decision or permission to execute. */
public final class ResourceTypeDetector {
    public static final int HEADER_BYTES = 512;
    public enum Evidence { MAGIC, TEXT_PREFIX, EXTENSION, DECLARED, UNKNOWN }
    public record Detection(String type, String subtype, Evidence evidence) {}
    private static final Map<String, String> TEXT_HINTS = Map.ofEntries(
            Map.entry("bbmodel", "model"), Map.entry("obj", "model"), Map.entry("gltf", "model"),
            Map.entry("fmt", "model"), Map.entry("mtb", "model"),
            Map.entry("js", "script"), Map.entry("py", "script"), Map.entry("kts", "script"),
            Map.entry("json", "config"), Map.entry("toml", "config"), Map.entry("yaml", "config"), Map.entry("yml", "config"),
            Map.entry("txt", "text"), Map.entry("md", "text"), Map.entry("glsl", "text"), Map.entry("vsh", "text"), Map.entry("fsh", "text"));
    private ResourceTypeDetector() {}

    public static Detection detect(String path, byte[] prefix) {
        if (starts(prefix, 137,80,78,71,13,10,26,10)) return magic("texture", "png");
        if (starts(prefix, 255,216,255)) return magic("texture", "jpeg");
        if (ascii(prefix, 0, "GIF87a") || ascii(prefix, 0, "GIF89a")) return magic("texture", "gif");
        if (ascii(prefix, 0, "RIFF")) {
            if (ascii(prefix, 8, "WEBP")) return magic("texture", "webp");
            if (ascii(prefix, 8, "WAVE")) return magic("audio", "wav");
        }
        if (ascii(prefix, 0, "glTF")) return magic("model", "glb");
        if (ascii(prefix, 0, "PMX ")) return magic("model", "pmx");
        if (ascii(prefix, 0, "OggS")) return magic("audio", "ogg");
        if (ascii(prefix, 0, "fLaC")) return magic("audio", "flac");
        if (ascii(prefix, 0, "ID3")) return magic("audio", "mp3");
        if (starts(prefix, 80,75,3,4) || starts(prefix, 80,75,5,6)) return magic("binary", "zip");
        if (!isText(prefix)) return new Detection("binary", "", Evidence.UNKNOWN);
        String extension = ResourceDescriptor.extension(path);
        String hint = TEXT_HINTS.get(extension);
        if (hint != null) return new Detection(hint, extension, Evidence.EXTENSION);
        String text = new String(prefix, 0, Math.min(prefix.length, HEADER_BYTES), StandardCharsets.UTF_8).stripLeading();
        if (text.startsWith("\uFEFF")) text = text.substring(1).stripLeading();
        if (text.startsWith("{") || text.startsWith("[")) return new Detection("config", "json", Evidence.TEXT_PREFIX);
        return prefix.length == 0 ? new Detection("binary", "", Evidence.UNKNOWN) : new Detection("text", "", Evidence.TEXT_PREFIX);
    }

    private static boolean isText(byte[] bytes) {
        int length = Math.min(bytes.length, HEADER_BYTES);
        for (int i = 0; i < length; i++) {
            int b = bytes[i] & 255;
            if (b < 32 && b != 9 && b != 10 && b != 13 || b == 127) return false;
        }
        var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        // A limited prefix may end halfway through a valid code point; don't invent a replacement character.
        return !decoder.decode(ByteBuffer.wrap(bytes, 0, length), CharBuffer.allocate(HEADER_BYTES), length < HEADER_BYTES).isError();
    }
    private static Detection magic(String type, String subtype) { return new Detection(type, subtype, Evidence.MAGIC); }
    private static boolean starts(byte[] bytes, int... signature) {
        if (bytes.length < signature.length) return false;
        for (int i = 0; i < signature.length; i++) if ((bytes[i] & 255) != signature[i]) return false;
        return true;
    }
    private static boolean ascii(byte[] bytes, int offset, String signature) {
        if (bytes.length < offset + signature.length()) return false;
        for (int i = 0; i < signature.length(); i++) if (bytes[offset + i] != signature.charAt(i)) return false;
        return true;
    }
}
