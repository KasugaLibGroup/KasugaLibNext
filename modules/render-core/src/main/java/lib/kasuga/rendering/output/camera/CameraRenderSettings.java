package lib.kasuga.rendering.output.camera;

import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.Objects;

/** Immutable configuration owned by one camera, never written to the user's options. */
public record CameraRenderSettings(int renderDistance, Quality quality, Shader shader,
                                   List<Path> resourcePacks, int mipmapLevels, boolean ambientOcclusion) {
    public CameraRenderSettings(int renderDistance, Quality quality, Shader shader) {
        this(renderDistance, quality, shader, List.of(), 0, true);
    }
    public enum Quality { FAST, FANCY }
    /** Null pack disables shaders for this camera. A pack can be a directory or zip. */
    public record Shader(Path pack, Map<String, String> options) {
        public Shader { options = Map.copyOf(options); }
        public static Shader disabled() { return new Shader(null, Map.of()); }
        public static Shader pack(Path path) { return new Shader(Objects.requireNonNull(path), Map.of()); }
    }
    public CameraRenderSettings {
        if (renderDistance < 2 || renderDistance > 32)
            throw new IllegalArgumentException("Render distance must be between 2 and 32 chunks");
        Objects.requireNonNull(quality); Objects.requireNonNull(shader);
        resourcePacks = resourcePacks.stream().map(p -> p.toAbsolutePath().normalize()).toList();
        if (mipmapLevels < 0 || mipmapLevels > 4) throw new IllegalArgumentException("Mipmap levels must be between 0 and 4");
    }
    public static CameraRenderSettings defaults() {
        return new CameraRenderSettings(8, Quality.FANCY, Shader.disabled());
    }
}
