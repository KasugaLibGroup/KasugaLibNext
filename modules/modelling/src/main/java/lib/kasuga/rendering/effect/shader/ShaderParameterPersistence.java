package lib.kasuga.rendering.effect.shader;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.JsonDeserializer;
import com.google.gson.reflect.TypeToken;
import lib.kasuga.utils.json.GsonBindings;
import com.mojang.logging.LogUtils;
import lib.kasuga.shader.ShaderParameter;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;
import org.jetbrains.annotations.ApiStatus;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Persists default RenderShaderHandle parameter blocks across client restarts. */
public final class ShaderParameterPersistence {
    private static final int FORMAT_VERSION = 1;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = GsonBindings.builder().setPrettyPrinting().create();
    private static final Object LOCK = new Object();
    private static final Object WRITE_LOCK = new Object();
    private static final long WRITE_DELAY_MILLIS = 200L;
    private static final ScheduledExecutorService WRITER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "Kasuga-Shader-Parameter-Writer");
        thread.setDaemon(true);
        return thread;
    });

    private static Map<String, Map<String, double[]>> saved = new LinkedHashMap<>();
    private static Path file;
    private static boolean initialized;
    private static boolean writeScheduled;
    private static long revision;
    private static long writtenRevision = -1L;

    private ShaderParameterPersistence() {}

    /** Loads persisted values before shader registrations are posted during client setup. */
    @ApiStatus.Internal
    public static void initialize() {
        synchronized (LOCK) {
            if (initialized) return;
            file = FMLPaths.CONFIGDIR.get().resolve("kasuga_lib").resolve("shader-parameters.json");
            if (Files.isRegularFile(file)) {
                try {
                    saved = decode(Files.readString(file, StandardCharsets.UTF_8));
                } catch (IOException | RuntimeException exception) {
                    LOGGER.error("Failed to read shader parameter settings from {}; using defaults", file, exception);
                    saved = new LinkedHashMap<>();
                }
            }
            initialized = true;
        }
    }

    public static Path file() {
        synchronized (LOCK) {
            return file != null
                    ? file
                    : FMLPaths.CONFIGDIR.get().resolve("kasuga_lib").resolve("shader-parameters.json");
        }
    }

    /** Forces the latest in-memory settings to disk. Normal changes are written asynchronously. */
    public static void flush() {
        Snapshot snapshot;
        synchronized (LOCK) {
            if (!initialized) return;
            snapshot = new Snapshot(file, copy(saved), revision);
        }
        write(snapshot);
    }

    static void restore(ResourceLocation shaderId, ShaderParameterBlock block) {
        Map<String, double[]> values;
        synchronized (LOCK) {
            if (!initialized) return;
            Map<String, double[]> stored = saved.get(shaderId.toString());
            values = stored == null ? null : copyValues(stored);
        }
        if (values == null) return;
        for (ShaderParameter parameter : block.schema().parameters()) {
            double[] value = values.get(parameter.name());
            if (value == null) continue;
            try {
                block.set(parameter, value);
            } catch (IllegalArgumentException exception) {
                LOGGER.warn("Ignoring invalid persisted value for shader {} parameter {}: {}",
                        shaderId, parameter.name(), exception.getMessage());
            }
        }
    }

    static void record(ResourceLocation shaderId, ShaderParameterBlock block) {
        boolean schedule = false;
        synchronized (LOCK) {
            if (!initialized) return;
            LinkedHashMap<String, double[]> values = new LinkedHashMap<>();
            for (ShaderParameter parameter : block.schema().parameters()) {
                if (!block.isDefault(parameter)) {
                    values.put(parameter.name(), block.values(parameter.name()));
                }
            }
            if (values.isEmpty()) saved.remove(shaderId.toString());
            else saved.put(shaderId.toString(), values);
            revision++;
            if (!writeScheduled) {
                writeScheduled = true;
                schedule = true;
            }
        }
        if (schedule) scheduleWrite();
    }

    private static void writeLoop() {
        Snapshot snapshot;
        synchronized (LOCK) {
            snapshot = new Snapshot(file, copy(saved), revision);
        }
        write(snapshot);
        synchronized (LOCK) {
            if (revision == snapshot.revision) {
                writeScheduled = false;
            } else {
                scheduleWrite();
            }
        }
    }

    private static void scheduleWrite() {
        WRITER.schedule(ShaderParameterPersistence::writeLoop, WRITE_DELAY_MILLIS, TimeUnit.MILLISECONDS);
    }

    private static boolean write(Snapshot snapshot) {
        synchronized (WRITE_LOCK) {
            if (snapshot.revision < writtenRevision) return true;
            try {
                Path parent = snapshot.file.getParent();
                Files.createDirectories(parent);
                Path temporary = parent.resolve(snapshot.file.getFileName() + ".tmp");
                Files.writeString(temporary, encode(snapshot.values), StandardCharsets.UTF_8);
                try {
                    Files.move(temporary, snapshot.file,
                            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, snapshot.file, StandardCopyOption.REPLACE_EXISTING);
                }
                writtenRevision = snapshot.revision;
                return true;
            } catch (IOException exception) {
                LOGGER.error("Failed to save shader parameter settings to {}", snapshot.file, exception);
                return false;
            }
        }
    }

    static String encode(Map<String, Map<String, double[]>> values) {
        JsonObject root = new JsonObject();
        root.addProperty("version", FORMAT_VERSION);
        JsonObject shaders = new JsonObject();
        for (Map.Entry<String, Map<String, double[]>> shader : new TreeMap<>(values).entrySet()) {
            JsonObject parameters = new JsonObject();
            for (Map.Entry<String, double[]> parameter : new TreeMap<>(shader.getValue()).entrySet()) {
                JsonArray components = new JsonArray();
                for (double component : parameter.getValue()) components.add(component);
                parameters.add(parameter.getKey(), components);
            }
            shaders.add(shader.getKey(), parameters);
        }
        root.add("shaders", shaders);
        return GSON.toJson(root) + '\n';
    }

    static Map<String, Map<String, double[]>> decode(String source) {
        Objects.requireNonNull(source, "source");
        SavedInput input = Objects.requireNonNull(GSON.fromJson(source, SavedInput.class), "settings");
        if (input.version != FORMAT_VERSION)
            throw new IllegalArgumentException("Unsupported shader parameter format version: " + input.version);
        return input.shaders == null ? new LinkedHashMap<>() : input.shaders;
    }

    private static final class SavedInput {
        Integer version = FORMAT_VERSION;
        @JsonAdapter(ShaderValuesAdapter.class) Map<String, Map<String, double[]>> shaders = new LinkedHashMap<>();
    }

    /** Old files may contain non-parameter metadata; preserve entry-level isolation. */
    private static final class ShaderValuesAdapter implements JsonDeserializer<Map<String, Map<String, double[]>>> {
        @Override public Map<String, Map<String, double[]>> deserialize(JsonElement json, java.lang.reflect.Type type,
                com.google.gson.JsonDeserializationContext context) {
            LinkedHashMap<String, Map<String, double[]>> result = new LinkedHashMap<>();
            if (!json.isJsonObject()) return result;
            Map<String, JsonElement> shaders = context.deserialize(json, new TypeToken<Map<String, JsonElement>>() {}.getType());
            shaders.forEach((shader, raw) -> {
                if (!raw.isJsonObject()) return;
                Map<String, JsonElement> entries = context.deserialize(raw, new TypeToken<Map<String, JsonElement>>() {}.getType());
                Map<String, double[]> parameters = new LinkedHashMap<>();
                entries.forEach((name, value) -> {
                    if (!value.isJsonArray()) return;
                    double[] components = context.deserialize(value, double[].class);
                    for (double component : components) if (!Double.isFinite(component))
                        throw new IllegalArgumentException("Persisted shader parameter must be finite");
                    parameters.put(name, components);
                });
                result.put(shader, parameters);
            });
            return result;
        }
    }

    private static Map<String, Map<String, double[]>> copy(
            Map<String, Map<String, double[]>> source
    ) {
        LinkedHashMap<String, Map<String, double[]>> result = new LinkedHashMap<>();
        source.forEach((shader, parameters) -> result.put(shader, copyValues(parameters)));
        return result;
    }

    private static Map<String, double[]> copyValues(Map<String, double[]> source) {
        LinkedHashMap<String, double[]> result = new LinkedHashMap<>();
        source.forEach((name, values) -> result.put(name, values.clone()));
        return result;
    }

    private record Snapshot(
            Path file,
            Map<String, Map<String, double[]>> values,
            long revision
    ) {}
}
