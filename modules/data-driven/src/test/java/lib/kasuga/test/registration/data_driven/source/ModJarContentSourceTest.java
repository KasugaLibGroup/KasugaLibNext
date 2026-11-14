package lib.kasuga.test.registration.data_driven.source;

import cpw.mods.jarhandling.SecureJar;
import lib.kasuga.registration.data_driven.source.ContentSource;
import lib.kasuga.registration.data_driven.source.LogicalPath;
import lib.kasuga.registration.data_driven.source.ModJarContentSource;
import lib.kasuga.registration.data_driven.source.SourceDescriptor;
import net.neoforged.neoforgespi.locating.IModFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModJarContentSourceTest {
    @Test
    void enumeratesAndReadsThroughTheSecureJarRoot(@TempDir Path root) throws IOException {
        Path file = root.resolve("data/example/models/one.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "one");
        SecureJar secureJar = SecureJar.from(root);
        IModFile modFile = (IModFile) Proxy.newProxyInstance(
                IModFile.class.getClassLoader(),
                new Class<?>[]{IModFile.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getSecureJar")) return secureJar;
                    if (method.getName().equals("getFilePath")) return root;
                    if (method.getName().equals("toString")) return "test mod jar";
                    return null;
                });
        SourceDescriptor descriptor = SourceDescriptor.of("kasuga_lib", "kasuga_lib", List.of("example"));

        try (ContentSource source = ModJarContentSource.open(modFile, descriptor)) {
            assertEquals(List.of("data/example/models/one.json"),
                    source.files().stream().map(located -> located.path().value()).toList());
            try (InputStream input = source.open(LogicalPath.of("data/example/models/one.json")).orElseThrow()) {
                assertEquals("one", new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }
}
