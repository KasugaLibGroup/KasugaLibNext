package lib.kasuga.create.registration;

import lib.kasuga.create.registration.bogey.BogeyBlockReg;
import lib.kasuga.create.registration.bogey.BogeyGroupReg;
import lib.kasuga.create.registration.bogey.BogeyMetadataIndex;
import lib.kasuga.create.registration.bogey.BogeySizeReg;
import lib.kasuga.create.registration.track.TrackMaterialMetadataIndex;
import lib.kasuga.create.registration.track.TrackMaterialReg;
import lib.kasuga.create.registration.track.TrackReg;
import org.junit.jupiter.api.Test;
import com.simibubi.create.content.trains.track.TrackBlock;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackBogeyArchitectureTest {

    private static final List<String> CLIENT_ONLY_PREFIXES = List.of(
            "lib/kasuga/create/client/",
            "net/minecraft/client/",
            "net/neoforged/neoforge/client/",
            "com/mojang/blaze3d/",
            "dev/engine_room/flywheel/"
    );
    @Test
    void metadataIndexUsesWeakKeys() {
        assertEquals(WeakHashMap.class, TrackMaterialMetadataIndex.backingMapType());
        Class<?> holder = Arrays.stream(BogeyMetadataIndex.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("Holder"))
                .findFirst()
                .orElseThrow();
        assertTrue(Arrays.stream(holder.getDeclaredFields())
                .allMatch(field -> field.getType() == WeakHashMap.class));
    }

    @Test
    void trackOwnsBlockAndItemAsChildrenWithoutInheritingBlockReg() {
        TrackMaterialReg material = new TrackMaterialReg("composition_material")
                .particle(ResourceLocation.withDefaultNamespace("block/stone"));
        TrackReg<TrackBlock> track = new TrackReg<>("composition_track", material, TrackBlock::new);

        assertFalse(lib.kasuga.registration.minecraft.block.BlockReg.class.isAssignableFrom(TrackReg.class));
        assertEquals(2, track.getChildren().size());
        assertTrue(track.getChildren().contains(track.getBlockReg()));
        assertTrue(track.getChildren().contains(track.getItemReg()));
    }

    @Test
    void commonProductionBytecodeDoesNotLinkClientOnlyPackages()
            throws IOException, InterruptedException {
        Path classes = classesDirectory();
        Path jdeps = Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name").toLowerCase().contains("win")
                        ? "jdeps.exe" : "jdeps");
        Process process = new ProcessBuilder(
                jdeps.toString(), "-q", "-verbose:class", "-filter:none", classes.toString())
                .redirectErrorStream(true)
                .start();
        String report = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), report);

        List<String> violations = report.lines()
                .map(TrackBogeyArchitectureTest::dependencyEdge)
                .filter(edge -> edge.length == 2)
                .filter(edge -> isCommonCreateClass(edge[0]))
                .filter(edge -> CLIENT_ONLY_PREFIXES.stream().anyMatch(edge[1]::startsWith))
                .map(edge -> edge[0] + " -> " + edge[1])
                .toList();

        assertFalse(report.isBlank());
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    private static String[] dependencyEdge(String line) {
        int arrow = line.indexOf(" -> ");
        if (arrow < 0) {
            return new String[0];
        }
        String source = line.substring(0, arrow).trim();
        String target = line.substring(arrow + 4).trim();
        int whitespace = target.indexOf(' ');
        if (whitespace >= 0) {
            target = target.substring(0, whitespace);
        }
        return new String[]{source, target};
    }

    private static boolean isCommonCreateClass(String name) {
        return name.startsWith("lib.kasuga.create.")
                && !name.startsWith("lib.kasuga.create.client.")
                && !name.startsWith("lib.kasuga.create.mixins.client.");
    }

    @Test
    void commonApplicationInitializesWhenClientPackagesAreUnavailable()
            throws IOException, ClassNotFoundException {
        ClientDenyingClassLoader loader =
                new ClientDenyingClassLoader(classesDirectory());
        Class<?> application = Class.forName(
                "lib.kasuga.create.CreateApplication", true, loader);

        assertDoesNotThrow(() -> {
            try {
                var constructor = application.getDeclaredConstructor();
                constructor.setAccessible(true);
                Object instance = constructor.newInstance();
                var init = application.getDeclaredMethod("init");
                init.setAccessible(true);
                init.invoke(instance);
            } catch (InvocationTargetException failure) {
                throw new AssertionError("common Create initialization failed", failure.getCause());
            }
        });
        assertTrue(loader.denied.isEmpty(), () -> String.join("\n", loader.denied));
    }

    private static Path classesDirectory() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve("modules/create/build/classes/java/main");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("cannot locate modules/create main classes");
    }

    private static final class ClientDenyingClassLoader extends ClassLoader {

        private final Path classes;
        private final Set<String> denied = new LinkedHashSet<>();

        private ClientDenyingClassLoader(Path classes) {
            super(ClientDenyingClassLoader.class.getClassLoader());
            this.classes = classes;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                if (name.startsWith("lib.kasuga.create.client.")) {
                    denied.add(name);
                    throw new ClassNotFoundException("client class denied: " + name);
                }
                if (!name.startsWith("lib.kasuga.create.")) {
                    return super.loadClass(name, resolve);
                }
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    loaded = findClass(name);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            Path file = classes.resolve(name.replace('.', '/') + ".class");
            try {
                byte[] bytecode = Files.readAllBytes(file);
                return defineClass(name, bytecode, 0, bytecode.length);
            } catch (IOException failure) {
                throw new ClassNotFoundException(name, failure);
            }
        }
    }

}
