package lib.kasuga.rendering.models.mc.java_and_bedrock;

import lib.kasuga.structure.Pair;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jetbrains.annotations.Nullable;

import javax.swing.text.html.Option;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;

public class IdentifierHelper {

    @Nullable
    public static Object getIdentifier(String input) {
        if (input.contains(":")) {
            ResourceLocation location = ResourceLocation.tryParse(input);
            if (location == null) {
                return getPath(input);
            }
            return location;
        } else {
            Path path = getPath(input);
            if (path != null) return path;
            ResourceLocation location = ResourceLocation.tryParse(input);
            if (location == null) {
                // warn about invalid identifier
                return null;
            }
            return location;
        }
    }

    public static Pair<ResourceLocation, Path> getRLAndPath(String input) {
        Object identifier = getIdentifier(input);
        if (identifier instanceof ResourceLocation rl) {
            return Pair.of(rl, null);
        } else if (identifier instanceof Path path) {
            ResourceLocation rl = ResourceLocation.tryBuild(
                    "kasuga_lib",
                    "file_" + input.hashCode()
            );
            return Pair.of(rl, path);
        } else {
            // warn about invalid identifier
            return null;
        }
    }

    @Nullable
    public static Path getPath(String input) {
        Path path = Path.of(input);
        File file = path.toFile();
        if (file.exists()) {
            if (path.isAbsolute()) {
                // warn about absolute path usage
            }
            return path;
        }
        return null;
    }

    public static Optional<InputStream> getInputStream(String input) {
        Object identifier = getIdentifier(input);
        if (identifier == null) return Optional.empty();
        return getInputStream(identifier);
    }

    public static Optional<InputStream> getInputStream(Object identifier) {
        if (identifier instanceof ResourceLocation rl) {
            ResourceManager manager = Minecraft.getInstance().getResourceManager();
            Optional<Resource> resource = manager.getResource(rl);
            if (resource.isEmpty()) return Optional.empty();
            try {
                return Optional.of(resource.get().open());
            } catch (Exception e) {
                // warn sth;
                return Optional.empty();
            }
        } else if (identifier instanceof Path path) {
            File file = path.toFile();
            if (!file.exists() || !file.isFile()) return Optional.empty();
            try {
                return Optional.of(file.toURI().toURL().openStream());
            } catch (Exception e) {
                // warn sth;
                return Optional.empty();
            }
        }
        return Optional.empty();
    }
}
