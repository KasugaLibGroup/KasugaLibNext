package lib.kasuga.rendering.models.mc.source.model.zip;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import lib.kasuga.rendering.models.mc.typo.pmx_entry.ZipHelper;
import lib.kasuga.rendering.models.mc.typo.pmx_entry.ZipMeta;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.joml.Vector3f;
import org.slf4j.Logger;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipInputStream;

public class JarZipModelSource extends ZipModelSource<ResourceLocation> {

    private static final Logger LOGGER = LogUtils.getLogger();


    public JarZipModelSource(String name) {
        super(name);
    }

    @Override
    public Optional<ZipHelper> getInput(ResourceLocation input) {
        ResourceManager manager = Minecraft.getInstance().getResourceManager();
        ResourceLocation location = ResourceLocation.tryBuild(
                input.getNamespace(), input.getPath()
        );
        List<Resource> resources = manager.getResourceStack(location);
        Resource resource = resources.isEmpty()
                ? manager.getResource(location).orElse(null)
                : resources.getFirst();
        if (resource == null) {
            LOGGER.warn("Archive resource not found: {}", location);
            return Optional.empty();
        }
        ResourceLocation metaLocation = ResourceLocation.tryBuild(
                input.getNamespace(), input.getPath().replace(".zip", ".json")
        );
        Optional<Resource> metaResource = manager.getResource(metaLocation);
        Charset charset = StandardCharsets.UTF_8;
        Vector3f modelScale = new Vector3f(ZipMeta.DEFAULT_MODEL_SCALE);
        if (metaResource.isPresent()) {
            Resource meta = metaResource.get();
            try {
                JsonElement json = JsonParser.parseReader(meta.openAsReader());
                if (json.isJsonObject()) {
                    ZipMeta metaData = new ZipMeta(json.getAsJsonObject());
                    charset = metaData.getCharset();
                    modelScale = metaData.getModelScale();
                }
            } catch (Exception ignored) {}
        }
        try {
            try (ZipInputStream stream = new ZipInputStream(resource.open(), charset)) {
                return Optional.of(new ZipHelper(location, stream, modelScale));
            }
        } catch (Exception e) {
            LOGGER.error("Exception while reading archive resource {}", location, e);
            return Optional.empty();
        }
    }

    @Override
    public Class<ResourceLocation> getInputType() {
        return ResourceLocation.class;
    }

    @Override
    public boolean isValidInput(Object input) {
        return input instanceof ResourceLocation;
    }
}
