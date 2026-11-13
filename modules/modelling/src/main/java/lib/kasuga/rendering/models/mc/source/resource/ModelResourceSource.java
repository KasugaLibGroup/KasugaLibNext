package lib.kasuga.rendering.models.mc.source.resource;

import com.google.gson.*;
import lib.kasuga.core.resource.identity.*;
import lib.kasuga.core.resource.routing.StrictResourceJson;
import lib.kasuga.core.resource.source.*;
import lib.kasuga.core.resource.view.ResourceTypeDetector;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Opt-in bbmodel → generated MC model/texture files, prepared before pack discovery consumes them. */
public final class ModelResourceSource implements ResourceSource {
    public static final String DECODER_VERSION = "bbmodel-mc-v1";
    private final ResourceSource input;
    private final InputPolicy policy;
    private final long maxImagePixels;
    private final boolean owned;
    public ModelResourceSource(ResourceSource input, InputPolicy policy, long maxImagePixels, boolean owned) {
        if (maxImagePixels < 1) throw new IllegalArgumentException("Invalid pixel limit");
        this.input = input; this.policy = policy; this.maxImagePixels = maxImagePixels; this.owned = owned;
    }
    @Override public ResourceSourceDescriptor descriptor() { return input.descriptor(); }
    @Override public ResourceSnapshot capture() throws IOException {
        try (var original = input.capture(); var builder = new ResourceSnapshot.Builder(descriptor(), policy)) {
            for (var file : original.files()) {
                var d = file.descriptor();
                builder.copy(file); // Preserve original identity, annotations and bytes alongside generated files.
                if (d.logicalPath().startsWith("assets/") || d.logicalPath().startsWith("data/")) continue;
                if (!d.type().equals("model") || !d.subtype().equals("bbmodel")) continue;
                if (d.size() > policy.maxJsonBytes()) throw new IOException("LIMIT_EXCEEDED: model JSON");
                JsonObject model = StrictResourceJson.parse(new String(file.readBytes(), StandardCharsets.UTF_8), policy.maxJsonDepth());
                JsonArray textures = model.has("textures") ? model.getAsJsonArray("textures") : new JsonArray();
                Set<ResourceUri> identities = new HashSet<>();
                for (int index = 0; index < textures.size(); index++) {
                    JsonObject texture = textures.get(index).getAsJsonObject();
                    String source = texture.has("source") ? texture.get("source").getAsString() : "";
                    if (!source.startsWith("data:")) {
                        if (!source.isEmpty()) throw new IOException("Non-MC bbmodel external textures require an explicit dependency adapter");
                        continue;
                    }
                    if (!source.startsWith("data:image/png;base64,")) throw new IOException("Unsupported embedded image encoding (expected PNG)");
                    String key = texture.has("uuid") ? "texture/uuid/" + HexFormat.of().formatHex(texture.get("uuid").getAsString().getBytes(StandardCharsets.UTF_8)) : "texture/index/" + index;
                    ResourceUri child = d.uri().child(key);
                    if (!identities.add(child)) throw new IOException("DUPLICATE_ENTRY: embedded texture UUID");
                    byte[] png;
                    try { png = Base64.getDecoder().decode(source.substring("data:image/png;base64,".length())); }
                    catch (IllegalArgumentException invalid) { throw new IOException("Invalid embedded PNG base64", invalid); }
                    checkPng(png);
                    String name = MinecraftNameCodec.encode(child, ".png"), path = name.substring(name.indexOf(':') + 1);
                    builder.add(child, "assets/kasuga_generated/textures/" + path, "texture", "png", Set.of(DECODER_VERSION), () -> new ByteArrayInputStream(png));
                    texture.addProperty("source", "kasuga_generated:textures/" + path);
                }
                ResourceUri projectedModel = d.uri().child("minecraft/model");
                String name = MinecraftNameCodec.encode(projectedModel, ".bbmodel");
                String path = name.substring(name.indexOf(':') + 1);
                byte[] bytes = model.toString().getBytes(StandardCharsets.UTF_8);
                builder.add(projectedModel, "assets/kasuga_generated/models/" + path, "model", "bbmodel", Set.of(DECODER_VERSION), () -> new ByteArrayInputStream(bytes));
            }
            return builder.build();
        }
    }
    private void checkPng(byte[] png) throws IOException {
        if (png.length < 33 || !ResourceTypeDetector.detect("", png).subtype().equals("png")
                || png[12] != 'I' || png[13] != 'H' || png[14] != 'D' || png[15] != 'R') throw new IOException("Invalid embedded PNG header");
        ByteBuffer header = ByteBuffer.wrap(png).order(ByteOrder.BIG_ENDIAN);
        int width = header.getInt(16), height = header.getInt(20);
        if (width < 1 || height < 1 || (long) width * height > maxImagePixels) throw new IOException("LIMIT_EXCEEDED: decoded image pixels");
    }
    @Override public void close() throws IOException { if (owned) input.close(); }
}
