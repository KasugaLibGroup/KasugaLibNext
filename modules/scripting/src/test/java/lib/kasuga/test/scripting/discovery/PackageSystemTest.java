package lib.kasuga.test.scripting.discovery;

import lib.kasuga.KasugaLib;
import lib.kasuga.core.resource.pack.ScopedVanillaFileResourcePack;
import lib.kasuga.core.resource.pack.VfsScopedPackResourcesAdapter;
import lib.kasuga.core.vfs.VfsPath;
import lib.kasuga.core.vfs.backend.InMemoryVfs;
import lib.kasuga.scripting.ScriptEngineRegistry;
import lib.kasuga.scripting.ScriptEngineType;
import lib.kasuga.scripting.discovery.PackageSystem;
import lib.kasuga.scripting.module.PackageRegistry;
import lib.kasuga.scripting.module.ResolvedPackage;
import lib.kasuga.test.scripting.MockModuleResolver;
import lib.kasuga.test.scripting.MockScriptEngine;
import lib.kasuga.test.scripting.TestScopedPackResources;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.neoforged.testframework.junit.EphemeralTestServerProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(EphemeralTestServerProvider.class)
public class PackageSystemTest {

    private PackageSystem packageSystem;
    private PackageRegistry packageRegistry;
    private ScriptEngineRegistry engineRegistry;

    @BeforeEach
    void setUp() {
        packageSystem = KasugaLib.getBean(PackageSystem.class);
        packageRegistry = KasugaLib.getBean(PackageRegistry.class);
        engineRegistry = KasugaLib.getBean(ScriptEngineRegistry.class);
        packageSystem.init();
        packageRegistry.clear();

        MockScriptEngine mockInstance = new MockScriptEngine();
        ScriptEngineType<MockScriptEngine> mockEngine = ScriptEngineType.<MockScriptEngine>builder(() -> mockInstance)
            .scriptType("mock")
            .resolver(new MockModuleResolver())
            .build();
        mockInstance.setType(mockEngine);

        engineRegistry.register(
            ResourceLocation.fromNamespaceAndPath("test", "mock"),
            List.of("mock"),
            mockEngine,
            0
        );
    }

    private TestScopedPackResources loadTestPack(String packName) throws URISyntaxException {
        Path packPath = Path.of(getClass().getResource("/packs/" + packName).toURI());
        return new TestScopedPackResources(packPath, packName);
    }

    @Test
    public void shouldScanAndRegisterPackagesWithCorrectMetadata(MinecraftServer server) throws URISyntaxException {
        TestScopedPackResources pack = loadTestPack("mock_pack");
        List<ResolvedPackage> result = packageSystem.scan(pack);

        assertEquals(2, result.size());

        ResolvedPackage rootPkg = packageRegistry.lookup("@test/mock-pack");
        assertNotNull(rootPkg);
        assertEquals("@test/mock-pack", rootPkg.info().name());
        assertEquals("mock", rootPkg.info().engine());
        assertEquals("1.0.0", rootPkg.info().version());
        assertEquals("index", rootPkg.info().main());
        assertEquals("scripts", rootPkg.packRelativeRoot());
        assertEquals(List.of("packages/*"), rootPkg.info().workspaces());
        assertEquals(List.of("index"), rootPkg.info().entry().common());
        assertEquals("mock", rootPkg.engine().scriptType);
    }

    @Test
    public void shouldScanWorkspaceSubPackagesWithCorrectPaths(MinecraftServer server) throws URISyntaxException {
        TestScopedPackResources pack = loadTestPack("mock_pack");
        packageSystem.scan(pack);

        ResolvedPackage mathPkg = packageRegistry.lookup("@test/math");
        assertNotNull(mathPkg);
        assertEquals("@test/math", mathPkg.info().name());
        assertEquals("mock", mathPkg.info().engine());
        assertEquals("scripts/packages/unrelated-name", mathPkg.packRelativeRoot());
        assertEquals("index", mathPkg.info().main());
        assertEquals(List.of("index"), mathPkg.info().entry().common());
    }

    @Test
    public void shouldSkipPackWithoutToml(MinecraftServer server) throws URISyntaxException {
        TestScopedPackResources pack = loadTestPack("no_toml_pack");
        List<ResolvedPackage> result = packageSystem.scan(pack);
        assertTrue(result.isEmpty());
        assertNull(packageRegistry.lookup("any-name"));
    }

    @Test
    public void shouldHandleScopedPackagesWithCorrectPaths(MinecraftServer server) throws URISyntaxException {
        TestScopedPackResources pack = loadTestPack("scoped_pack");
        List<ResolvedPackage> result = packageSystem.scan(pack);
        assertEquals(2, result.size());

        ResolvedPackage alpha = packageRegistry.lookup("@scope/alpha");
        assertNotNull(alpha);
        assertEquals("@scope/alpha", alpha.info().name());
        assertEquals("scripts", alpha.packRelativeRoot());
        assertEquals(List.of("lib/*"), alpha.info().workspaces());

        ResolvedPackage beta = packageRegistry.lookup("@scope/beta");
        assertNotNull(beta);
        assertEquals("@scope/beta", beta.info().name());
        assertEquals("scripts/lib/whatever", beta.packRelativeRoot());
    }

    @Test
    public void shouldScanDotRelativeWorkspacesFromZipAndVfsPacks(MinecraftServer server, @TempDir Path tempDir) throws IOException {
        Path zipPath = tempDir.resolve("dot-workspace-pack.zip");
        Map<String, String> files = Map.of(
                "scripts/package.toml", """
                        name = "dot_root"
                        engine = "mock"
                        version = "1.0.0"
                        main = "index"
                        workspaces = ["./packages/*"]

                        [entry]
                        common = ["index"]
                        """,
                "scripts/index.js", "export default 1;",
                "scripts/packages/sub/package.toml", """
                        name = "dot_sub"
                        engine = "mock"
                        version = "1.0.0"
                        main = "index"

                        [entry]
                        common = ["index"]
                        """,
                "scripts/packages/sub/index.js", "export default 2;"
        );
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            for (var entry : files.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }

        try (ZipFile zipFile = new ZipFile(zipPath.toFile());
             InMemoryVfs memoryVfs = new InMemoryVfs("dot-workspace-mem")) {
            for (var entry : files.entrySet()) {
                memoryVfs.writeString(VfsPath.of(entry.getKey()), entry.getValue());
            }

            List<ResolvedPackage> zipPackages = packageSystem.scan(new ScopedVanillaFileResourcePack(zipFile));
            assertEquals(2, zipPackages.size());
            assertNotNull(packageRegistry.lookup("dot_root"));
            assertEquals("scripts", packageRegistry.lookup("dot_root").packRelativeRoot());
            assertNotNull(packageRegistry.lookup("dot_sub"));
            assertEquals("scripts/packages/sub", packageRegistry.lookup("dot_sub").packRelativeRoot());

            packageRegistry.clear();
            List<ResolvedPackage> vfsPackages = packageSystem.scan(new VfsScopedPackResourcesAdapter(memoryVfs));
            assertEquals(2, vfsPackages.size());
            assertNotNull(packageRegistry.lookup("dot_root"));
            assertEquals("scripts", packageRegistry.lookup("dot_root").packRelativeRoot());
            assertNotNull(packageRegistry.lookup("dot_sub"));
            assertEquals("scripts/packages/sub", packageRegistry.lookup("dot_sub").packRelativeRoot());
        }
    }
}
