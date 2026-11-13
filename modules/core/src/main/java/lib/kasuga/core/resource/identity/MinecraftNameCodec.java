package lib.kasuga.core.resource.identity;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/** Generated channel only. Native assets/data IDs must never pass through this codec. */
public final class MinecraftNameCodec {
    private static final String PREFIX = "kasuga_generated:v1/";
    public static final int MAX_NAME_LENGTH = 8192;
    private MinecraftNameCodec() {}

    public static String encode(ResourceUri uri, String suffix) {
        checkSuffix(suffix);
        String name = PREFIX + HexFormat.of().formatHex(uri.toString().getBytes(StandardCharsets.UTF_8)) + suffix;
        if (name.length() > MAX_NAME_LENGTH) throw new IllegalArgumentException("Generated Minecraft name exceeds limit");
        return name;
    }

    public static ResourceUri decode(String name, String suffix) {
        checkSuffix(suffix);
        if (!name.startsWith(PREFIX) || !name.endsWith(suffix) || name.length() > MAX_NAME_LENGTH)
            throw new IllegalArgumentException("Unknown generated Minecraft name");
        String hex = name.substring(PREFIX.length(), name.length() - suffix.length());
        ResourceUri uri = ResourceUri.parse(new String(HexFormat.of().parseHex(hex), StandardCharsets.UTF_8));
        if (!encode(uri, suffix).equals(name)) throw new IllegalArgumentException("Non-canonical generated name");
        return uri;
    }

    private static void checkSuffix(String suffix) {
        if (suffix == null || !suffix.matches("(?:\\.[a-z0-9]+)?")) throw new IllegalArgumentException("Invalid suffix");
    }
}
