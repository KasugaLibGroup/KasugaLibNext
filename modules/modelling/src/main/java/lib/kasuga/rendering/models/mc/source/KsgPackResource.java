package lib.kasuga.rendering.models.mc.source;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.resources.IoSupplier;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Set;

public abstract class KsgPackResource<UrlType> extends PathPackResources {

    private final HashMap<UrlType, IoSupplier<InputStream>> resources;

    public KsgPackResource(PackLocationInfo location) {
        super(location, Path.of("dummy"));
        this.resources = new HashMap<>();
    }

    public abstract UrlType getUrl(String str);

    public abstract UrlType getUrl(ResourceLocation location);

    public void addResource(String identifier, IoSupplier<InputStream> supplier) {
        UrlType url = getUrl(identifier);
        resources.put(url, supplier);
    }

    public void addResource(UrlType url, IoSupplier<InputStream> supplier) {
        resources.put(url, supplier);
    }

    public @Nullable IoSupplier<InputStream> getResource(UrlType url) {
        return resources.get(url);
    }

    public boolean hasResource(UrlType url) {
        return resources.containsKey(url);
    }

    public void removeResource(UrlType url) {
        resources.remove(url);
    }

    public void clearResources() {
        resources.clear();
    }

    public int getResourceCount() {
        return resources.size();
    }

    @Override
    public @Nullable IoSupplier<InputStream> getRootResource(String... elements) {
        return null;
    }

    @Override
    public @Nullable IoSupplier<InputStream> getResource(PackType packType, ResourceLocation location) {
        UrlType location1 = getUrl(location);
        return getResource(location1);
    }

    @Override
    public void listResources(PackType packType, String namespace, String p_path, ResourceOutput resourceOutput) {
        super.listResources(packType, namespace, p_path, resourceOutput);
    }

    @Override
    public Set<String> getNamespaces(PackType type) {
        return super.getNamespaces(type);
    }
}
