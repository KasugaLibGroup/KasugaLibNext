package lib.kasuga.core.resource.identity;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Concrete provider identity. Resolution always requires an explicit resource scope. */
public record ResourceUri(String sourceId, String rootId, String path, String childKey)
        implements Comparable<ResourceUri> {
    public ResourceUri {
        ResourcePaths.id(sourceId);
        ResourcePaths.id(rootId);
        ResourcePaths.path(path, false);
        Objects.requireNonNull(childKey, "childKey");
        if (!childKey.isEmpty()) ResourcePaths.path(childKey, false);
        if (path.matches(".*%[0-9A-Fa-f]{2}.*") || childKey.matches(".*%[0-9A-Fa-f]{2}.*"))
            throw new IllegalArgumentException("URI_INVALID: nested escape-looking provider name");
    }

    public static ResourceUri file(String source, String root, String path) {
        return new ResourceUri(source, root, path, "");
    }

    public ResourceUri child(String key) {
        if (!childKey.isEmpty()) throw new IllegalArgumentException("Child must reference a file parent");
        return new ResourceUri(sourceId, rootId, path, ResourcePaths.path(key, false));
    }

    public ResourceUri parent() { return file(sourceId, rootId, path); }

    public static ResourceUri parse(String text) {
        Objects.requireNonNull(text, "uri");
        if (!text.startsWith("ksg://") || text.indexOf('?') >= 0)
            throw new IllegalArgumentException("URI_INVALID: expected ksg URI without query");
        int roots = text.indexOf("/roots/", 6);
        if (roots < 0) throw new IllegalArgumentException("URI_INVALID: root is required");
        int start = roots + 7;
        int slash = text.indexOf('/', start);
        if (slash < 0) throw new IllegalArgumentException("URI_INVALID: file path is required");
        int fragment = text.indexOf('#', slash);
        String path = text.substring(slash + 1, fragment < 0 ? text.length() : fragment);
        String child = fragment < 0 ? "" : decodePath(text.substring(fragment + 1));
        if (fragment >= 0 && child.isEmpty()) throw new IllegalArgumentException("URI_INVALID: empty fragment");
        ResourceUri uri = new ResourceUri(text.substring(6, roots), text.substring(start, slash), decodePath(path), child);
        if (!uri.toString().equals(text)) throw new IllegalArgumentException("URI_INVALID: non-canonical encoding");
        return uri;
    }

    private static String decodePath(String encoded) {
        StringBuilder out = new StringBuilder();
        for (String segment : encoded.split("/", -1)) {
            if (!out.isEmpty()) out.append('/');
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            for (int i = 0; i < segment.length(); i++) {
                char c = segment.charAt(i);
                if (c == '%') {
                    if (i + 2 >= segment.length()) throw new IllegalArgumentException("URI_INVALID: truncated escape");
                    int high = Character.digit(segment.charAt(++i), 16);
                    int low = Character.digit(segment.charAt(++i), 16);
                    if (high < 0 || low < 0) throw new IllegalArgumentException("URI_INVALID: malformed escape");
                    bytes.write((high << 4) | low);
                } else {
                    if (c > 127) throw new IllegalArgumentException("URI_INVALID: encode UTF-8 bytes");
                    bytes.write(c);
                }
            }
            try {
                String decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
                if (decoded.contains("/") || decoded.matches(".*%[0-9A-Fa-f]{2}.*"))
                    throw new IllegalArgumentException("URI_INVALID: encoded separator or nested escape");
                out.append(decoded);
            } catch (CharacterCodingException invalid) {
                throw new IllegalArgumentException("URI_INVALID: malformed UTF-8", invalid);
            }
        }
        return out.toString();
    }

    private static String encodePath(String text) {
        StringBuilder encoded = new StringBuilder();
        for (byte item : text.getBytes(StandardCharsets.UTF_8)) {
            int b = item & 255;
            if (b >= 'a' && b <= 'z' || b >= 'A' && b <= 'Z' || b >= '0' && b <= '9'
                    || b == '-' || b == '_' || b == '.' || b == '~' || b == '/') encoded.append((char) b);
            else encoded.append('%').append("0123456789ABCDEF".charAt(b >>> 4)).append("0123456789ABCDEF".charAt(b & 15));
        }
        return encoded.toString();
    }

    @Override public String toString() {
        return "ksg://" + sourceId + "/roots/" + rootId + "/" + encodePath(path)
                + (childKey.isEmpty() ? "" : "#" + encodePath(childKey));
    }

    @Override public int compareTo(ResourceUri other) { return toString().compareTo(other.toString()); }
}
