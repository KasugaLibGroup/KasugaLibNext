package lib.kasuga.rendering.models.mc.typo.pmx_entry;

import lombok.Getter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

public class ZipHelper implements AutoCloseable {

    private static final List<Charset> LEGACY_ZIP_CHARSETS = List.of(
            Charset.forName("windows-31j"),
            Charset.forName("GB18030"),
            Charset.forName("IBM437")
    );

    @Getter
    private final Map<ZipEntry, ByteBuffer> entries;

    @Getter
    private final Map<String, ZipEntry> entryNameMap;

    private final Map<ZipEntry, List<ZipEntry>> entryTree;

    @Getter
    private final Object path;

    private final Vector3f modelScale;

    public ZipHelper(ZipFile file) {
        this(file, new Vector3f(ZipMeta.DEFAULT_MODEL_SCALE));
    }

    public ZipHelper(ZipFile file, Vector3f modelScale) {
        List<ZipEntry> entriesList = (List<ZipEntry>) file.stream().toList();
        entryNameMap = new HashMap<>();
        this.entries = new HashMap<>();
        entryTree = new HashMap<>();
        for (ZipEntry entry : entriesList) {
            try {
                if (!entry.isDirectory()) {
                    byte[] entryData = file.getInputStream(entry).readAllBytes();
                    ByteBuffer buffer = ByteBuffer.allocate(entryData.length);
                    buffer.order(ByteOrder.nativeOrder());
                    buffer.put(entryData);
                    buffer.flip();
                    entries.put(entry, buffer);
                    entryNameMap.put(normalizeEntryName(entry.getName()), entry);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        path = file.getName();
        this.modelScale = checkedScale(modelScale);
    }

    public ZipHelper(ResourceLocation rl, ZipInputStream stream) {
        this(rl, stream, new Vector3f(ZipMeta.DEFAULT_MODEL_SCALE));
    }

    public ZipHelper(ResourceLocation rl, ZipInputStream stream, Vector3f modelScale) {
        this.entries = new HashMap<>();
        entryNameMap = new HashMap<>();
        entryTree = new HashMap<>();
        path = rl;
        this.modelScale = checkedScale(modelScale);
        ZipEntry entry;
        try {
            while ((entry = stream.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    byte[] entryData = stream.readAllBytes();
                    ByteBuffer buffer = ByteBuffer.allocate(entryData.length);
                    buffer.order(ByteOrder.nativeOrder());
                    buffer.put(entryData);
                    buffer.flip();
                    entries.put(entry, buffer);
                    entryNameMap.put(normalizeEntryName(entry.getName()), entry);
                }
                stream.closeEntry();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Vector3f getModelScale() {
        return new Vector3f(modelScale);
    }

    private static Vector3f checkedScale(Vector3f scale) {
        Vector3f result = new Vector3f(Objects.requireNonNull(scale, "modelScale"));
        if (!result.isFinite() || result.x <= 0f || result.y <= 0f || result.z <= 0f) {
            throw new IllegalArgumentException("modelScale components must be finite and positive");
        }
        return result;
    }

    public boolean hasEntry(String entryName) {
        return entryNameMap.containsKey(normalizeEntryName(entryName));
    }

    public boolean hasEntry(ZipEntry entry) {
        return entries.containsKey(entry);
    }

    public int entryCount() {
        return entries.size();
    }

    public @Nullable ByteBuffer getBuffer(String entryName) {
        ZipEntry entry = entryNameMap.get(normalizeEntryName(entryName));
        if (entry == null) return null;
        return entries.get(entry);
    }

    public @Nullable ByteBuffer getBuffer(ZipEntry entry) {
        return entries.getOrDefault(entry, null);
    }

    public ByteBuffer getBufferOrDefault(String entryName, @Nullable ByteBuffer defaultValue) {
        ZipEntry entry = entryNameMap.get(normalizeEntryName(entryName));
        if (entry == null) return defaultValue;
        return entries.getOrDefault(entry, defaultValue);
    }

    public List<ByteBuffer> searchForName(Predicate<String> namePredicate) {
        return entryNameMap.entrySet()
                .stream()
                .filter(e -> namePredicate.test(e.getKey()))
                .map(e -> entries.get(e.getValue()))
                .toList();
    }

    public List<ByteBuffer> searchFor(Predicate<ZipEntry> entryPredicate) {
        return entries.keySet().stream().filter(entryPredicate).map(entries::get).toList();
    }

    public @Nullable ZipResource getResource(String entryName) {
        String normalized = normalizeEntryName(entryName);
        ZipEntry entry = entryNameMap.get(normalized);
        if (entry == null) return null;
        ByteBuffer buffer = entries.get(entry);
        if (buffer == null) return null;
        return new ZipResource(this, normalized, entry, buffer);
    }

    public @Nullable ZipResource getResource(ZipEntry entry) {
        ByteBuffer buffer = entries.get(entry);
        if (buffer == null) return null;
        return new ZipResource(this, entry.getName(), entry, buffer);
    }

    public ZipResource getResourceOrDefault(String entryName, @Nullable ZipResource defaultValue) {
        String normalized = normalizeEntryName(entryName);
        ZipEntry entry = entryNameMap.get(normalized);
        if (entry == null) return defaultValue;
        ByteBuffer buffer = entries.get(entry);
        if (buffer == null) return defaultValue;
        return new ZipResource(this, normalized, entry, buffer);
    }

    public static String normalizeEntryName(String entryName) {
        if (entryName == null || entryName.isBlank()) return "";
        Deque<String> parts = new ArrayDeque<>();
        for (String part : entryName.replace('\\', '/').split("/+")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (!parts.isEmpty()) parts.removeLast();
            } else {
                parts.addLast(part);
            }
        }
        return String.join("/", parts).toLowerCase(Locale.ROOT);
    }

    public List<ZipResource> searchNameForResource(Predicate<String> namePredicate) {
        return entryNameMap.entrySet()
                .stream()
                .filter(e -> namePredicate.test(e.getKey()))
                .map(e -> {
                    ZipEntry entry = e.getValue();
                    ByteBuffer buffer = entries.get(entry);
                    if (buffer == null) return null;
                    return new ZipResource(this, e.getKey(), entry, buffer);
                })
                .filter(Objects::nonNull)
                .toList();
    }

    public List<ZipResource> searchForResource(Predicate<ZipEntry> entryPredicate) {
        return entries.entrySet().stream()
                .filter(e -> entryPredicate.test(e.getKey()))
                .map(e -> new ZipResource(this, e.getKey().getName(), e.getKey(), e.getValue()))
                .toList();
    }

    public static ZipHelper fromFile(String filePath) throws Exception {
        ZipException utf8Failure;
        try {
            return readFile(filePath, StandardCharsets.UTF_8);
        } catch (ZipException e) {
            utf8Failure = e;
        }

        for (Charset charset : LEGACY_ZIP_CHARSETS) {
            try {
                return readFile(filePath, charset);
            } catch (ZipException e) {
                utf8Failure.addSuppressed(e);
            }
        }
        throw utf8Failure;
    }

    private static ZipHelper readFile(String filePath, Charset charset) throws Exception {
        try (ZipFile file = new ZipFile(filePath, charset)) {
            return new ZipHelper(file);
        }
    }

    public static @Nullable ZipHelper fromResource(ResourceManager manager, ResourceLocation rl) throws Exception {
        Optional<Resource> resource = manager.getResource(rl);
        if (resource.isEmpty()) return null;
        Resource res = resource.get();
        try (ZipInputStream zin = new ZipInputStream(res.open())) {
            return new ZipHelper(rl, zin);
        } catch (Exception e) {
            return null;
        }
    }

    public static @Nullable ZipHelper fromResource(ResourceLocation rl, Resource resource, @Nullable Charset charset) {
        return fromResource(rl, resource, charset, new Vector3f(ZipMeta.DEFAULT_MODEL_SCALE));
    }

    public static @Nullable ZipHelper fromResource(ResourceLocation rl, Resource resource, @Nullable Charset charset,
                                                    Vector3f modelScale) {
        try (ZipInputStream zin = new ZipInputStream(resource.open(), charset == null ? StandardCharsets.UTF_8 : charset)) {
            return new ZipHelper(rl, zin, modelScale);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void close() throws Exception {
        entries.forEach((k, v) -> {
            if (v.isDirect()) {
                MemoryUtil.memFree(v);
            }
        });
        entries.clear();
        entryNameMap.clear();
    }

    @Override
    public String toString() {
        return "ZipHelper{" +
                "path=" + path.toString() +
                ", entryCount=" + entries.size() +
                '}';
    }
}
