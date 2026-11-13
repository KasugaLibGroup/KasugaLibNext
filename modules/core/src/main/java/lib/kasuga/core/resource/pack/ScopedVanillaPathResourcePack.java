package lib.kasuga.core.resource.pack;

import net.minecraft.server.packs.PathPackResources;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ScopedVanillaPathResourcePack implements HierarchicalScopedPackResources {
    private final Path source;
    private final PathPackResources pack;

    public ScopedVanillaPathResourcePack(Path source, PathPackResources pack) {
        this.source = source;
        this.pack = pack;
    }

    @Override
    public InputStream open(String prefix, String path) throws IOException {
        Path filePath = resolve(prefix, path);
        return Files.newInputStream(filePath);
    }

    @Override
    public boolean exists(String prefix, String path) {
        Path filePath = resolve(prefix, path);
        return Files.exists(filePath);
    }

    public List<String> list(String prefix, String path) throws IOException {
        try(var stream = Files.list(resolve(prefix, path))){
            return stream.map(p -> p.getFileName().toString()).toList();
        }
    }

    @Override
    public boolean isRegularFile(String prefix, String path) {
        return Files.isRegularFile(resolve(prefix, path));
    }

    @Override
    public boolean isDirectory(String prefix, String path) {
        return Files.isDirectory(resolve(prefix, path));
    }

    @Override
    public String getName() {
        return pack.packId();
    }

    public Path resolve(String prefix, String path) {
        return ScopedPackResources.safeResolve(this.source, prefix, path);
    }
}


