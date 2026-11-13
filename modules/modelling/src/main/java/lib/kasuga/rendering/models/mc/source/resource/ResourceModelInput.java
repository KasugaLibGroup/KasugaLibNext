package lib.kasuga.rendering.models.mc.source.resource;

import com.google.gson.JsonObject;
import lib.kasuga.core.resource.identity.*;
import lib.kasuga.core.resource.routing.StrictResourceJson;
import lib.kasuga.core.resource.source.ResourceSnapshot;
import lib.kasuga.core.resource.view.*;
import net.minecraft.resources.ResourceLocation;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Common leased input for JSON, text and binary loaders; no physical File/JAR naming leaks out. */
public final class ResourceModelInput implements AutoCloseable {
    private final ResourceCatalog catalog;
    private final ResourceSnapshot.LocatedResourceFile file;
    private final ResourceSnapshot.ResourceLease lease;
    public ResourceModelInput(ResourceCatalog catalog, ResourceSnapshot.LocatedResourceFile file) {
        if (catalog.concrete(file.descriptor().uri()).orElse(null) != file) throw new IllegalArgumentException("File belongs to another generation");
        this.catalog = catalog; this.file = file; lease = file.lease();
    }
    public ResourceUri uri() { return file.descriptor().uri(); }
    public long generation() { return catalog.generation(); }
    public String scopeId() { return catalog.scopeId(); }
    public InputStream open() throws IOException { return file.open(lease); }
    public byte[] bytes() throws IOException { try (var input = open()) { return input.readAllBytes(); } }
    public String text() throws IOException { return new String(bytes(), StandardCharsets.UTF_8); }
    public JsonObject json() throws IOException { return StrictResourceJson.parse(text()); }
    public ResourceLocation minecraftId(String suffix) {
        String path = file.descriptor().logicalPath(); String[] parts = path.split("/", 3);
        if (parts.length == 3 && parts[0].equals("assets")) {
            var id = ResourceLocation.tryBuild(parts[1], parts[2]); if (id != null) return id;
        }
        return ResourceLocation.parse(MinecraftNameCodec.encode(uri(), suffix));
    }
    /** Decoder-relative links may contain .. only while remaining inside the same concrete source/root. */
    public ResourceModelInput sibling(String relative, String expectedType) {
        if (relative == null || relative.startsWith("/") || relative.indexOf('\\') >= 0 || relative.indexOf(':') >= 0)
            throw new IllegalArgumentException("UNSAFE_PATH: model dependency");
        List<String> segments = new ArrayList<>(List.of(uri().path().split("/"))); segments.removeLast();
        for (String part : relative.split("/", -1)) {
            if (part.equals(".")) continue;
            if (part.equals("..")) { if (segments.isEmpty()) throw new IllegalArgumentException("UNSAFE_PATH: model dependency escapes root"); segments.removeLast(); }
            else { ResourcePaths.path(part, false); segments.add(part); }
        }
        var target = ResourceUri.file(uri().sourceId(), uri().rootId(), String.join("/", segments));
        var resolved = catalog.concrete(target).orElseThrow(() -> new IllegalArgumentException("Model dependency unavailable: " + target));
        new ResourceKey(resolved.descriptor().logicalPath(), expectedType).check(resolved.descriptor());
        return new ResourceModelInput(catalog, resolved);
    }
    @Override public void close() throws IOException { lease.close(); }
}
