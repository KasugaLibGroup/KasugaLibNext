package lib.kasuga.rendering.models.uml.dynamic.proxy;

import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyKernelBoundaryTest {

    private static final List<String> FORBIDDEN_PREFIXES = List.of(
            "net/minecraft/",
            "net/neoforged/",
            "com/simibubi/create/",
            "dev/engine_room/flywheel/",
            "com/mojang/blaze3d/",
            "lib/kasuga/create/",
            "lib/kasuga/rendering/models/mc/"
    );
    private static final Pattern DESCRIPTOR_TYPE = Pattern.compile("L([^;<]+/[^;<]+);");
    private static final int[] CONSTANT_POOL_PAYLOAD_SIZES = {
            0, 0, 0, 4, 4, 8, 8, 2, 2, 4, 4, 4, 4,
            0, 0, 3, 2, 4, 4, 2, 2
    };

    @Test
    void proxyKernelBytecodeHasNoPlatformOrHostDependencies() throws IOException, URISyntaxException {
        Path classes = Path.of(ProxyRuntime.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        Path kernel = classes.resolve("lib/kasuga/rendering/models/uml/dynamic/proxy");
        List<String> violations = new ArrayList<>();
        int classCount = 0;

        try (Stream<Path> files = Files.walk(kernel)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                classCount++;
                for (String reference : ClassFileReferences.read(file)) {
                    if (FORBIDDEN_PREFIXES.stream().anyMatch(reference::startsWith)) {
                        violations.add(kernel.relativize(file) + " -> " + reference);
                    }
                }
            }
        }

        assertTrue(classCount >= 15, "unexpectedly few proxy kernel classes: " + classCount);
        assertTrue(violations.isEmpty(), () -> String.join("\n", violations));
    }

    private static final class ClassFileReferences {

        private ClassFileReferences() {
        }

        private static Set<String> read(Path file) throws IOException {
            try (DataInputStream input = new DataInputStream(Files.newInputStream(file))) {
                if (input.readInt() != 0xCAFEBABE) {
                    throw new IOException("not a class file: " + file);
                }
                input.readUnsignedShort();
                input.readUnsignedShort();
                int constantPoolCount = input.readUnsignedShort();
                String[] utf8 = new String[constantPoolCount];
                int[] classNames = new int[constantPoolCount];
                for (int index = 1; index < constantPoolCount; index++) {
                    int tag = input.readUnsignedByte();
                    if (tag == 1) {
                        utf8[index] = input.readUTF();
                    } else if (tag == 7) {
                        classNames[index] = input.readUnsignedShort();
                    } else {
                        input.skipNBytes(CONSTANT_POOL_PAYLOAD_SIZES[tag]);
                    }
                    if (tag == 5 || tag == 6) {
                        index++;
                    }
                }
                return references(utf8, classNames);
            }
        }

        private static Set<String> references(String[] utf8, int[] classNames) {
            Set<String> references = new LinkedHashSet<>();
            for (int index = 1; index < utf8.length; index++) {
                if (classNames[index] != 0) {
                    references.add(utf8[classNames[index]]);
                }
                if (utf8[index] != null) {
                    Matcher matcher = DESCRIPTOR_TYPE.matcher(utf8[index]);
                    while (matcher.find()) {
                        references.add(matcher.group(1));
                    }
                }
            }
            return references;
        }
    }
}
