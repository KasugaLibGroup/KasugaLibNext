package lib.kasuga.scripting.resource;

import lib.kasuga.core.resource.identity.ResourceUri;
import lib.kasuga.core.resource.view.*;
import lib.kasuga.scripting.security.Api;
import java.io.IOException;
import java.util.*;

/** Data-only script surface. Java Path, streams and writable source backends cannot escape. */
public final class ScriptVfsFacade {
    private final ResourceAccess access;
    public ScriptVfsFacade(ResourceAccess access) { this.access = access; }
    @Api public List<Map<String, Object>> query(String prefix, String type, int limit) {
        ResourceQuery query = new ResourceQuery(prefix, type, "", Set.of(), Set.of(), Set.of(), true, 0, limit);
        return access.query(query).stream().map(d -> Map.<String, Object>of("uri", d.uri().toString(), "path", d.logicalPath(),
                "type", d.type(), "subtype", d.subtype(), "evidence", d.typeEvidence().name().toLowerCase(Locale.ROOT), "size", d.size())).toList();
    }
    @Api public String readText(String uri, int maximumBytes) throws IOException { return access.readText(ResourceUri.parse(uri), maximumBytes); }
    @Api public void writeText(String workspace, String path, String text) throws IOException { access.writeText(workspace, path, text); }
    @Api public boolean delete(String workspace, String path) throws IOException { return access.delete(workspace, path); }
}
